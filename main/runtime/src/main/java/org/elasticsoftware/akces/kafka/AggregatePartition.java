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

package org.elasticsoftware.akces.kafka;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.*;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.aggregate.IndexParams;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.control.AkcesRegistry;
import org.elasticsoftware.akces.gdpr.GDPRContextHolder;
import org.elasticsoftware.akces.gdpr.GDPRContextRepository;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.gdpr.GDPRKeyUtils;
import org.elasticsoftware.akces.protocol.*;
import org.elasticsoftware.akces.state.AggregateStateRepository;
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
import org.elasticsoftware.akces.util.HostUtils;
import org.elasticsoftware.akces.util.KafkaSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.elasticsoftware.akces.gdpr.GDPRContextHolder.getCurrentGDPRContext;
import static org.elasticsoftware.akces.kafka.AggregatePartitionState.*;
import static org.elasticsoftware.akces.util.KafkaUtils.getIndexTopicName;

public class AggregatePartition implements Runnable, AutoCloseable, CommandBus {
    private static final Logger logger = LoggerFactory.getLogger(AggregatePartition.class);
    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final AggregateRuntime runtime;
    private final AggregateStateRepository stateRepository;
    private final GDPRContextRepository gdprContextRepository;
    private final Integer id;
    private final TopicPartition commandPartition;
    private final TopicPartition domainEventPartition;
    private final TopicPartition statePartition;
    private final TopicPartition gdprKeyPartition;
    private final Set<TopicPartition> externalEventPartitions = new HashSet<>();
    private final Collection<DomainEventType<?>> externalDomainEventTypes;
    private final AkcesRegistry ackesRegistry;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final BiFunction<String, String, Boolean> indexTopicCreator;
    private Consumer<String, ProtocolRecord> consumer;
    private Producer<String, ProtocolRecord> producer;
    private volatile AggregatePartitionState processState;
    private Map<TopicPartition, Long> initializedEndOffsets = Collections.emptyMap();
    private volatile Thread aggregatePartitionThread = null;


    public AggregatePartition(ConsumerFactory<String, ProtocolRecord> consumerFactory,
                              ProducerFactory<String, ProtocolRecord> producerFactory,
                              AggregateRuntime runtime,
                              AggregateStateRepositoryFactory stateRepositoryFactory,
                              GDPRContextRepositoryFactory gdprContextRepositoryFactory,
                              Integer id,
                              TopicPartition commandPartition,
                              TopicPartition domainEventPartition,
                              TopicPartition statePartition,
                              TopicPartition gdprKeyPartition,
                              Collection<DomainEventType<?>> externalDomainEventTypes,
                              AkcesRegistry ackesRegistry,
                              BiFunction<String, String, Boolean> indexTopicCreator) {
        this.gdprKeyPartition = gdprKeyPartition;
        this.ackesRegistry = ackesRegistry;
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
        this.runtime = runtime;
        this.indexTopicCreator = indexTopicCreator;
        this.stateRepository = stateRepositoryFactory.create(runtime, id);
        this.id = id;
        this.commandPartition = commandPartition;
        this.domainEventPartition = domainEventPartition;
        this.statePartition = statePartition;
        this.externalDomainEventTypes = externalDomainEventTypes;
        this.processState = INITIALIZING;
        this.gdprContextRepository = gdprContextRepositoryFactory.create(runtime.getName(), id);
    }

    public Integer getId() {
        return id;
    }

