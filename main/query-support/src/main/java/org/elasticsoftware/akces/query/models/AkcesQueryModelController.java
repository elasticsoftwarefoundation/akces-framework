package org.elasticsoftware.akces.query.models;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelState;
import org.elasticsoftware.akces.util.HostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.kafka.core.ConsumerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.elasticsoftware.akces.query.models.AkcesQueryModelControllerState.*;
import static org.elasticsoftware.akces.util.TopicUtils.getIndexTopicName;

@SuppressWarnings("rawtypes")
public class AkcesQueryModelController extends Thread implements AutoCloseable, ApplicationContextAware, QueryModels {
    private static final Logger logger = LoggerFactory.getLogger(AkcesQueryModelController.class);
    private final Map<Class<? extends QueryModel>, QueryModelRuntime> runtimes = new HashMap<>();
    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private volatile AkcesQueryModelControllerState processState = INITIALIZING;
    private final BlockingQueue<HydrationRequest<?>> commandQueue = new LinkedBlockingQueue<>();
    private final Map<TopicPartition, HydrationExecution<?>> hydrationExecutions = new HashMap<>();

    public AkcesQueryModelController(ConsumerFactory<String, ProtocolRecord> consumerFactory) {
        super("AkcesQueryModelController");
        this.consumerFactory = consumerFactory;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.runtimes.putAll(
                applicationContext.getBeansOfType(QueryModelRuntime.class).values().stream()
                .collect(Collectors.toMap(runtime -> ((QueryModelRuntime<?>)runtime).getQueryModelClass(), runtime -> runtime)));
    }


    @SuppressWarnings("unchecked")
    public <S extends QueryModelState> QueryModelRuntime<S> getRuntime(Class<? extends QueryModel<S>> modelClass) {
        return (QueryModelRuntime<S>) this.runtimes.get(modelClass);
    }

    @Override
    public <S extends QueryModelState> CompletionStage<S> getHydratedState(Class<? extends QueryModel<S>> modelClass, String id) {
        // TODO: get state and offset from a cache
        S currentState = null;
        Long currentOffset = null;
        QueryModelRuntime<S> runtime = getRuntime(modelClass);
        CompletableFuture<S> completableFuture = new CompletableFuture<>();
        commandQueue.add(new HydrationRequest<>(runtime, completableFuture, id, currentState, currentOffset));
        return completableFuture;
    }

    @Override
    public void run() {
        try (final Consumer<String, ProtocolRecord> indexConsumer = consumerFactory.createConsumer(
                "AkcesQueryModelController",
                HostUtils.getHostName() + "-AkcesQueryModelController",
                null)) {
            while (processState != SHUTTING_DOWN) {
                process(indexConsumer);
            }
        }
    }

