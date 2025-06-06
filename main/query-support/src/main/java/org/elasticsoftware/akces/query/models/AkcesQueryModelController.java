package org.elasticsoftware.akces.query.models;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import jakarta.annotation.Nullable;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.gdpr.GDPRContextHolder;
import org.elasticsoftware.akces.gdpr.GDPRContextRepository;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelState;
import org.elasticsoftware.akces.util.HostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdminOperations;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.elasticsoftware.akces.query.models.AkcesQueryModelControllerState.*;
import static org.elasticsoftware.akces.util.KafkaUtils.getIndexTopicName;

@SuppressWarnings({"rawtypes", "unchecked"})
public class AkcesQueryModelController extends Thread implements AutoCloseable, ApplicationContextAware, QueryModels {
    private static final Logger logger = LoggerFactory.getLogger(AkcesQueryModelController.class);
    private final Map<Class<? extends QueryModel>, QueryModelRuntime> enabledRuntimes = new ConcurrentHashMap<>();
    private final Map<Class<? extends QueryModel>, QueryModelRuntime> disabledRuntimes = new ConcurrentHashMap<>();
    private final KafkaAdminOperations kafkaAdmin;
    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final GDPRContextRepositoryFactory gdprContextRepositoryFactory;
    private final Map<TopicPartition, GDPRContextRepository> gdprContextRepositories = new HashMap<>();
    private final BlockingQueue<HydrationRequest<?>> commandQueue = new LinkedBlockingQueue<>();
    private final Map<TopicPartition, HydrationExecution<?>> hydrationExecutions = new HashMap<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile AkcesQueryModelControllerState processState = INITIALIZING;
    private final Set<TopicPartition> gdprKeyPartitions = new HashSet<>();
    private Map<TopicPartition, Long> initializedEndOffsets = Collections.emptyMap();
    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private int totalPartitions;
    private ApplicationContext applicationContext;
    private final Cache<String, CachedQueryModelState<?>> queryModelStateCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .build();

    public AkcesQueryModelController(KafkaAdminOperations kafkaAdmin,
                                     ConsumerFactory<String, ProtocolRecord> consumerFactory,
                                     GDPRContextRepositoryFactory gdprContextRepositoryFactory) {
        super("AkcesQueryModelController");
        this.kafkaAdmin = kafkaAdmin;
        this.consumerFactory = consumerFactory;
        this.gdprContextRepositoryFactory = gdprContextRepositoryFactory;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        this.enabledRuntimes.putAll(
                applicationContext.getBeansOfType(QueryModelRuntime.class).values().stream()
                        .collect(Collectors.toMap(runtime -> ((QueryModelRuntime<?>) runtime).getQueryModelClass(), runtime -> runtime)));
    }


    @SuppressWarnings("unchecked")
    private <S extends QueryModelState> QueryModelRuntime<S> getEnabledRuntime(Class<? extends QueryModel<S>> modelClass) {
        return (QueryModelRuntime<S>) this.enabledRuntimes.get(modelClass);
    }

    private <S extends QueryModelState> boolean isRuntimeDisabled(Class<? extends QueryModel<S>> modelClass) {
        return this.disabledRuntimes.containsKey(modelClass);
    }

    @Override
    public <S extends QueryModelState> CompletionStage<S> getHydratedState(Class<? extends QueryModel<S>> modelClass, String id) {
        QueryModelRuntime<S> runtime = getEnabledRuntime(modelClass);
        if (runtime != null) {
            CachedQueryModelState<S> cachedQueryModelState = (CachedQueryModelState<S>) queryModelStateCache.getIfPresent(runtime.getName()+"-"+id);
            S currentState = cachedQueryModelState != null ? cachedQueryModelState.state() : null;
            Long currentOffset = cachedQueryModelState != null ? cachedQueryModelState.offset() : null;
            CompletableFuture<S> completableFuture = new CompletableFuture<>();
            commandQueue.add(new HydrationRequest<>(runtime, completableFuture, id, currentState, currentOffset));
            return completableFuture;
        } else if (isRuntimeDisabled(modelClass)) {
            // TODO: add Schema differences
            return CompletableFuture.failedFuture(new QueryModelExecutionDisabledException(modelClass));
        } else {
            // not found
            return CompletableFuture.failedFuture(new QueryModelNotFoundException(modelClass));
        }
    }