    @Override
    public void run() {
        try {
            // store the thread so we can check if we are on the correct thread
            this.aggregatePartitionThread = Thread.currentThread();
            // register the CommandBus
            AggregatePartitionCommandBus.registerCommandBus(this);
            logger.info("Starting AggregatePartition {} of {}Aggregate", id, runtime.getName());
            this.consumer = consumerFactory.createConsumer(
                    runtime.getName() + "Aggregate-partition-" + id,
                    runtime.getName() + "Aggregate-partition-" + id + "-" + HostUtils.getHostName(),
                    null);
            this.producer = producerFactory.createProducer(runtime.getName() + "Aggregate-partition-" + id + "-" + HostUtils.getHostName());
            // resolve the external event partitions
            externalDomainEventTypes.forEach(domainEventType -> {
                String topic = ackesRegistry.resolveTopic(domainEventType);
                externalEventPartitions.add(new TopicPartition(topic, id));
            });
            // make a hard assignment, only assign the gdprKeyPartition if needed
            consumer.assign(Stream.concat(Stream.concat(
                                    Stream.of(commandPartition, domainEventPartition, statePartition), runtime.shouldHandlePIIData() ? Stream.of(gdprKeyPartition) : Stream.empty()),
                                    externalEventPartitions.stream())
                    .toList());
            logger.info("Assigned partitions {} for AggregatePartition {} of {}Aggregate", consumer.assignment(), id, runtime.getName());
            while (processState != SHUTTING_DOWN) {
                process();
            }
            logger.info("Shutting down AggregatePartition {} of {}Aggregate", id, runtime.getName());
        } catch (Throwable t) {
            logger.error("Unexpected error in AggregatePartition {} of {}Aggregate", id, runtime.getName(), t);
        } finally {
            try {
                consumer.close(Duration.ofSeconds(5));
                producer.close(Duration.ofSeconds(5));
            } catch (InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                logger.error("Error closing consumer/producer", e);
            }
            try {
                stateRepository.close();
            } catch (IOException e) {
                logger.error("Error closing state repository", e);
            }
            try {
                gdprContextRepository.close();
            } catch (IOException e) {
                logger.error("Error closing gdpr context repository", e);
            }
            AggregatePartitionCommandBus.registerCommandBus(null);
        }
        logger.info("Finished Shutting down AggregatePartition {} of {}Aggregate", id, runtime.getName());
        shutdownLatch.countDown();
    }

    @Override
    public void close() throws InterruptedException {
        processState = SHUTTING_DOWN;
        // wait maximum of 10 seconds for the shutdown to complete
        try {
            if (shutdownLatch.await(10, TimeUnit.SECONDS)) {
                logger.info("AggregatePartition={} has been shutdown", id);
            } else {
                logger.warn("AggregatePartition={} did not shutdown within 10 seconds", id);
            }
        } catch (InterruptedException e) {
            // ignore
        }
    }

    @Override
    public void send(Command command) {
        // this implementation is only meant to be called from the AggregatePartition thread
        if (Thread.currentThread() != aggregatePartitionThread) {
            throw new IllegalStateException("send() can only be called from the AggregatePartition thread");
        }
        // we need to resolve the command type
        CommandType<?> commandType = ackesRegistry.resolveType(command.getClass());
        // ensure we have the command registered and validated, this is an idempotent call
        try {
            runtime.registerAndValidate(commandType);
        } catch (Exception e) {
            logger.error("Problem registering command {}", commandType.typeName(), e);
            // TODO: throw a more specific exception
            // TODO: decide whether to terminate the AggregatePartition
            throw new RuntimeException(e);
        }

        if (commandType != null) {
            // now we need to find the topic
            String topic = ackesRegistry.resolveTopic(commandType);
            // and send the command to the topic: TODO propagate tenantId and correlationId
            CommandRecord commandRecord = new CommandRecord(
                    null,
                    commandType.typeName(),
                    commandType.version(),
                    runtime.serialize(command),
                    PayloadEncoding.JSON,
                    command.getAggregateId(),
                    null,
                    null); // don't send a response
            // we should not use the local partition id but that of the aggregate
            Integer partition = ackesRegistry.resolvePartition(command.getAggregateId());
            KafkaSender.send(producer, new ProducerRecord<>(topic, partition, commandRecord.id(), commandRecord));
        }
    }

