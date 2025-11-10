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

package org.elasticsoftware.akces;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import jakarta.annotation.Nonnull;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.elasticsoftware.akces.aggregate.SchemaType;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.*;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.kafka.AggregatePartition;
import org.elasticsoftware.akces.kafka.PartitionUtils;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.schemas.IncompatibleSchemaException;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
import org.elasticsoftware.akces.util.HostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdminOperations;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.elasticsoftware.akces.AkcesControllerState.*;
import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;
import static org.elasticsoftware.akces.kafka.PartitionUtils.*;
import static org.elasticsoftware.akces.util.KafkaUtils.createCompactedTopic;
import static org.elasticsoftware.akces.util.KafkaUtils.getIndexTopicName;

public class AkcesAggregateController extends Thread implements AutoCloseable, ConsumerRebalanceListener, AkcesRegistry, EnvironmentAware, ApplicationContextAware {
    private static final Logger logger = LoggerFactory.getLogger(AkcesAggregateController.class);
    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final ProducerFactory<String, AkcesControlRecord> controlProducerFactory;
    private final ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory;
    private final ProducerFactory<String, org.elasticsoftware.akces.protocol.SchemaRecord> schemaProducerFactory;
    private final ConsumerFactory<String, org.elasticsoftware.akces.protocol.SchemaRecord> schemaConsumerFactory;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final AggregateRuntime aggregateRuntime;
    private final KafkaAdminOperations kafkaAdmin;
    private final Map<Integer, AggregatePartition> aggregatePartitions = new HashMap<>();
    private final ExecutorService executorService;
    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private final Map<String, AggregateServiceRecord> aggregateServices = new ConcurrentHashMap<>();
    private final AggregateStateRepositoryFactory aggregateStateRepositoryFactory;
    private final GDPRContextRepositoryFactory gdprContextRepositoryFactory;
    private final List<TopicPartition> partitionsToAssign = new ArrayList<>();
    private final List<TopicPartition> partitionsToRevoke = new ArrayList<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private Integer partitions = null;
    private Short replicationFactor = null;
    private Consumer<String, AkcesControlRecord> controlConsumer;
    private org.elasticsoftware.akces.schemas.storage.KafkaTopicSchemaStorage schemaStorage;
    private org.elasticsoftware.akces.schemas.KafkaSchemaRegistry schemaRegistry;
    private volatile AkcesControllerState processState = INITIALIZING;
    private boolean forceRegisterOnIncompatible = false;
    private ApplicationContext applicationContext;

    public AkcesAggregateController(ConsumerFactory<String, ProtocolRecord> consumerFactory,
                                    ProducerFactory<String, ProtocolRecord> producerFactory,
                                    ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory,
                                    ProducerFactory<String, AkcesControlRecord> controlProducerFactory,
                                    ProducerFactory<String, org.elasticsoftware.akces.protocol.SchemaRecord> schemaProducerFactory,
                                    ConsumerFactory<String, org.elasticsoftware.akces.protocol.SchemaRecord> schemaConsumerFactory,
                                    com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                    AggregateStateRepositoryFactory aggregateStateRepositoryFactory,
                                    GDPRContextRepositoryFactory gdprContextRepositoryFactory,
                                    AggregateRuntime aggregateRuntime,
                                    KafkaAdminOperations kafkaAdmin) {
        super(aggregateRuntime.getName() + "-AkcesController");
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
        this.controlProducerFactory = controlProducerFactory;
        this.controlRecordConsumerFactory = controlConsumerFactory;
        this.schemaProducerFactory = schemaProducerFactory;
        this.schemaConsumerFactory = schemaConsumerFactory;
        this.objectMapper = objectMapper;
        this.aggregateStateRepositoryFactory = aggregateStateRepositoryFactory;
        this.gdprContextRepositoryFactory = gdprContextRepositoryFactory;
        this.aggregateRuntime = aggregateRuntime;
        this.kafkaAdmin = kafkaAdmin;
        this.executorService = Executors.newCachedThreadPool(new CustomizableThreadFactory(aggregateRuntime.getName() + "AggregatePartitionThread-"));
    }

