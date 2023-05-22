/*
 * Copyright 2022 - 2023 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import jakarta.annotation.Nonnull;
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
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.*;
import org.elasticsoftware.akces.kafka.AggregatePartition;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
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
import static org.elasticsoftware.akces.AkcesControllerState.*;
import static org.elasticsoftware.akces.kafka.PartitionUtils.*;

public class AkcesController extends Thread implements AutoCloseable, ConsumerRebalanceListener, AkcesRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AkcesController.class);
    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final ProducerFactory<String, AkcesControlRecord> controlProducerFactory;
    private final ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory;
    private final AggregateRuntime aggregateRuntime;
    private final KafkaAdminOperations kafkaAdmin;
    private final Map<Integer,AggregatePartition> aggregatePartitions = new HashMap<>();
    private final ExecutorService executorService;
    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private Integer partitions = null;
    private final Map<String, CommandServiceRecord> commandServices = new ConcurrentHashMap<>();
    private Consumer<String, AkcesControlRecord> controlConsumer;
    private final AggregateStateRepositoryFactory aggregateStateRepositoryFactory;
    private volatile AkcesControllerState processState = INITIALIZING;
    private final List<TopicPartition> partitionsToAssign = new ArrayList<>();
    private final List<TopicPartition> partitionsToRevoke = new ArrayList<>();

    public AkcesController(ConsumerFactory<String, ProtocolRecord> consumerFactory,
                           ProducerFactory<String, ProtocolRecord> producerFactory,
                           ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory,
                           ProducerFactory<String, AkcesControlRecord> controlProducerFactory,
                           AggregateStateRepositoryFactory aggregateStateRepositoryFactory,
                           AggregateRuntime aggregateRuntime,
                           KafkaAdminOperations kafkaAdmin) {
        super(aggregateRuntime.getName()+"-AkcesController");
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
        this.controlProducerFactory = controlProducerFactory;
        this.controlRecordConsumerFactory = controlConsumerFactory;
        this.aggregateStateRepositoryFactory = aggregateStateRepositoryFactory;
        this.aggregateRuntime = aggregateRuntime;
        this.kafkaAdmin = kafkaAdmin;
        this.executorService = Executors.newCachedThreadPool(new CustomizableThreadFactory(aggregateRuntime.getName()+"AggregatePartitionThread-"));
    }

    @Override
    public void run() {
        // make sure all our events are registered and validated
        try {
            // first register and validate all local (= owned) domain events
            for (DomainEventType<?> domainEventType : aggregateRuntime.getProducedDomainEventTypes()) {
                aggregateRuntime.registerAndValidate(domainEventType);
            }
            // find out about the cluster
            partitions = kafkaAdmin.describeTopics("Akces-Control").get("Akces-Control").partitions().size();
            // publish our own record
            publishControlRecord(partitions);
            // and start consuming
            controlConsumer =
                    controlRecordConsumerFactory.createConsumer(
                            aggregateRuntime.getName(),
                            aggregateRuntime.getName() + "-" + HostUtils.getHostName() + "-control",
                            null);
            controlConsumer.subscribe(List.of("Akces-Control"), this);
            //controlConsumer.enforceRebalance();
            while (processState != SHUTTING_DOWN) {
                process();
            }
            controlConsumer.close();
            // close all aggregate partitions
            aggregatePartitions.keySet().forEach(partition -> {
                AggregatePartition aggregatePartition = aggregatePartitions.remove(partition);
                if (aggregatePartition != null) {
                    try {
                        aggregatePartition.close();
                    } catch (Exception e) {
                        logger.error("Error closing AggregatePartition " + aggregatePartition.getId(), e);
                    }
                }
            });
        } catch (Exception e) {
            logger.error("Error in AkcesController", e);
        }
    }

    private void process() {
        if(processState == RUNNING || processState == INITIALIZING) {
            try {
                // the data on the AkcesControl topics are broadcasted to all partitions
                // so we only need to read one partition actually
                // for simplicity we just read them all for now
                ConsumerRecords<String, AkcesControlRecord> consumerRecords = controlConsumer.poll(Duration.ofMillis(1000));
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
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesController", e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            }
        }  else if(processState == INITIAL_REBALANCING) {
            // we need to load all the service data and then move to REBALANCING
            try {
                // seek to beginning to load all
                controlConsumer.seekToBeginning(partitionsToAssign);
                // the data on the AkcesControl topics are broadcasted to all partitions
                // so we only need to read one partition actually
                // for simplicity we just read them all for now
                ConsumerRecords<String, AkcesControlRecord> consumerRecords = controlConsumer.poll(Duration.ofMillis(1000));
                while (!consumerRecords.isEmpty()) {
                    consumerRecords.forEach(record -> {
                        AkcesControlRecord controlRecord = record.value();
                        if (controlRecord instanceof CommandServiceRecord commandServiceRecord) {
                            // only log it once
                            if(!commandServices.containsKey(record.key())) {
                                logger.info("Discovered service: {}", commandServiceRecord.aggregateName());
                            }
                            commandServices.put(record.key(), commandServiceRecord);
                        } else {
                            logger.info("Received unknown AkcesControlRecord type: {}", controlRecord.getClass().getSimpleName());
                        }
                    });
                    // poll again
                    consumerRecords = controlConsumer.poll(Duration.ofMillis(1000));
                }
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesController", e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            }
            // register external domain event types
            // TODO: maybe this needs it's own state
            for (DomainEventType<?> domainEventType : aggregateRuntime.getExternalDomainEventTypes()) {
                try {
                    aggregateRuntime.registerAndValidate(domainEventType);
                } catch (Exception e) {
                    logger.error("Error registering external domain event type: {}:{}", domainEventType.typeName(),domainEventType.version(), e);
                    processState = SHUTTING_DOWN;
                }
            }
            // now we can move to REBALANCING
            processState = REBALANCING;
        } else if(processState == REBALANCING) {
            // first revoke
            for (TopicPartition topicPartition : partitionsToRevoke) {
                AggregatePartition aggregatePartition = aggregatePartitions.remove(topicPartition.partition());
                if (aggregatePartition != null) {
                    try {
                        aggregatePartition.close();
                    } catch (Exception e) {
                        logger.error("Error closing AggregatePartition", e);
                    }
                }
            }
            partitionsToRevoke.clear();
            // then assign
            for (TopicPartition topicPartition : partitionsToAssign) {
                AggregatePartition aggregatePartition = new AggregatePartition(
                        consumerFactory,
                        producerFactory,
                        aggregateRuntime,
                        aggregateStateRepositoryFactory,
                        topicPartition.partition(),
                        toCommandTopicPartition(aggregateRuntime, topicPartition.partition()),
                        toDomainEventTopicPartition(aggregateRuntime, topicPartition.partition()),
                        toAggregateStateTopicPartition(aggregateRuntime, topicPartition.partition()),
                        aggregateRuntime.getExternalDomainEventTypes(),
                        this);
                aggregatePartitions.put(topicPartition.partition(), aggregatePartition);
                executorService.submit(aggregatePartition);
            }
            partitionsToAssign.clear();
            // move back to running
            processState = RUNNING;
        }
    }

    private void publishControlRecord(int partitions) {
        String transactionalId = aggregateRuntime.getName() + "-" + HostUtils.getHostName() + "-control";
        try (Producer<String,AkcesControlRecord> controlProducer = controlProducerFactory.createProducer(transactionalId)) {
            // publish the CommandServiceRecord
            CommandServiceRecord commandServiceRecord = new CommandServiceRecord(
                    aggregateRuntime.getName(),
                    aggregateRuntime.getName() + COMMANDS_SUFFIX,
                    aggregateRuntime.getName() + DOMAINEVENTS_SUFFIX,
                    aggregateRuntime.getCommandTypes().stream()
                            .map(commandType ->
                                    new CommandServiceCommandType(
                                        commandType.typeName(),
                                        commandType.version(),
                                        commandType.create())).toList(),
                    aggregateRuntime.getProducedDomainEventTypes().stream().map(domainEventType ->
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
        this.processState = SHUTTING_DOWN;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> collection) {
        // stop all local AggregatePartition instances
        partitionsToRevoke.addAll(collection);
        // if we are already running, we can immediately rebalance
        if(processState == RUNNING) {
            processState = REBALANCING;
        } else if(processState == INITIALIZING) { // otherwise we first have to load the services data
            processState = INITIAL_REBALANCING;
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> collection) {
        partitionsToAssign.addAll(collection);
        // if we are already running, we can immediately rebalance
        if(processState == RUNNING) {
            processState = REBALANCING;
        } else if(processState == INITIALIZING) { // otherwise we first have to load the services data
            processState = INITIAL_REBALANCING;
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

    private boolean producesDomainEvent(List<CommandServiceDomainEventType> producedEvents, DomainEventType<?> externalDomainEventType) {
        for (CommandServiceDomainEventType<?> producedEvent : producedEvents) {
            if (producedEvent.typeName().equals(externalDomainEventType.typeName()) &&
                    producedEvent.version() == externalDomainEventType.version()) {
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
    public String resolveTopic(@Nonnull DomainEventType<?> externalDomainEventType) {
        List<CommandServiceRecord> services = commandServices.values().stream()
                .filter(commandServiceRecord -> producesDomainEvent(commandServiceRecord.producedEvents(), externalDomainEventType))
                .toList();
        if(services.size() == 1) {
            return services.get(0).domainEventTopic();
        } else {
            throw new IllegalStateException("Cannot determine which service produces DomainEvent " + externalDomainEventType.typeName() + " v" + externalDomainEventType.version());
        }
    }

    @Override
    @Nonnull
    public Integer resolvePartition(@Nonnull String aggregateId) {
        return Math.abs(hashFunction.hashString(aggregateId, UTF_8).asInt()) % partitions;
    }

    public boolean isRunning() {
        return processState == RUNNING;
    }
}