    private void send(ProtocolRecord protocolRecord) {
        if (protocolRecord instanceof AggregateStateRecord asr) {
            logger.trace("Sending AggregateStateRecord with id {} to {}", asr.aggregateId(), statePartition);
            // send to topic
            Future<RecordMetadata> result = KafkaSender.send(producer, new ProducerRecord<>(statePartition.topic(), statePartition.partition(), asr.aggregateId(), asr));
            // prepare (cache) for commit
            stateRepository.prepare(asr, result);
        } else if (protocolRecord instanceof DomainEventRecord der) {
            logger.trace("Sending DomainEventRecord {}:{} with id {} to {}", der.name(), der.version(), der.id(), domainEventPartition);
            KafkaSender.send(producer, new ProducerRecord<>(domainEventPartition.topic(), domainEventPartition.partition(), der.id(), der));
        } else if (protocolRecord instanceof GDPRKeyRecord gkr) {
            logger.trace("Sending GDPRKeyRecord with id {} to {}", gkr.aggregateId(), gdprKeyPartition);
            Future<RecordMetadata> result = KafkaSender.send(producer, new ProducerRecord<>(gdprKeyPartition.topic(), gdprKeyPartition.partition(), gkr.aggregateId(), gkr));
            gdprContextRepository.prepare(gkr, result);
        } else if (protocolRecord instanceof CommandRecord cr) {
            // commands should be sent via the CommandBus since it needs to figure out the topic
            // producer.send(new ProducerRecord<>(commandPartition.topic(), commandPartition.partition(), cr.aggregateId(), cr));
            // this is a framework programmer error, it should never happen
            throw new IllegalArgumentException("""
                    send(ProtocolRecord) should not be used for CommandRecord type.
                    Use send(commandRecord,commandPartition) instead""");
        }
    }

    private void index(DomainEventRecord der, IndexParams params) {
        // send to the index topic
        String topicName = getIndexTopicName(params.indexName(), params.indexKey());
        if (params.createIndex()) {
            if (consumer.partitionsFor(topicName).isEmpty()) {
                if (indexTopicCreator.apply(params.indexName(), params.indexKey())) {
                    logger.info("Creating DomainEventIndex topic {}", topicName);
                }
            }
        }
        logger.trace("Indexing DomainEventRecord {}:{} with id {} to topic {}", der.name(), der.version(), der.id(), topicName + "-0");
        // index topics only have one partition
        KafkaSender.send(producer, new ProducerRecord<>(topicName, 0, der.id(), der));
    }

    private void setupGDPRContext(String tenantId, String aggregateId, boolean createIfMissing) {
        // avoid accidentally overwriting an existing gdpr key
        if (!gdprContextRepository.exists(aggregateId) && createIfMissing) {
            logger.trace("Generating GDPR key for aggregate {}", aggregateId);
            // generate a new key record
            GDPRKeyRecord gdprKeyRecord = new GDPRKeyRecord(
                    tenantId,
                    aggregateId,
                    GDPRKeyUtils.createKey().getEncoded());
            // send to kafka (and update gdprContextRepository)
            send(gdprKeyRecord);
        }
        // setup the context, this will either be a DefaultGDPRContext or a NoopGDPRContext
        GDPRContextHolder.setCurrentGDPRContext(gdprContextRepository.get(aggregateId));
    }

    private void tearDownGDPRContext() {
        GDPRContextHolder.resetCurrentGDPRContext();
    }

    private void handleCommand(CommandRecord commandRecord) {
        try {
            final List<DomainEventRecord> responseRecords = commandRecord.replyToTopicPartition() != null ? new ArrayList<>() : null;
            java.util.function.Consumer<ProtocolRecord> protocolRecordConsumer = (pr) -> {
                send(pr);
                if (responseRecords != null && pr instanceof DomainEventRecord der) {
                    responseRecords.add(der);
                }
            };
            if(runtime.requiresGDPRContext(commandRecord)) {
                setupGDPRContext(commandRecord.tenantId(), commandRecord.aggregateId(), runtime.shouldGenerateGDPRKey(commandRecord));
            }
            logger.trace("Handling CommandRecord with type {}", commandRecord.name());
            runtime.handleCommandRecord(commandRecord, protocolRecordConsumer, this::index, () -> stateRepository.get(commandRecord.aggregateId()));
            if (responseRecords != null) {
                CommandResponseRecord crr = new CommandResponseRecord(
                        commandRecord.tenantId(),
                        commandRecord.aggregateId(),
                        commandRecord.correlationId(),
                        commandRecord.id(),
                        responseRecords,
                        getCurrentGDPRContext() != null ? getCurrentGDPRContext().getEncryptionKey() : null);
                TopicPartition replyToTopicPartition = PartitionUtils.parseReplyToTopicPartition(commandRecord.replyToTopicPartition());
                logger.trace("Sending CommandResponseRecord with commandId {} to {}", crr.commandId(), replyToTopicPartition);
                KafkaSender.send(producer, new ProducerRecord<>(replyToTopicPartition.topic(), replyToTopicPartition.partition(), crr.commandId(), crr));
            }

        } catch (IOException e) {
            // TODO need to raise a (built-in) ErrorEvent here
            logger.error("Error handling command", e);
        } finally {
            tearDownGDPRContext();
        }
    }

