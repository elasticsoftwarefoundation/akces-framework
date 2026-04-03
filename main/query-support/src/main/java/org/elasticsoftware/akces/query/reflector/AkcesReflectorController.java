/*
 * Copyright 2022 - 2026 The Original Authors
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

package org.elasticsoftware.akces.query.reflector;

import tools.jackson.databind.ObjectMapper;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import jakarta.annotation.Nonnull;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.AggregateServiceCommandType;
import org.elasticsoftware.akces.control.AggregateServiceDomainEventType;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.control.AkcesRegistry;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.elasticsoftware.akces.schemas.storage.KafkaTopicSchemaStorage;
import org.elasticsoftware.akces.schemas.storage.SchemaStorage;
import org.elasticsoftware.akces.util.HostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;
import static org.elasticsoftware.akces.query.reflector.AkcesReflectorControllerState.*;

public class AkcesReflectorController extends Thread
        implements AutoCloseable, ConsumerRebalanceListener, ApplicationContextAware, AkcesRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AkcesReflectorController.class);

    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory;
    private final ConsumerFactory<String, SchemaRecord> schemaRecordConsumerFactory;
    private final ObjectMapper objectMapper;
    private final GDPRContextRepositoryFactory gdprContextRepositoryFactory;
    private final ReflectorRuntime reflectorRuntime;
    private final Map<Integer, ReflectorPartition> reflectorPartitions = new HashMap<>();
    private final ExecutorService executorService;
    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private final Map<String, AggregateServiceRecord> aggregateServices = new ConcurrentHashMap<>();
    private final List<TopicPartition> partitionsToAssign = new ArrayList<>();
    private final List<TopicPartition> partitionsToRevoke = new ArrayList<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private Integer partitions = null;
    private Consumer<String, AkcesControlRecord> controlConsumer;
    private volatile AkcesReflectorControllerState processState = INITIALIZING;
    private ApplicationContext applicationContext;
    private SchemaStorage schemaStorage;
    private SchemaRegistry schemaRegistry;

    public AkcesReflectorController(ConsumerFactory<String, ProtocolRecord> consumerFactory,
                                     ProducerFactory<String, ProtocolRecord> producerFactory,
                                     ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory,
                                     ConsumerFactory<String, SchemaRecord> schemaRecordConsumerFactory,
                                     ObjectMapper objectMapper,
                                     GDPRContextRepositoryFactory gdprContextRepositoryFactory,
                                     ReflectorRuntime reflectorRuntime) {
        super(reflectorRuntime.getName() + "-AkcesReflectorController");
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
        this.controlRecordConsumerFactory = controlConsumerFactory;
        this.schemaRecordConsumerFactory = schemaRecordConsumerFactory;
        this.objectMapper = objectMapper;
        this.gdprContextRepositoryFactory = gdprContextRepositoryFactory;
        this.reflectorRuntime = reflectorRuntime;
        this.executorService = Executors.newCachedThreadPool(
                new CustomizableThreadFactory(reflectorRuntime.getName() + "ReflectorPartitionThread-"));
    }

    @Override
    public void run() {
        try {
            controlConsumer = controlRecordConsumerFactory.createConsumer(
                    reflectorRuntime.getName() + "Reflector-Akces-Control",
                    reflectorRuntime.getName() + "-" + HostUtils.getHostName() + "Reflector-Akces-Control",
                    null);
            controlConsumer.subscribe(List.of("Akces-Control"), this);
            // determine the number of partitions once metadata is available
            this.partitions = controlConsumer.partitionsFor("Akces-Control").size();
            this.schemaStorage = new KafkaTopicSchemaStorage(
                    schemaRecordConsumerFactory.createConsumer(
                            HostUtils.getHostName() + "-AkcesReflectorController-Schemas",
                            HostUtils.getHostName() + "-AkcesReflectorController-Schemas",
                            null));
            schemaStorage.initialize();
            this.schemaRegistry = new KafkaSchemaRegistry(schemaStorage);
            // validate domain event schemas
            reflectorRuntime.validateDomainEventSchemas(schemaRegistry);
            while (processState != SHUTTING_DOWN) {
                process();
            }
            // close all reflector partitions
            logger.info("Closing {} ReflectorPartitions", reflectorPartitions.size());
            reflectorPartitions.values().forEach(reflectorPartition -> {
                if (reflectorPartition != null) {
                    try {
                        reflectorPartition.close();
                    } catch (Exception e) {
                        logger.error("Error closing ReflectorPartition " + reflectorPartition.getId(), e);
                    }
                }
            });
            try {
                controlConsumer.close(Duration.ofSeconds(5));
            } catch (InterruptException e) {
                Thread.currentThread().interrupt();
            } catch (KafkaException e) {
                logger.error("Error closing controlConsumer", e);
            }
            // raise an error for the liveness check
            applicationContext.publishEvent(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));
            // signal done
            shutdownLatch.countDown();
        } catch (Exception e) {
            logger.error("Error in AkcesReflectorController", e);
            processState = ERROR;
        }
    }

    private void process() {
        if (processState == RUNNING) {
            try {
                processControlRecords();
            } catch (WakeupException e) {
                // ignore
            } catch (InterruptException e) {
                Thread.currentThread().interrupt();
            } catch (KafkaException e) {
                logger.error("Unrecoverable exception in AkcesReflectorController", e);
                processState = SHUTTING_DOWN;
            }
        } else if (processState == INITIALIZING) {
            processControlRecords();
            // we don't switch to the running state here; we wait for the INITIAL_REBALANCING state
            // which should be triggered by onPartitionsRevoked/onPartitionsAssigned
        } else if (processState == INITIAL_REBALANCING) {
            // load all the service data from the control topic before transitioning to REBALANCING
            if (!partitionsToAssign.isEmpty()) {
                try {
                    controlConsumer.seekToBeginning(partitionsToAssign);
                    Map<TopicPartition, Long> initializedEndOffsets = controlConsumer.endOffsets(partitionsToAssign);
                    ConsumerRecords<String, AkcesControlRecord> consumerRecords =
                            controlConsumer.poll(Duration.ofMillis(100));
                    while (!initializedEndOffsets.isEmpty()) {
                        consumerRecords.forEach(record -> {
                            AkcesControlRecord controlRecord = record.value();
                            if (controlRecord instanceof AggregateServiceRecord aggregateServiceRecord) {
                                if (aggregateServices.putIfAbsent(record.key(), aggregateServiceRecord) == null) {
                                    logger.info("Discovered service: {}", aggregateServiceRecord.aggregateName());
                                }
                            } else {
                                logger.info("Received unknown AkcesControlRecord type: {}",
                                        controlRecord.getClass().getSimpleName());
                            }
                        });
                        if (consumerRecords.isEmpty()) {
                            initializedEndOffsets.entrySet().removeIf(
                                    entry -> entry.getValue() <= controlConsumer.position(entry.getKey()));
                        }
                        consumerRecords = controlConsumer.poll(Duration.ofMillis(100));
                    }
                } catch (WakeupException e) {
                    // ignore
                } catch (InterruptException e) {
                    Thread.currentThread().interrupt();
                } catch (KafkaException e) {
                    logger.error("Unrecoverable exception in AkcesReflectorController", e);
                    processState = SHUTTING_DOWN;
                }
            }
            // now we can move to REBALANCING
            processState = REBALANCING;
        } else if (processState == REBALANCING) {
            // first revoke
            for (TopicPartition topicPartition : partitionsToRevoke) {
                ReflectorPartition reflectorPartition = reflectorPartitions.remove(topicPartition.partition());
                if (reflectorPartition != null) {
                    logger.info("Stopping ReflectorPartition {}", reflectorPartition.getId());
                    try {
                        reflectorPartition.close();
                    } catch (Exception e) {
                        logger.error("Error closing ReflectorPartition", e);
                    }
                }
            }
            partitionsToRevoke.clear();
            // then assign
            for (TopicPartition topicPartition : partitionsToAssign) {
                ReflectorPartition reflectorPartition = new ReflectorPartition(
                        consumerFactory,
                        producerFactory,
                        reflectorRuntime,
                        gdprContextRepositoryFactory,
                        objectMapper,
                        topicPartition.partition(),
                        new TopicPartition("Akces-GDPRKeys", topicPartition.partition()),
                        reflectorRuntime.getDomainEventTypes(),
                        this);
                reflectorPartitions.put(reflectorPartition.getId(), reflectorPartition);
                logger.info("Starting ReflectorPartition {}", reflectorPartition.getId());
                executorService.submit(reflectorPartition);
            }
            partitionsToAssign.clear();
            // move back to running
            processState = RUNNING;
        }
    }

    private void processControlRecords() {
        ConsumerRecords<String, AkcesControlRecord> consumerRecords =
                controlConsumer.poll(Duration.ofMillis(100));
        if (!consumerRecords.isEmpty()) {
            consumerRecords.forEach(record -> {
                AkcesControlRecord controlRecord = record.value();
                if (controlRecord instanceof AggregateServiceRecord aggregateServiceRecord) {
                    if (!aggregateServices.containsKey(record.key())) {
                        logger.info("Discovered service: {}", aggregateServiceRecord.aggregateName());
                    }
                    // always overwrite with the latest version
                    aggregateServices.put(record.key(), aggregateServiceRecord);
                } else {
                    logger.info("Received unknown AkcesControlRecord type: {}",
                            controlRecord.getClass().getSimpleName());
                }
            });
        }
    }

    @Override
    public void close() {
        logger.info("Shutting down AkcesReflectorController");
        this.processState = SHUTTING_DOWN;
        try {
            if (shutdownLatch.await(10, TimeUnit.SECONDS)) {
                logger.info("AkcesReflectorController has been shutdown");
            } else {
                logger.warn("AkcesReflectorController did not shutdown within 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> topicPartitions) {
        if (!topicPartitions.isEmpty()) {
            partitionsToRevoke.addAll(topicPartitions);
            if (processState == RUNNING) {
                logger.info("Switching from RUNNING to REBALANCING, revoking partitions: {}",
                        topicPartitions.stream().map(TopicPartition::partition).toList());
                processState = REBALANCING;
            } else if (processState == INITIALIZING) {
                logger.info("Switching from INITIALIZING to INITIAL_REBALANCING, revoking partitions: {}",
                        topicPartitions.stream().map(TopicPartition::partition).toList());
                processState = INITIAL_REBALANCING;
            }
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> topicPartitions) {
        if (!topicPartitions.isEmpty()) {
            partitionsToAssign.addAll(topicPartitions);
            if (processState == RUNNING) {
                logger.info("Switching from RUNNING to REBALANCING, assigning partitions: {}",
                        topicPartitions.stream().map(TopicPartition::partition).toList());
                processState = REBALANCING;
            } else if (processState == INITIALIZING) {
                logger.info("Switching from INITIALIZING to INITIAL_REBALANCING, assigning partitions: {}",
                        topicPartitions.stream().map(TopicPartition::partition).toList());
                processState = INITIAL_REBALANCING;
            }
        }
    }

    private boolean supportsCommand(List<AggregateServiceCommandType> supportedCommands, CommandInfo commandInfo) {
        for (AggregateServiceCommandType supportedCommand : supportedCommands) {
            if (supportedCommand.typeName().equals(commandInfo.type()) &&
                    supportedCommand.version() == commandInfo.version()) {
                return true;
            }
        }
        return false;
    }

    private boolean supportsCommand(List<AggregateServiceCommandType> supportedCommands, CommandType<?> commandType) {
        for (AggregateServiceCommandType supportedCommand : supportedCommands) {
            if (supportedCommand.typeName().equals(commandType.typeName()) &&
                    supportedCommand.version() == commandType.version()) {
                return true;
            }
        }
        return false;
    }

    private boolean producesDomainEvent(List<AggregateServiceDomainEventType> producedEvents,
                                        DomainEventType<?> externalDomainEventType) {
        for (AggregateServiceDomainEventType producedEvent : producedEvents) {
            if (producedEvent.typeName().equals(externalDomainEventType.typeName()) &&
                    producedEvent.version() == externalDomainEventType.version()) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Nonnull
    public CommandType<?> resolveType(@Nonnull Class<? extends Command> commandClass) {
        CommandInfo commandInfo = commandClass.getAnnotation(CommandInfo.class);
        if (commandInfo == null) {
            throw new IllegalStateException(
                    "Command class " + commandClass.getName() + " is not annotated with @CommandInfo");
        }
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(record -> supportsCommand(record.supportedCommands(), commandInfo))
                .toList();
        if (services.size() == 1) {
            return new CommandType<>(
                    commandInfo.type(),
                    commandInfo.version(),
                    commandClass,
                    false,
                    true,
                    hasPIIDataAnnotation(commandClass));
        } else {
            throw new IllegalStateException(
                    "Cannot determine where to send command " + commandClass.getName());
        }
    }

    @Override
    @Nonnull
    public String resolveTopic(@Nonnull Class<? extends Command> commandClass) {
        return resolveTopic(resolveType(commandClass));
    }

    @Override
    @Nonnull
    public String resolveTopic(@Nonnull CommandType<?> commandType) {
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(record -> supportsCommand(record.supportedCommands(), commandType))
                .toList();
        if (services.size() == 1) {
            return services.getFirst().commandTopic();
        } else {
            throw new IllegalStateException(
                    "Cannot determine where to send command " + commandType.typeName() + " v" + commandType.version());
        }
    }

    @Override
    @Nonnull
    public String resolveTopic(@Nonnull DomainEventType<?> externalDomainEventType) {
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(record -> producesDomainEvent(record.producedEvents(), externalDomainEventType))
                .toList();
        if (services.size() == 1) {
            return services.getFirst().domainEventTopic();
        } else {
            throw new IllegalStateException(
                    "Cannot determine which service produces DomainEvent "
                            + externalDomainEventType.typeName() + " v" + externalDomainEventType.version());
        }
    }

    @Override
    @Nonnull
    public Integer resolvePartition(@Nonnull String aggregateId) {
        return Math.abs(hashFunction.hashString(aggregateId, UTF_8).asInt()) % partitions;
    }

    public boolean isRunning() {
        return processState == RUNNING
                && reflectorPartitions.values().stream().allMatch(ReflectorPartition::isProcessing);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