    @Override
    public void run() {
        try (final Consumer<String, ProtocolRecord> indexConsumer = consumerFactory.createConsumer(
                HostUtils.getHostName() + "-AkcesQueryModelController",
                HostUtils.getHostName() + "-AkcesQueryModelController",
                null)) {

            while (processState != SHUTTING_DOWN) {
                process(indexConsumer);
            }
            logger.info("AkcesQueryModelController is shutting down");
            // handle all pending requests
            List<HydrationRequest<?>> pendingRequests = new ArrayList<>();
            commandQueue.drainTo(pendingRequests);
            pendingRequests.forEach(request -> request.completableFuture.completeExceptionally(
                    new QueryModelExecutionCancelledException(request.runtime().getQueryModelClass())));
            // handle all pending executions
            Iterator<HydrationExecution<?>> iterator = hydrationExecutions.values().iterator();
            while (iterator.hasNext()) {
                HydrationExecution<?> execution = iterator.next();
                execution.completableFuture.completeExceptionally(
                        new QueryModelExecutionCancelledException(execution.runtime().getQueryModelClass()));
                iterator.remove();
            }
            // close the GDPRContextRepositories
            for (GDPRContextRepository gdprContextRepository : gdprContextRepositories.values()) {
                try {
                    gdprContextRepository.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            // raise an error for the liveness check
            applicationContext.publishEvent(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));
        }
        shutdownLatch.countDown();
    }

    private void process(Consumer<String, ProtocolRecord> indexConsumer) {
        if (processState == RUNNING) {
            try {
                Map<TopicPartition, HydrationExecution<?>> newExecutions = processHydrationRequests(indexConsumer);
                // assign all the hydrationExecution partition and the gdpKeyPartitions (if any)
                hydrationExecutions.putAll(newExecutions);
                indexConsumer.assign(Stream.concat(hydrationExecutions.keySet().stream(), gdprKeyPartitions.stream()).toList());
                // set the correct end offset
                if(!newExecutions.isEmpty()){
                    logger.info("Processing {} new HydrationExecutions", newExecutions.size());
                    // we need to get the endoffsets for all the partitions
                    indexConsumer.endOffsets(newExecutions.keySet()).forEach((partition, endOffset) ->
                            hydrationExecutions.computeIfPresent(partition, (topicPartition, hydrationExecution) ->
                                    hydrationExecution.withEndOffset(endOffset)));
                }
                // seek to the correct offset for the new executions
                newExecutions.forEach((partition, execution) -> {
                    if (execution.currentOffset() != null) {
                        indexConsumer.seek(partition, execution.currentOffset());
                    } else if(hydrationExecutions.get(partition).endOffset() > 0) {
                        // we have existing data in the index (as expected). so use seek
                        indexConsumer.seek(partition, 0);
                    } else {
                        // no data in the index, so we need to poll
                        indexConsumer.seekToBeginning(List.of(partition));
                    }
                });
                if(!hydrationExecutions.isEmpty()) {
                    logger.info("Processing HydrationExecutions {}", hydrationExecutions);
                }
                ConsumerRecords<String, ProtocolRecord> consumerRecords = indexConsumer.poll(Duration.ofMillis(10));
                if(!consumerRecords.isEmpty()) {
                    logger.info("Processing {}", consumerRecords.partitions());
                    // first process the gdpr keys
                    if(!gdprKeyPartitions.isEmpty()) {
                        List<TopicPartition> gdprKeyPartitions = consumerRecords.partitions().stream()
                                .filter(topicPartition -> topicPartition.topic().equals("Akces-GDPRKeys")).toList();
                        logger.info("Processing {} GDPRKeyPartitions", gdprKeyPartitions.size());
                        for (TopicPartition gdprKeyPartition : gdprKeyPartitions) {
                            gdprContextRepositories.get(gdprKeyPartition).process(consumerRecords.records(gdprKeyPartition));
                        }
                    }
                    if(!hydrationExecutions.isEmpty()) {
                        List<TopicPartition> indexPartitions = consumerRecords.partitions().stream()
                                .filter(partition -> !partition.topic().equals("Akces-GDPRKeys")).toList();
                        logger.info("Processing {} indexPartitions", indexPartitions.size());
                        for (TopicPartition partition : indexPartitions) {
                            hydrationExecutions.computeIfPresent(partition,
                                    (topicPartition, hydrationExecution) ->
                                            processHydrationExecution(
                                                    hydrationExecution.runtime().shouldHandlePIIData() ? getGDPRContextRepository(hydrationExecution.id()) : null,
                                                    hydrationExecution,
                                                    consumerRecords.records(partition)));
                        }
                    }
                }
                // check for stop condition
                Iterator<HydrationExecution<?>> itr = hydrationExecutions.values().iterator();
                while (itr.hasNext()) {
                    HydrationExecution<?> execution = itr.next();
                    if (execution.endOffset() <= indexConsumer.position(execution.indexPartition())) {
                        logger.info(
                                "HydrationExecution on index {} with id {} and runtime {} is complete: indexPartition {} endOffset {} consumerPosition {}",
                                execution.runtime().getIndexName(),
                                execution.id(),
                                execution.runtime().getName(),
                                execution.indexPartition(),
                                execution.endOffset(),
                                indexConsumer.position(execution.indexPartition()));
                        // we are done with this execution
                        execution.complete();
                        itr.remove();
                        queryModelStateCache.put(
                                execution.runtime().getName()+"-"+execution.id(),
                                new CachedQueryModelState<>(
                                        execution.currentState(),
                                        indexConsumer.position(execution.indexPartition())));
                    }
                }
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesQueryModelController while {}", processState, e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            }
        } else if (processState == LOADING_GDPR_KEYS) {
            // read all of the GDPR key topics
            try {
                ConsumerRecords<String, ProtocolRecord> gdprKeyRecords = indexConsumer.poll(Duration.ofMillis(10));
                // iterate over all of the gdpr partitions
                for (TopicPartition gdprKeyPartition : gdprKeyPartitions) {
                   gdprContextRepositories.get(gdprKeyPartition).process(gdprKeyRecords.records(gdprKeyPartition));
                   // check the stopcondition (this removes the entry from the initializedEndOffsets map)
                    initializedEndOffsets.computeIfPresent(gdprKeyPartition, (partition, endOffset) -> {
                        if (endOffset <= indexConsumer.position(gdprKeyPartition)) {
                            return null;
                        } else {
                            return endOffset;
                        }
                    });
                }
                // stop condition
                if (gdprKeyRecords.isEmpty() && initializedEndOffsets.isEmpty()) {
                    processState = RUNNING;
                }
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesQueryModelController while {}", processState, e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            }
        } else if (processState == INITIALIZING) {
            try {
                Iterator<QueryModelRuntime> iterator = enabledRuntimes.values().iterator();
                while (iterator.hasNext()) {
                    QueryModelRuntime queryModelRuntime = iterator.next();
                    try {
                        queryModelRuntime.validateDomainEventSchemas();
                        logger.info("Enabling {} QueryModelRuntime", queryModelRuntime.getName());
                    } catch (org.elasticsoftware.akces.schemas.SchemaException e) {
                        logger.error(
                                "SchemaException while validating DomainEventSchemas for QueryModel {}. Disabling QueryModel",
                                queryModelRuntime.getName(),
                                e);
                        iterator.remove();
                        // mark the runtime as disabled
                        disabledRuntimes.put(queryModelRuntime.getQueryModelClass(), queryModelRuntime);
                    }
                }
                if(enabledRuntimes.isEmpty() && !disabledRuntimes.isEmpty()) {
                    logger.error("No QueryModelRuntimes enabled. This is an error. Shutting down");
                    processState = SHUTTING_DOWN;
                } else if (enabledRuntimes.values().stream().anyMatch(QueryModelRuntime::shouldHandlePIIData)) {
                    // we need to load the gdpr keys
                    logger.info("Loading GDPR keys");
                    // first find out about the cluster
                    TopicDescription controlTopicDescription = kafkaAdmin.describeTopics("Akces-Control").get("Akces-Control");
                    totalPartitions = controlTopicDescription.partitions().size();
                    // create all the gdpr key partitions
                    for (int i = 0; i < totalPartitions; i++) {
                        gdprKeyPartitions.add(new TopicPartition("Akces-GDPRKeys", i));
                    }
                    // create the GDPRContextRepositories for the TopicPartitions
                    gdprKeyPartitions.forEach(partition -> {
                        gdprContextRepositories.put(partition, gdprContextRepositoryFactory.create("AkcesQueryModelController",partition.partition()));
                    });
                    // assign the partitions to the consumer
                    indexConsumer.assign(gdprKeyPartitions);
                    // seek to the correct offset
                    gdprKeyPartitions.forEach(partition -> {
                        indexConsumer.seek(partition, gdprContextRepositories.get(partition).getOffset() + 1);
                    });
                    // and set the initial offsets
                    initializedEndOffsets = indexConsumer.endOffsets(gdprKeyPartitions);
                    processState = LOADING_GDPR_KEYS;
                } else {
                    // go straight to running
                    processState = RUNNING;
                }
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesQueryModelController while {}", processState, e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            }
        }
    }

    private GDPRContextRepository getGDPRContextRepository(String id) {
        Integer partition = Math.abs(hashFunction.hashString(id, UTF_8).asInt()) % totalPartitions;
        return gdprContextRepositories.get(new TopicPartition("Akces-GDPRKeys", partition));
    }

    private Map<TopicPartition, HydrationExecution<?>> processHydrationRequests(Consumer<String, ProtocolRecord> indexConsumer) {
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
                    request.completableFuture().completeExceptionally(new QueryModelIdNotFoundException(request.runtime().getQueryModelClass(), request.id()));
                }
                request = commandQueue.poll();
            }
            return newExecutions;
        } catch (InterruptedException e) {
            logger.warn("Interrupted while processing HydrationRequests", e);
            // ignore
            Thread.currentThread().interrupt();
        }
        return newExecutions;
    }

    private <S extends QueryModelState> HydrationExecution<S> processHydrationExecution(@Nullable GDPRContextRepository gdprContextRepository,
                                                                                        HydrationExecution<S> execution,
                                                                                        List<ConsumerRecord<String, ProtocolRecord>> records) {
        try {
            if(gdprContextRepository != null) {
                GDPRContext gdprContext = gdprContextRepository.get(execution.id());
                logger.info("Setting GDPRContext {} for aggregateId {}", gdprContext.getClass().getSimpleName(), execution.id());
                GDPRContextHolder.setCurrentGDPRContext(gdprContext);
            }
            logger.info(
                    "Processing {} records HydrationExecution on index {} with id {} and runtime {}",
                    records.size(),
                    execution.runtime().getIndexName(),
                    execution.id(),
                    execution.runtime().getName());
            return execution.withCurrentState(execution.runtime().apply(
                    records.stream().map(record -> (DomainEventRecord) record.value())
                            .toList(), execution.currentState()));
        } catch (IOException e) {
            logger.error("Exception while processing HydrationExecution", e);
            execution.completableFuture.completeExceptionally(
                    new QueryModelExecutionException(
                            "Exception while processing HydrationExecution",
                            execution.runtime().getQueryModelClass(),
                            e));
            return null; // this will remove the HydrationExecution from the map
        } finally {
            GDPRContextHolder.resetCurrentGDPRContext();
        }
    }

    @Override
    public void close() throws Exception {
        processState = SHUTTING_DOWN;
        // wait maximum of 10 seconds for the shutdown to complete
        try {
            if (shutdownLatch.await(10, TimeUnit.SECONDS)) {
                logger.info("AkcesQueryModelController has been shutdown");
            } else {
                logger.warn("AkcesQueryModelController did not shutdown within 10 seconds");
            }
        } catch (InterruptedException e) {
            // ignore
        }
    }

    public boolean isRunning() {
        return processState == RUNNING;
    }

    private record HydrationRequest<S extends QueryModelState>(QueryModelRuntime<S> runtime,
                                                               CompletableFuture<S> completableFuture, String id,
                                                               S currentState, Long currentOffset) {
    }

    private record HydrationExecution<S extends QueryModelState>(QueryModelRuntime<S> runtime,
                                                                 CompletableFuture<S> completableFuture, String id,
                                                                 S currentState, Long currentOffset,
                                                                 TopicPartition indexPartition, Long endOffset) {
        HydrationExecution<S> withEndOffset(Long endOffset) {
            return new HydrationExecution<>(runtime, completableFuture, id, currentState, currentOffset, indexPartition, endOffset);
        }

        HydrationExecution<S> withCurrentState(S currentState) {
            return new HydrationExecution<>(runtime, completableFuture, id, currentState, currentOffset, indexPartition, endOffset);
        }

        void complete() {
            if(currentState != null) {
                completableFuture.complete(currentState);
            } else {
                // TODO: this should not happen because we only create the index topic when there is an event to write
                completableFuture.completeExceptionally(new QueryModelIdNotFoundException(runtime.getQueryModelClass(), id));
            }
        }
    }

    private record CachedQueryModelState<S extends QueryModelState>(S state, Long offset) {
    }
}