    private void handleExternalEvent(DomainEventRecord eventRecord) {
        try {
            logger.trace("Handling DomainEventRecord with type {} as External Event", eventRecord.name());
            // only setup the GDPR context if required
            if(runtime.requiresGDPRContext(eventRecord)) {
                setupGDPRContext(eventRecord.tenantId(), eventRecord.aggregateId(), runtime.shouldGenerateGDPRKey(eventRecord));
            }
            runtime.handleExternalDomainEventRecord(eventRecord,
                    this::send,
                    this::index,
                    () -> stateRepository.get(eventRecord.aggregateId()),
                    this);
        } catch (IOException e) {
            // TODO need to raise a (built-in) ErrorEvent here
            logger.error("Error handling external event", e);
        } finally {
            tearDownGDPRContext();
        }
    }

    private void process() {
        try {
            if (processState == PROCESSING) {
                ConsumerRecords<String, ProtocolRecord> allRecords = consumer.poll(Duration.ofMillis(10));
                if (!allRecords.isEmpty()) {
                    processRecords(allRecords);
                }
            } else if (processState == LOADING_GDPR_KEYS) {
                ConsumerRecords<String, ProtocolRecord> gdprKeyRecords = consumer.poll(Duration.ofMillis(10));
                gdprContextRepository.process(gdprKeyRecords.records(gdprKeyPartition));
                // stop condition
                if (gdprKeyRecords.isEmpty() && initializedEndOffsets.getOrDefault(gdprKeyPartition, 0L) <= consumer.position(gdprKeyPartition)) {
                    // special case, there is no data yet so no need to load anything
                    if (initializedEndOffsets.getOrDefault(statePartition, 0L) == 0L) {
                        // we need to ensure we are not missing any commands or external events in this case
                        // see if we need to commit the initial offsets
                        commitInitialOffsetsIfNecessary();
                        logger.info("No state found in Kafka for AggregatePartition {} of {}Aggregate", id, runtime.getName());
                        // resume the other topics
                        consumer.resume(Stream.concat(Stream.of(statePartition, commandPartition, domainEventPartition), externalEventPartitions.stream()).toList());
                        // go immediately to processing
                        processState = PROCESSING;
                    } else {
                        logger.info("Loading state for AggregatePartition {} of {}Aggregate", id, runtime.getName());
                        // resume the state topic
                        consumer.resume(singletonList(statePartition));
                        // pause the gdpr key topic for now to avoid reading past it
                        consumer.pause(singletonList(gdprKeyPartition));
                        processState = LOADING_STATE;
                    }
                }
            } else if (processState == LOADING_STATE) {
                ConsumerRecords<String, ProtocolRecord> stateRecords = consumer.poll(Duration.ofMillis(10));
                stateRepository.process(stateRecords.records(statePartition));
                // stop condition
                if (stateRecords.isEmpty() && initializedEndOffsets.getOrDefault(statePartition, 0L) <= consumer.position(statePartition)) {
                    // done loading the state, enable the other topics
                    consumer.resume(Stream.concat(
                            Stream.concat(Stream.of(commandPartition, domainEventPartition),runtime.shouldHandlePIIData() ? Stream.of(gdprKeyPartition) : Stream.empty()),
                            externalEventPartitions.stream()).toList());
                    // and move to processing state
                    processState = PROCESSING;
                }
            } else if (processState == INITIALIZING) {
                logger.info(
                        "Initializing AggregatePartition {} of {}Aggregate. Will {}",
                        id,
                        runtime.getName(),
                        runtime.shouldHandlePIIData() ? "Handle PII Data" : "Not Handle PII Data");
                // find the right offset to start reading the state from
                long stateRepositoryOffset = stateRepository.getOffset();
                if (stateRepositoryOffset >= 0) {
                    logger.info(
                            "Resuming State from offset {} for AggregatePartition {} of {}Aggregate",
                            stateRepositoryOffset,
                            id,
                            runtime.getName());
                    consumer.seek(statePartition, stateRepository.getOffset() + 1);
                } else {
                    consumer.seekToBeginning(singletonList(statePartition));
                }
                if(runtime.shouldHandlePIIData()) {
                    // find the right offset to start reading the gdpr keys from
                    long gdprKeyRepositoryOffset = gdprContextRepository.getOffset();
                    if (gdprKeyRepositoryOffset >= 0) {
                        logger.info(
                                "Resuming GDPRKeys from offset {} for AggregatePartition {} of {}Aggregate",
                                gdprKeyRepositoryOffset,
                                id,
                                runtime.getName());
                        consumer.seek(gdprKeyPartition, gdprContextRepository.getOffset() + 1);
                    } else {
                        consumer.seekToBeginning(singletonList(gdprKeyPartition));
                    }
                    // find the end offsets so we know when to stop
                    initializedEndOffsets = consumer.endOffsets(List.of(gdprKeyPartition, statePartition));
                    logger.info("Loading GDPR Keys for AggregatePartition {} of {}Aggregate", id, runtime.getName());
                    // pause the other topics
                    consumer.pause(Stream.concat(Stream.of(statePartition, commandPartition, domainEventPartition), externalEventPartitions.stream()).toList());
                    processState = LOADING_GDPR_KEYS;
                } else {
                    // we should skip the LOADING_GDPR_KEYS phase and go directly to LOADING_STATE
                    initializedEndOffsets = consumer.endOffsets(List.of(statePartition));
                    // special case, there is no data yet so no need to load anything
                    if (initializedEndOffsets.getOrDefault(statePartition, 0L) == 0L) {
                        // we need to ensure we are not missing any commands or external events in this case
                        // see if we need to commit the initial offsets
                        commitInitialOffsetsIfNecessary();
                        logger.info("No state found in Kafka for AggregatePartition {} of {}Aggregate", id, runtime.getName());
                        // resume the other topics
                        consumer.resume(Stream.concat(Stream.of(statePartition, commandPartition, domainEventPartition), externalEventPartitions.stream()).toList());
                        // go immediately to processing
                        processState = PROCESSING;
                    } else {
                        logger.info("Loading state for AggregatePartition {} of {}Aggregate", id, runtime.getName());
                        // resume the state topic
                        consumer.resume(singletonList(statePartition));
                        // no need to pause the gdpr key topic since it is not assigned to the consumer in this case
                        processState = LOADING_STATE;
                    }
                }
            }
        } catch (WakeupException | InterruptException ignore) {
            // non-fatal. ignore
        } catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
            // For transactional producers, this is a fatal error and you should close the producer.
            logger.error("Fatal error during " + processState + " phase, shutting down AggregatePartition " + id + " of " + runtime.getName() + "Aggregate", e);
            processState = SHUTTING_DOWN;
        } catch (KafkaException e) {
            // fatal
            logger.error("Fatal error during " + processState + " phase, shutting down AggregatePartition " + id + " of " + runtime.getName() + "Aggregate", e);
            processState = SHUTTING_DOWN;
        }
    }

    private void commitInitialOffsetsIfNecessary() {
        // latest is the default
        String autoOffsetResetConfig = (String) Optional.ofNullable(consumerFactory.getConfigurationProperties()
                .get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).orElse("latest");
        if ("latest".equals(autoOffsetResetConfig)) {
            List<TopicPartition> topicPartitions = Stream.concat(Stream.concat(
                            Stream.of(commandPartition, domainEventPartition, statePartition),
                            runtime.shouldHandlePIIData() ? Stream.of(gdprKeyPartition) : Stream.empty()),
                            externalEventPartitions.stream()).toList();
            final Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(topicPartitions);
            final Map<TopicPartition, OffsetAndMetadata> committedOffsets = consumer.committed(new HashSet<>(topicPartitions));
            final Map<TopicPartition, OffsetAndMetadata> uncommittedTopicPartitions = new HashMap<>();
            committedOffsets.forEach((topicPartition, offsetAndMetadata) -> {
                if (offsetAndMetadata == null) {
                    logger.info("TopicPartition[{}] has no committed offsets, will commit offset {} to avoid " +
                            "skipping records", topicPartition, beginningOffsets.getOrDefault(topicPartition, 0L));
                    uncommittedTopicPartitions.put(topicPartition, new OffsetAndMetadata(beginningOffsets.getOrDefault(topicPartition, 0L)));
                }
            });
            if (!uncommittedTopicPartitions.isEmpty()) {
                producer.beginTransaction();
                producer.sendOffsetsToTransaction(uncommittedTopicPartitions, consumer.groupMetadata());
                producer.commitTransaction();
            }
        }
    }

    private void processRecords(ConsumerRecords<String, ProtocolRecord> allRecords) {
        try {
            if (logger.isTraceEnabled()) {
                logger.trace("Processing {} records in a single transaction", allRecords.count());
                logger.trace("Processing {} gdpr key records", allRecords.records(gdprKeyPartition).size());
                logger.trace("Processing {} command records", allRecords.records(commandPartition).size());
                if (!externalEventPartitions.isEmpty()) {
                    logger.trace("Processing {} external event records", externalEventPartitions.stream()
                            .map(externalEventPartition -> allRecords.records(externalEventPartition).size())
                            .mapToInt(Integer::intValue).sum());
                }
                logger.trace("Processing {} state records", allRecords.records(statePartition).size());
                logger.trace("Processing {} internal event records", allRecords.records(domainEventPartition).size());
            }
            // start a transaction
            producer.beginTransaction();
            Map<TopicPartition, Long> offsets = new HashMap<>();
            // first handle gdpr keys
            List<ConsumerRecord<String, ProtocolRecord>> gdprKeyRecords = allRecords.records(gdprKeyPartition);
            if (!gdprKeyRecords.isEmpty()) {
                gdprContextRepository.process(gdprKeyRecords);
                offsets.put(gdprKeyPartition, gdprKeyRecords.getLast().offset());
            }
            // second handle external events
            externalEventPartitions
                    .forEach(externalEventPartition -> allRecords.records(externalEventPartition)
                            .forEach(eventRecord -> {
                                handleExternalEvent((DomainEventRecord) eventRecord.value());
                                offsets.put(externalEventPartition, eventRecord.offset());
                            }));
            // then commands
            allRecords.records(commandPartition)
                    .forEach(commandRecord -> {
                        handleCommand((CommandRecord) commandRecord.value());
                        offsets.put(commandPartition, commandRecord.offset());
                    });
            // then state (ignore?)
            List<ConsumerRecord<String, ProtocolRecord>> stateRecords = allRecords.records(statePartition);
            if (!stateRecords.isEmpty()) {
                stateRepository.process(stateRecords);
                offsets.put(statePartition, stateRecords.getLast().offset());
            }
            // then internal events (ignore?)
            allRecords.records(domainEventPartition)
                    .forEach(domainEventRecord -> offsets.put(domainEventPartition, domainEventRecord.offset()));
            // find the processed offsets in each partition
            producer.sendOffsetsToTransaction(offsets.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue() + 1))),
                    consumer.groupMetadata());
            producer.commitTransaction();
            // commit the state repository
            stateRepository.commit();
            // commit the gdpr key repository
            gdprContextRepository.commit();
        } catch (InvalidProducerEpochException e) {
            // When encountering this exception, user should abort the ongoing transaction by calling
            // KafkaProducer#abortTransaction which would try to send initPidRequest and reinitialize the producer
            // under the hood
            producer.abortTransaction();
            rollbackConsumer(allRecords);
            stateRepository.rollback();
            gdprContextRepository.rollback();
        }
    }

    private void rollbackConsumer(ConsumerRecords<String, ProtocolRecord> consumerRecords) {
        consumerRecords.partitions().forEach(topicPartition -> {
            // find the lowest offset
            consumerRecords.records(topicPartition).stream().map(ConsumerRecord::offset).min(Long::compareTo)
                    .ifPresent(offset -> consumer.seek(topicPartition, offset));
        });
    }

    public boolean isProcessing() {
        return processState == PROCESSING;
    }
}