    @Override
    public void run() {
        // make sure all our events and commands are registered and validated
        try {
            // create schema storage and registry
            schemaStorage = new org.elasticsoftware.akces.schemas.storage.KafkaTopicSchemaStorageImpl(
                    schemaProducerFactory.createProducer(aggregateRuntime.getName() + "-SchemaProducer"),
                    schemaConsumerFactory.createConsumer(
                            aggregateRuntime.getName() + "-Akces-SchemaConsumer",
                            aggregateRuntime.getName() + "-" + HostUtils.getHostName() + "-Akces-SchemaConsumer")
            );
            schemaRegistry = new org.elasticsoftware.akces.schemas.KafkaSchemaRegistry(schemaStorage, objectMapper);
            
            // and start consuming
            controlConsumer =
                    controlRecordConsumerFactory.createConsumer(
                            aggregateRuntime.getName() + "-Akces-Control",
                            aggregateRuntime.getName() + "-" + HostUtils.getHostName() + "-Akces-Control",
                            null);
            controlConsumer.subscribe(List.of("Akces-Control"), this);
            while (processState != SHUTTING_DOWN) {
                process();
            }
            // TODO: we have 10 seconds to do this
            // close all aggregate partitions
            logger.info("Closing {} AggregatePartitions", aggregatePartitions.size());
            aggregatePartitions.values().forEach(aggregatePartition -> {
                if (aggregatePartition != null) {
                    try {
                        aggregatePartition.close();
                    } catch (Exception e) {
                        logger.error("Error closing AggregatePartition " + aggregatePartition.getId(), e);
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
            try {
                if (schemaStorage != null) {
                    schemaStorage.close();
                }
            } catch (Exception e) {
                logger.error("Error closing schemaStorage", e);
            }
            // raise an error for the liveness check
            applicationContext.publishEvent(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));
            // signal done
            shutdownLatch.countDown();
        } catch (Exception e) {
            logger.error("Error in AkcesController", e);
            processState = ERROR;
        }
    }

    private void process() {
        if (processState == RUNNING) {
            try {
                // process schema storage updates
                schemaStorage.process();
                processControlRecords();
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesController", e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            }
        } else if (processState == INITIALIZING) {
            // ensure we loaded all the available schemas first
            schemaStorage.initialize();
            // find out about the cluster
            TopicDescription controlTopicDescription = kafkaAdmin.describeTopics("Akces-Control").get("Akces-Control");
            partitions = controlTopicDescription.partitions().size();
            replicationFactor = (short) controlTopicDescription.partitions().getFirst().replicas().size();
            // first register and validate all local (= owned) domain events
            for (DomainEventType<?> domainEventType : aggregateRuntime.getProducedDomainEventTypes()) {
                try {
                    aggregateRuntime.registerAndValidate(domainEventType, schemaRegistry);
                } catch (IncompatibleSchemaException e) {
                    // our schema is not compatible with the existing schema
                    // if no DomainEvents of the type/version have been produced yet, we can just update the schema
                    if (forceRegisterOnIncompatible) {
                        if (protocolRecordTypeNotYetProduced(domainEventType, PartitionUtils::toDomainEventTopicPartition)) {
                            aggregateRuntime.registerAndValidate(domainEventType, schemaRegistry, true);
                        } else {
                            logger.warn("Cannot update schema for DomainEvent {} v{} because it has already been produced",
                                    domainEventType.typeName(), domainEventType.version());
                            throw e;
                        }
                    } else {
                        throw e;
                    }
                }
            }
            // register and validate all local commands
            for (CommandType<?> commandType : aggregateRuntime.getLocalCommandTypes()) {
                try {
                    aggregateRuntime.registerAndValidate(commandType, schemaRegistry);
                } catch (IncompatibleSchemaException e) {
                    // our schema is not compatible with the existing schema
                    // if no Commands of the type/version have been produced yet, we can just update the schema
                    if (forceRegisterOnIncompatible) {
                        if (protocolRecordTypeNotYetProduced(commandType, PartitionUtils::toCommandTopicPartition)) {
                            aggregateRuntime.registerAndValidate(commandType, schemaRegistry, true);
                        } else {
                            logger.warn("Cannot update schema for Command {} v{} because it has already been produced",
                                    commandType.typeName(), commandType.version());
                            throw e;
                        }
                    } else {
                        throw e;
                    }
                }
            }
            // publish our own record
            publishControlRecord(partitions);
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
                    logger.error("Unrecoverable exception in AkcesController", e);
                    // drop out of the control loop, this will shut down all resources
                    processState = SHUTTING_DOWN;
                }
                // TODO: maybe this needs it's own process state
                // register external domain event types
                for (DomainEventType<?> domainEventType : aggregateRuntime.getExternalDomainEventTypes()) {
                    try {
                        aggregateRuntime.registerAndValidate(domainEventType, schemaRegistry);
                    } catch (SchemaException e) {
                        logger.error("Error registering external domain event type: {}:{}", domainEventType.typeName(), domainEventType.version(), e);
                        processState = SHUTTING_DOWN;
                    }
                }
                // register external command types
                for (CommandType<?> commandType : aggregateRuntime.getExternalCommandTypes()) {
                    try {
                        aggregateRuntime.registerAndValidate(commandType, schemaRegistry);
                    } catch (SchemaException e) {
                        logger.error("Error registering external command type: {}:{}", commandType.typeName(), commandType.version(), e);
                        processState = SHUTTING_DOWN;
                    }
                }
            }
            // now we can move to REBALANCING
            processState = REBALANCING;
        } else if (processState == REBALANCING) {
            // first revoke
            for (TopicPartition topicPartition : partitionsToRevoke) {
                AggregatePartition aggregatePartition = aggregatePartitions.remove(topicPartition.partition());
                if (aggregatePartition != null) {
                    logger.info("Stopping AggregatePartition {}", aggregatePartition.getId());
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
                        schemaRegistry,
                        aggregateStateRepositoryFactory,
                        gdprContextRepositoryFactory,
                        topicPartition.partition(),
                        toCommandTopicPartition(aggregateRuntime, topicPartition.partition()),
                        toDomainEventTopicPartition(aggregateRuntime, topicPartition.partition()),
                        toAggregateStateTopicPartition(aggregateRuntime, topicPartition.partition()),
                        toGDPRKeysTopicPartition(aggregateRuntime, topicPartition.partition()),
                        aggregateRuntime.getExternalDomainEventTypes(),
                        this,
                        this::createIndexTopic);
                aggregatePartitions.put(aggregatePartition.getId(), aggregatePartition);
                logger.info("Starting AggregatePartition {}", aggregatePartition.getId());
                executorService.submit(aggregatePartition);
            }
            partitionsToAssign.clear();
            // move back to running
            processState = RUNNING;
        }
    }

    private boolean protocolRecordTypeNotYetProduced(SchemaType<?> schemaType,
                                                     BiFunction<AggregateRuntime, Integer, TopicPartition> createTopicPartition) {
        try (Consumer<String, ProtocolRecord> consumer = consumerFactory.createConsumer(
                aggregateRuntime.getName() + "-Akces-Control-TypeCheck",
                aggregateRuntime.getName() + "-" + HostUtils.getHostName() + "-Akces-Control-TypeCheck",
                null)) {
            List<TopicPartition> partitionsList = IntStream.range(0, partitions)
                    .mapToObj(i -> createTopicPartition.apply(aggregateRuntime, i))
                    .toList();
            consumer.assign(partitionsList);
            consumer.seekToBeginning(partitionsList);
            // get end offsets
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitionsList);
            while (!endOffsets.isEmpty()) {
                try {
                    for (ConsumerRecord<String, ProtocolRecord> record : consumer.poll(Duration.ofMillis(10))) {
                        if (record.value().name().equals(schemaType.typeName()) &&
                                record.value().version() == schemaType.version()) {
                            // the event was produced, we cannot update the schema
                            return false;
                        }
                    }
                    // check for the stopcondition
                    endOffsets.entrySet().removeIf(entry -> entry.getValue() <= consumer.position(entry.getKey()));
                } catch (WakeupException | InterruptException e) {
                    // ignore
                } catch (KafkaException e) {
                    // this is an unrecoverable exception
                    logger.error(
                            "KafkaException while checking if ProtocolRecord {} v{} has already been produced",
                            schemaType.typeName(),
                            schemaType.version(),
                            e);
                    // due to the exception we cannot determine whether the event has been produced so we need to return false
                    return false;
                }
            }
        } catch (KafkaException e) {
            // this is an unrecoverable exception
            logger.error(
                    "KafkaException while checking if ProtocolRecord {} v{} has already been produced",
                    schemaType.typeName(),
                    schemaType.version(),
                    e);
            // due to the exception we cannot determine whether the event has been produced so we need to return false
            return false;
        }
        // if we make it till here we didn't find any records
        return true;
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

    private Boolean createIndexTopic(String indexName, String indexKey) {
        try {
            kafkaAdmin.createOrModifyTopics(
                    createCompactedTopic(getIndexTopicName(indexName, indexKey), 1, replicationFactor));
            return true;
        } catch (Exception e) {
            logger.error("Error creating index topic: {}", indexName, e);
            return false;
        }
    }

    private void publishControlRecord(int partitions) {
        String transactionalId = aggregateRuntime.getName() + "-" + HostUtils.getHostName() + "-control";
        try (Producer<String, AkcesControlRecord> controlProducer = controlProducerFactory.createProducer(transactionalId)) {
            // publish the CommandServiceRecord
            AggregateServiceRecord aggregateServiceRecord = new AggregateServiceRecord(
                    aggregateRuntime.getName(),
                    aggregateRuntime.getName() + COMMANDS_SUFFIX,
                    aggregateRuntime.getName() + DOMAINEVENTS_SUFFIX,
                    aggregateRuntime.getLocalCommandTypes().stream()
                            .map(commandType ->
                                    new AggregateServiceCommandType(
                                            commandType.typeName(),
                                            commandType.version(),
                                            commandType.create(),
                                            "commands." + commandType.typeName())).toList(),
                    aggregateRuntime.getProducedDomainEventTypes().stream().map(domainEventType ->
                            new AggregateServiceDomainEventType(
                                    domainEventType.typeName(),
                                    domainEventType.version(),
                                    domainEventType.create(),
                                    domainEventType.external(),
                                    "domainevents." + domainEventType.typeName())).toList(),
                    aggregateRuntime.getExternalDomainEventTypes().stream().map(externalDomainEventType ->
                            new AggregateServiceDomainEventType(
                                    externalDomainEventType.typeName(),
                                    externalDomainEventType.version(),
                                    externalDomainEventType.create(),
                                    externalDomainEventType.external(),
                                    "domainevents." + externalDomainEventType.typeName())).toList());
            controlProducer.beginTransaction();
            for (int partition = 0; partition < partitions; partition++) {
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, aggregateRuntime.getName(), aggregateServiceRecord));
            }
            controlProducer.commitTransaction();
        } catch (Exception e) {
            logger.error("Error publishing CommandServiceRecord", e);
        }
    }

