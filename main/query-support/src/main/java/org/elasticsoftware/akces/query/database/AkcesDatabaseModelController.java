/*
 * Copyright 2022 - 2025 The Original Authors
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

package org.elasticsoftware.akces.query.database;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.elasticsoftware.akces.commands.Command;
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
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.elasticsoftware.akces.query.database.AkcesDatabaseModelControllerState.*;

public class AkcesDatabaseModelController extends Thread implements AutoCloseable, ConsumerRebalanceListener, ApplicationContextAware, AkcesRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AkcesDatabaseModelController.class);
    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory;
    private final ConsumerFactory<String, SchemaRecord> schemaRecordConsumerFactory;
    private final DatabaseModelRuntime databaseModelRuntime;
    private final Map<Integer, DatabaseModelPartition> databaseModelPartitions = new HashMap<>();
    private final ExecutorService executorService;
    private final Map<String, AggregateServiceRecord> aggregateServices = new ConcurrentHashMap<>();
    private final GDPRContextRepositoryFactory gdprContextRepositoryFactory;
    private final List<TopicPartition> partitionsToAssign = new ArrayList<>();
    private final List<TopicPartition> partitionsToRevoke = new ArrayList<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private Consumer<String, AkcesControlRecord> controlConsumer;
    private volatile AkcesDatabaseModelControllerState processState = INITIALIZING;
    private ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private SchemaStorage schemaStorage;
    private SchemaRegistry schemaRegistry;

    public AkcesDatabaseModelController(ConsumerFactory<String, ProtocolRecord> consumerFactory,
                                        ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory,
                                        ConsumerFactory<String, SchemaRecord> schemaRecordConsumerFactory,
                                        ObjectMapper objectMapper,
                                        GDPRContextRepositoryFactory gdprContextRepositoryFactory,
                                        DatabaseModelRuntime databaseModelRuntime) {
        super(databaseModelRuntime.getName() + "-AkcesDatabaseModelController");
        this.consumerFactory = consumerFactory;
        this.controlRecordConsumerFactory = controlConsumerFactory;
        this.schemaRecordConsumerFactory = schemaRecordConsumerFactory;
        this.objectMapper = objectMapper;
        this.gdprContextRepositoryFactory = gdprContextRepositoryFactory;
        this.databaseModelRuntime = databaseModelRuntime;
        this.executorService = Executors.newCachedThreadPool(new CustomizableThreadFactory(databaseModelRuntime.getName() + "DatabaseModelPartitionThread-"));
    }

    @Override
    public void run() {
        // make sure all our events and commands are registered and validated
        try {
            // and start consuming
            controlConsumer =
                    controlRecordConsumerFactory.createConsumer(
                            databaseModelRuntime.getName() + "DatabaseModel-Akces-Control",
                            databaseModelRuntime.getName() + "-" + HostUtils.getHostName() + "DatabaseModel-Akces-Control",
                            null);
            controlConsumer.subscribe(List.of("Akces-Control"), this);
            this.schemaStorage = new KafkaTopicSchemaStorage(
                    schemaRecordConsumerFactory.createConsumer(
                            HostUtils.getHostName() + "-AkcesDatabaseModelController-Schemas",
                            HostUtils.getHostName() + "-AkcesDatabaseModelController-Schemas",
                            null
                    )
            );
            // load the schemas
            schemaStorage.initialize();
            this.schemaRegistry = new KafkaSchemaRegistry(schemaStorage, objectMapper);
            // make sure the runtime has valid events
            databaseModelRuntime.validateDomainEventSchemas(schemaRegistry);
            while (processState != SHUTTING_DOWN) {
                process();
            }
            // close all aggregate partitions
            logger.info("Closing {} DatabaseModelPartitions", databaseModelPartitions.size());
            databaseModelPartitions.values().forEach(databaseModelPartition -> {
                if (databaseModelPartition != null) {
                    try {
                        databaseModelPartition.close();
                    } catch (Exception e) {
                        logger.error("Error closing DatabaseModelPartition " + databaseModelPartition.getId(), e);
                    }
                }
            });
            try {
                controlConsumer.close(Duration.ofSeconds(5));
            } catch (InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                logger.error("Error closing controlConsumer", e);
            }
            // raise an error for the liveness check
            applicationContext.publishEvent(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));
            // signal done
            shutdownLatch.countDown();
        } catch (Exception e) {
            logger.error("Error in AkcesDatabaseModelController", e);
            processState = ERROR;
        }
    }

    private void process() {
        if (processState == RUNNING) {
            try {
                processControlRecords();
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesDatabaseModelController", e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            }
        } else if (processState == INITIALIZING) {
            processControlRecords();
            // we don't switch to the running state because we wait for the INITIAL_REBALANCING state
            // which should be triggered by the processControlRecords() method
        } else if (processState == INITIAL_REBALANCING) {
            // we need to load all the service data and then move to REBALANCING
            if (!partitionsToAssign.isEmpty()) {
                try {
                    // seek to beginning to load all
                    controlConsumer.seekToBeginning(partitionsToAssign);
                    // find the end offsets so we know when to stop
                    Map<TopicPartition, Long> initializedEndOffsets = controlConsumer.endOffsets(partitionsToAssign);
                    // the data on the AkcesControl topics are broadcasted to all partitions
                    // so we only need to read one partition actually
                    // for simplicity we just read them all for now
                    ConsumerRecords<String, AkcesControlRecord> consumerRecords = controlConsumer.poll(Duration.ofMillis(100));
                    while (!initializedEndOffsets.isEmpty()) {
                        consumerRecords.forEach(record -> {
                            AkcesControlRecord controlRecord = record.value();
                            if (controlRecord instanceof AggregateServiceRecord aggregateServiceRecord) {
                                // only log it once
                                if (aggregateServices.putIfAbsent(record.key(), aggregateServiceRecord) == null) {
                                    logger.info("Discovered service: {}", aggregateServiceRecord.aggregateName());
                                }
                            } else {
                                logger.info("Received unknown AkcesControlRecord type: {}", controlRecord.getClass().getSimpleName());
                            }
                        });
                        // test for stop condition
                        if (consumerRecords.isEmpty()) {
                            initializedEndOffsets.entrySet().removeIf(entry -> entry.getValue() <= controlConsumer.position(entry.getKey()));
                        }
                        // poll again
                        consumerRecords = controlConsumer.poll(Duration.ofMillis(100));
                    }
                } catch (WakeupException | InterruptException e) {
                    // ignore
                } catch (KafkaException e) {
                    // this is an unrecoverable exception
                    logger.error("Unrecoverable exception in AkcesDatabaseModelController", e);
                    // drop out of the control loop, this will shut down all resources
                    processState = SHUTTING_DOWN;
                }
            }
            // now we can move to REBALANCING
            processState = REBALANCING;
        } else if (processState == REBALANCING) {
            // first revoke
            for (TopicPartition topicPartition : partitionsToRevoke) {
                DatabaseModelPartition databaseModelPartition = databaseModelPartitions.remove(topicPartition.partition());
                if (databaseModelPartition != null) {
                    logger.info("Stopping DatabaseModelPartition {}", databaseModelPartition.getId());
                    try {
                        databaseModelPartition.close();
                    } catch (Exception e) {
                        logger.error("Error closing DatabaseModelPartition", e);
                    }
                }
            }
            partitionsToRevoke.clear();
            // then assign
            for (TopicPartition topicPartition : partitionsToAssign) {
                DatabaseModelPartition aggregatePartition = new DatabaseModelPartition(
                        consumerFactory,
                        databaseModelRuntime,
                        gdprContextRepositoryFactory,
                        topicPartition.partition(),
                        new TopicPartition("Akces-GDPRKeys", topicPartition.partition()),
                        databaseModelRuntime.getDomainEventTypes(),
                        this);
                databaseModelPartitions.put(aggregatePartition.getId(), aggregatePartition);
                logger.info("Starting DatabaseModelPartition {}", aggregatePartition.getId());
                executorService.submit(aggregatePartition);
            }
            partitionsToAssign.clear();
            // move back to running
            processState = RUNNING;
        }
    }

    private void processControlRecords() {
        // the data on the AkcesControl topics are broadcasted to all partitions
        // so we only need to read one partition actually
        // for simplicity we just read them all for now
        ConsumerRecords<String, AkcesControlRecord> consumerRecords = controlConsumer.poll(Duration.ofMillis(100));
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
                    logger.info("Received unknown AkcesControlRecord type: {}", controlRecord.getClass().getSimpleName());
                }
            });
        }
    }

    @Override
    public void close() {
        logger.info("Shutting down AkcesDatabaseModelController");
        this.processState = SHUTTING_DOWN;
        // wait maximum of 10 seconds for the shutdown to complete
        try {
            if (shutdownLatch.await(10, TimeUnit.SECONDS)) {
                logger.info("AkcesDatabaseModelController has been shutdown");
            } else {
                logger.warn("AkcesDatabaseModelController did not shutdown within 10 seconds");
            }
        } catch (InterruptedException e) {
            // ignore
        }
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> topicPartitions) {
        // don't do anything on empty partitions
        if (!topicPartitions.isEmpty()) {
            // stop all local AggregatePartition instances
            partitionsToRevoke.addAll(topicPartitions);
            // if we are already running, we can immediately rebalance
            if (processState == RUNNING) {
                logger.info("Switching from RUNNING to REBALANCING, revoking partitions: {}",
                        topicPartitions.stream().map(TopicPartition::partition).toList());
                processState = REBALANCING;
            } else if (processState == INITIALIZING) { // otherwise we first have to load the services data
                logger.info("Switching from INITIALIZING to INITIAL_REBALANCING, revoking partitions: {}",
                        topicPartitions.stream().map(TopicPartition::partition).toList());
                processState = INITIAL_REBALANCING;
            }
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> topicPartitions) {
        if (!topicPartitions.isEmpty()) {
            // start all local AggregatePartition instances
            partitionsToAssign.addAll(topicPartitions);
            // if we are already running, we can immediately rebalance
            if (processState == RUNNING) {
                logger.info("Switching from RUNNING to REBALANCING, assigning partitions : {}",
                        topicPartitions.stream().map(TopicPartition::partition).toList());
                processState = REBALANCING;
            } else if (processState == INITIALIZING) { // otherwise we first have to load the services data
                logger.info("Switching from INITIALIZING to INITIAL_REBALANCING, assigning partitions : {}",
                        topicPartitions.stream().map(TopicPartition::partition).toList());
                processState = INITIAL_REBALANCING;
            }
        }
    }


    private boolean producesDomainEvent(List<AggregateServiceDomainEventType> producedEvents, DomainEventType<?> externalDomainEventType) {
        for (AggregateServiceDomainEventType producedEvent : producedEvents) {
            if (producedEvent.typeName().equals(externalDomainEventType.typeName()) &&
                    producedEvent.version() == externalDomainEventType.version()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String resolveTopic(@Nonnull DomainEventType<?> externalDomainEventType) {
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(commandServiceRecord -> producesDomainEvent(commandServiceRecord.producedEvents(), externalDomainEventType))
                .toList();
        if (services.size() == 1) {
            return services.getFirst().domainEventTopic();
        } else {
            throw new IllegalStateException("Cannot determine which service produces DomainEvent " + externalDomainEventType.typeName() + " v" + externalDomainEventType.version());
        }
    }

    @Override
    public CommandType<?> resolveType(@Nonnull Class<? extends Command> commandClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String resolveTopic(@Nonnull Class<? extends Command> commandClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String resolveTopic(@Nonnull CommandType<?> commandType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Integer resolvePartition(@Nonnull String aggregateId) {
        throw new UnsupportedOperationException();
    }

    public boolean isRunning() {
        return processState == RUNNING && databaseModelPartitions.values().stream().allMatch(DatabaseModelPartition::isProcessing);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
