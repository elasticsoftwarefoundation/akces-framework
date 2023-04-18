package org.elasticsoftware.akces;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.*;
import org.elasticsoftware.akces.kafka.AggregatePartition;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.state.InMemoryAggregateStateRepository;
import org.elasticsoftware.akces.util.HostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdminOperations;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.elasticsoftware.akces.kafka.PartitionUtils.*;

public class AkcesControl extends Thread implements AutoCloseable, ConsumerRebalanceListener, AkcesRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AkcesControl.class);
    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final ProducerFactory<String, AkcesControlRecord> controlProducerFactory;
    private final ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory;
    private final AggregateRuntime aggregateRuntime;
    private final KafkaAdminOperations kafkaAdmin;
    private volatile boolean running = true;
    private final Map<Integer,AggregatePartition> aggregatePartitions = new HashMap<>();
    private final ExecutorService executorService;
    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private Integer partitions = null;
    private final Map<String, CommandServiceRecord> commandServices = new ConcurrentHashMap<>();
    Consumer<String, AkcesControlRecord> controlConsumer;

    public AkcesControl(ConsumerFactory<String, ProtocolRecord> consumerFactory,
                        ProducerFactory<String, ProtocolRecord> producerFactory,
                        ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory,
                        ProducerFactory<String, AkcesControlRecord> controlProducerFactory,
                        AggregateRuntime aggregateRuntime,
                        KafkaAdminOperations kafkaAdmin) {
        super(aggregateRuntime.getName()+"-AkcesControl");
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
        this.controlProducerFactory = controlProducerFactory;
        this.controlRecordConsumerFactory = controlConsumerFactory;
        this.aggregateRuntime = aggregateRuntime;
        this.kafkaAdmin = kafkaAdmin;
        this.executorService = Executors.newCachedThreadPool(new CustomizableThreadFactory(aggregateRuntime.getName()+"AggregatePartitionThread-"));
    }

    @Override
    public void run() {
        // find out about the cluster
        partitions = kafkaAdmin.describeTopics("Akces-Control").get("Akces-Control").partitions().size();
        // publish out own record
        publishControlRecord(partitions);
        // and start consuming
        controlConsumer =
                controlRecordConsumerFactory.createConsumer(
                        aggregateRuntime.getName(),
                        aggregateRuntime.getName() + "-" + HostUtils.getHostName() + "-control",
                        null);
            controlConsumer.subscribe(List.of("Akces-Control"), this);
            //controlConsumer.enforceRebalance();
            while (running) {
                try {
                    // the data on the AkcesControl topics are broadcasted to all partitions
                    // so we only need to read one partition actually
                    // for simplicity we just read them all for now
                    ConsumerRecords<String, AkcesControlRecord> consumerRecords = controlConsumer.poll(Duration.ofMillis(100));
                    if (!consumerRecords.isEmpty()) {
                        consumerRecords.forEach(record -> {
                            AkcesControlRecord controlRecord = record.value();
                            if (controlRecord instanceof CommandServiceRecord commandServiceRecord) {
                                logger.info("Discovered service: {}", commandServiceRecord.aggregateName());
                                commandServices.put(record.key(), commandServiceRecord);
                            } else {
                                logger.info("Received unknown AkcesControlRecord type: {}", controlRecord.getClass().getSimpleName());
                            }
                        });
                    }
                } catch (WakeupException | InterruptException e) {
                    // ignore
                } catch(KafkaException e) {
                    // this is an unrecoverable exception
                    logger.error("Unrecoverable exception in AkcesControl", e);
                    // drop out of the control loop, this will shut down all resources
                    running = false;
                }
            }
        controlConsumer.close();
        // close all aggregate partitions
        aggregatePartitions.keySet().forEach(partition -> {
            AggregatePartition aggregatePartition = aggregatePartitions.remove(partition);
            if (aggregatePartition != null) {
                try {
                    aggregatePartition.close();
                } catch (Exception e) {
                    logger.error("Error closing AggregatePartition "+aggregatePartition.getId(), e);
                }
            }
        });
    }

    private void publishControlRecord(int partitions) {
        String transactionalId = aggregateRuntime.getName() + "-" + HostUtils.getHostName() + "-control";
        try (Producer<String,AkcesControlRecord> controlProducer = controlProducerFactory.createProducer(transactionalId)) {
            // publish the CommandServiceRecord
            CommandServiceRecord commandServiceRecord = new CommandServiceRecord(
                    aggregateRuntime.getName(),
                    aggregateRuntime.getName() + "-Commands",
                    aggregateRuntime.getCommandTypes().stream()
                            .map(commandType ->
                                    new CommandServiceCommandType(
                                        commandType.typeName(),
                                        commandType.version(),
                                        commandType.create())).toList(),
                    aggregateRuntime.getDomainEventTypes().stream().map(domainEventType ->
                            new CommandServiceDomainEventType(
                                    domainEventType.typeName(),
                                    domainEventType.version(),
                                    domainEventType.create(),
                                    domainEventType.external())).toList(),
                    aggregateRuntime.getExternalDomainEventTypes().stream().map(externalDomainEventType ->
                            new CommandServiceDomainEventType(
                                    externalDomainEventType.typeName(),
                                    externalDomainEventType.version(),
                                    externalDomainEventType.create(),
                                    externalDomainEventType.external())).toList());
            controlProducer.beginTransaction();
            for (int partition = 0; partition < partitions; partition++) {
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, aggregateRuntime.getName(), commandServiceRecord));
            }
            controlProducer.commitTransaction();
        } catch (Exception e) {
            logger.error("Error publishing CommandServiceRecord", e);
        }
    }

    @Override
    public void close() throws Exception {
        this.running = false;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> collection) {
        // stop all local AggregatePartition instances
        for (TopicPartition topicPartition : collection) {
            AggregatePartition aggregatePartition = aggregatePartitions.remove(topicPartition.partition());
            if (aggregatePartition != null) {
                try {
                    aggregatePartition.close();
                } catch (Exception e) {
                    logger.error("Error closing AggregatePartition", e);
                }
            }
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> collection) {
        // seek to beginning to load all
        controlConsumer.seekToBeginning(collection);
        // start all local AggregatePartition instances
        for (TopicPartition topicPartition : collection) {
            AggregatePartition aggregatePartition = new AggregatePartition(
                    consumerFactory,
                    producerFactory,
                    aggregateRuntime,
                    new InMemoryAggregateStateRepository(), //TODO: create this from a factory to support different implementations
                    topicPartition.partition(),
                    toCommandTopicPartition(aggregateRuntime, topicPartition.partition()),
                    toDomainEventTopicPartition(aggregateRuntime, topicPartition.partition()),
                    toAggregateStateTopicPartition(aggregateRuntime, topicPartition.partition()),
                    toExternalDomainEventTopicPartitions(aggregateRuntime, topicPartition.partition()),
                    this);
            aggregatePartitions.put(topicPartition.partition(), aggregatePartition);
            executorService.submit(aggregatePartition);
        }
    }

    @Override @Nonnull
    public CommandType<?> resolveType(@Nonnull Class<? extends Command> commandClass) {
        // TODO: if the command class is for an external service it won't be derived from the local Aggregate
        CommandInfo commandInfo = commandClass.getAnnotation(CommandInfo.class);
        if(commandInfo != null) {
            List<CommandServiceRecord> services = commandServices.values().stream()
                    .filter(commandServiceRecord -> supportsCommand(commandServiceRecord.supportedCommands(), commandInfo))
                    .toList();
            if(services.size() == 1) {
                CommandServiceRecord commandServiceRecord = services.get(0);
                if(aggregateRuntime.getName().equals(commandServiceRecord.aggregateName())) {
                    // this is a local command (will be sent to self)
                    return aggregateRuntime.getLocalCommandType(commandInfo.type(), commandInfo.version());
                } else {
                    // this is a command for an external service
                    return new CommandType<>(commandInfo.type(), commandInfo.version(), commandClass, false, true);
                }
            } else {
                // TODO: throw exception, we cannot determine where to send the command
                throw new IllegalStateException("Cannot determine where to send command " + commandClass.getName());
            }

        } else {
            throw new IllegalStateException("Command class " + commandClass.getName() + " is not annotated with @CommandInfo");
        }
    }

    private boolean supportsCommand(List<CommandServiceCommandType> supportedCommands, CommandInfo commandInfo) {
        for (CommandServiceCommandType<?> supportedCommand : supportedCommands) {
            if (supportedCommand.typeName().equals(commandInfo.type()) &&
                    supportedCommand.version() == commandInfo.version()) {
                return true;
            }
        }
        return false;
    }

    private boolean supportsCommand(List<CommandServiceCommandType> supportedCommands, CommandType<?> commandType) {
        for (CommandServiceCommandType<?> supportedCommand : supportedCommands) {
            if (supportedCommand.typeName().equals(commandType.typeName()) &&
                    supportedCommand.version() == commandType.version()) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Nonnull
    public String resolveTopic(@Nonnull Class<? extends Command> commandClass) {
        return resolveTopic(resolveType(commandClass));
    }

    @Override
    @Nonnull
    public String resolveTopic(@Nonnull CommandType<?> commandType) {
        List<CommandServiceRecord> services = commandServices.values().stream()
                .filter(commandServiceRecord -> supportsCommand(commandServiceRecord.supportedCommands(), commandType))
                .toList();
        if(services.size() == 1) {
            return services.get(0).commandTopic();
        } else {
            throw new IllegalStateException("Cannot determine where to send command " + commandType.typeName() + " v" + commandType.version());
        }
    }

    @Override
    @Nonnull
    public Integer resolvePartition(@Nonnull String aggregateId) {
        return Math.abs(hashFunction.hashString(aggregateId, UTF_8).asInt()) % partitions;
    }
}