    private void process(Consumer<String, ProtocolRecord> indexConsumer) {
        if(processState == RUNNING) {
            try {
                Map<TopicPartition,HydrationExecution<?>> newExecutions = processHydrationRequests(indexConsumer);
                hydrationExecutions.putAll(newExecutions);
                indexConsumer.assign(hydrationExecutions.keySet());
                // seek to the correct offset for the new executions
                newExecutions.forEach((partition, execution) -> {
                    if (execution.currentOffset() != null) {
                        indexConsumer.seek(partition, execution.currentOffset());
                    } else {
                        indexConsumer.seekToBeginning(List.of(partition));
                    }
                });
                // only poll if there is something to poll
                if(!hydrationExecutions.isEmpty()) {
                    logger.info("Processing hydrationExecutions {}", hydrationExecutions);
                    ConsumerRecords<String, ProtocolRecord> consumerRecords = indexConsumer.poll(Duration.ofMillis(10));
                    for (TopicPartition partition : consumerRecords.partitions()) {
                        hydrationExecutions.computeIfPresent(partition,
                                (topicPartition, hydrationExecution) ->
                                        processHydrationExecution(hydrationExecution, consumerRecords.records(partition)));
                    }
                }
                // check for stop condition
                Iterator<HydrationExecution<?>> itr = hydrationExecutions.values().iterator();
                while(itr.hasNext()) {
                    HydrationExecution<?> execution = itr.next();
                    if(execution.endOffset() <= indexConsumer.position(execution.indexPartition())) {
                        // we are done with this execution
                        execution.complete();
                        itr.remove();
                        // TODO: store the state and offset in a cache
                    }
                }
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesQueryModelController", e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            }
        } else if(processState == INITIALIZING) {
            try {
                // TODO: we need to load the initial GDPRKeys here if necessary
                processState = RUNNING;
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesQueryModelController", e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            }
        }
    }

    private Map<TopicPartition,HydrationExecution<?>> processHydrationRequests(Consumer<String,ProtocolRecord> indexConsumer) {
        Map<TopicPartition, HydrationExecution<?>> newExecutions = new HashMap<>();
        // see if we have new commands to process
        try {
            HydrationRequest request = commandQueue.poll(100, TimeUnit.MILLISECONDS);
            while (request != null) {
                logger.info("Processing HydrationRequest on index {} with id {} and runtime {}", request.runtime().getIndexName(), request.id(), request.runtime().getName());
                // create the TopicPartition to check
                QueryModelRuntime runtime = request.runtime();
                String topicName = getIndexTopicName(runtime.getIndexName(), request.id());
                if (!indexConsumer.partitionsFor(topicName).isEmpty()) {
                    TopicPartition indexPartition = new TopicPartition(topicName, 0);
                    newExecutions.put(indexPartition, new HydrationExecution<>(runtime, request.completableFuture(), request.id(), request.currentState(), request.currentOffset(), indexPartition, null));
                } else {
                    logger.warn("KafkaTopic {} not found for HydrationRequest on index {} with id {}", topicName, request.runtime().getIndexName(), request.id());
                    // TODO: return a proper error here
                    request.completableFuture().completeExceptionally(new IllegalArgumentException("KafkaTopic not found"));
                }
                request = commandQueue.poll();
            }
            // we need to get the endoffsets for all the partitions
            indexConsumer.endOffsets(newExecutions.keySet()).forEach((partition, endOffset) -> {
                newExecutions.computeIfPresent(partition, (topicPartition, hydrationExecution) -> hydrationExecution.withEndOffset(endOffset));
            });
            return newExecutions;
        } catch (InterruptedException e) {
            // ignore
        }
        return newExecutions;
    }

    private <S extends QueryModelState> HydrationExecution<S> processHydrationExecution(HydrationExecution<S> execution,
                                                                    List<ConsumerRecord<String, ProtocolRecord>> records) {
        try {
            logger.info("Processing {} records HydrationExecution on index {} with id {} and runtime {}", records.size(), execution.runtime().getIndexName(), execution.id(), execution.runtime().getName());
            return execution.withCurrentState(execution.runtime().apply(
                    records.stream().map(record -> (DomainEventRecord) record.value())
                            .toList(), execution.currentState()));
        } catch (IOException e) {
            logger.error("Exception while processing HydrationExecution", e);
            // TODO: create our own exception
            execution.completableFuture.completeExceptionally(e);
            return null; // this will remove the HydrationExecution from the map
        }
    }

    @Override
    public void close() throws Exception {

    }

    public boolean isRunning() {
        return processState == RUNNING;
    }

    private record HydrationRequest<S extends QueryModelState>(QueryModelRuntime<S> runtime, CompletableFuture<S> completableFuture, String id, S currentState, Long currentOffset) { }

    private record HydrationExecution<S extends QueryModelState>(QueryModelRuntime<S> runtime, CompletableFuture<S> completableFuture, String id, S currentState, Long currentOffset, TopicPartition indexPartition, Long endOffset) {
        HydrationExecution<S> withEndOffset(Long endOffset) {
            return new HydrationExecution<>(runtime, completableFuture, id, currentState, currentOffset, indexPartition, endOffset);
        }
        HydrationExecution<S> withCurrentState(S currentState) {
            return new HydrationExecution<>(runtime, completableFuture, id, currentState, currentOffset, indexPartition, endOffset);
        }
        void complete() {
            completableFuture.complete(currentState);
        }
    }
}