    @Override
    public void close() throws Exception {
        logger.info("Shutting down AkcesAggregateController");
        this.processState = SHUTTING_DOWN;
        // wait maximum of 10 seconds for the shutdown to complete
        try {
            if (shutdownLatch.await(10, TimeUnit.SECONDS)) {
                logger.info("AkcesAggregateController has been shutdown");
            } else {
                logger.warn("AkcesAggregateController did not shutdown within 10 seconds");
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

    @Override
    @Nonnull
    public CommandType<?> resolveType(@Nonnull Class<? extends Command> commandClass) {
        // TODO: if the command class is for an external service it won't be derived from the local Aggregate
        CommandInfo commandInfo = commandClass.getAnnotation(CommandInfo.class);
        if (commandInfo != null) {
            List<AggregateServiceRecord> services = aggregateServices.values().stream()
                    .filter(commandServiceRecord -> supportsCommand(commandServiceRecord.supportedCommands(), commandInfo))
                    .toList();
            if (services.size() == 1) {
                AggregateServiceRecord aggregateServiceRecord = services.getFirst();
                if (aggregateRuntime.getName().equals(aggregateServiceRecord.aggregateName())) {
                    // this is a local command (will be sent to self)
                    return aggregateRuntime.getLocalCommandType(commandInfo.type(), commandInfo.version());
                } else {
                    return new CommandType<>(commandInfo.type(),
                            commandInfo.version(),
                            commandClass,
                            false,
                            true,
                            hasPIIDataAnnotation(commandClass));
                }
            } else {
                // TODO: throw exception, we cannot determine where to send the command
                throw new IllegalStateException("Cannot determine where to send command " + commandClass.getName());
            }

        } else {
            throw new IllegalStateException("Command class " + commandClass.getName() + " is not annotated with @CommandInfo");
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
    @Nonnull
    public String resolveTopic(@Nonnull Class<? extends Command> commandClass) {
        return resolveTopic(resolveType(commandClass));
    }

    @Override
    @Nonnull
    public String resolveTopic(@Nonnull CommandType<?> commandType) {
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(commandServiceRecord -> supportsCommand(commandServiceRecord.supportedCommands(), commandType))
                .toList();
        if (services.size() == 1) {
            return services.getFirst().commandTopic();
        } else {
            throw new IllegalStateException("Cannot determine where to send command " + commandType.typeName() + " v" + commandType.version());
        }
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
    @Nonnull
    public Integer resolvePartition(@Nonnull String aggregateId) {
        return Math.abs(hashFunction.hashString(aggregateId, UTF_8).asInt()) % partitions;
    }

    public boolean isRunning() {
        return processState == RUNNING && aggregatePartitions.values().stream().allMatch(AggregatePartition::isProcessing);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.forceRegisterOnIncompatible = environment.getProperty("akces.aggregate.schemas.forceRegister", Boolean.class, false);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
