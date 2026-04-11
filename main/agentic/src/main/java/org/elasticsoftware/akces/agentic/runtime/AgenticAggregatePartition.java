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

package org.elasticsoftware.akces.agentic.runtime;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.aggregate.IndexParams;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.control.AkcesRegistry;
import org.elasticsoftware.akces.kafka.PartitionUtils;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.CommandResponseRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.state.AggregateStateRepository;
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
import org.elasticsoftware.akces.util.KafkaSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.elasticsoftware.akces.kafka.AggregatePartitionState.INITIALIZING;
import static org.elasticsoftware.akces.kafka.AggregatePartitionState.INITIALIZING_STATE;
import static org.elasticsoftware.akces.kafka.AggregatePartitionState.LOADING_STATE;
import static org.elasticsoftware.akces.kafka.AggregatePartitionState.PROCESSING;
import static org.elasticsoftware.akces.kafka.AggregatePartitionState.SHUTTING_DOWN;
import static org.elasticsoftware.akces.kafka.PartitionUtils.AGGREGRATESTATE_SUFFIX;
import static org.elasticsoftware.akces.kafka.PartitionUtils.COMMANDS_SUFFIX;
import static org.elasticsoftware.akces.kafka.PartitionUtils.DOMAINEVENTS_SUFFIX;

/**
 * Simplified Kafka-backed partition handler for {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}s.
 *
 * <p>Unlike {@link org.elasticsoftware.akces.kafka.AggregatePartition}, this class:
 * <ul>
 *   <li>Always hard-assigns <strong>partition 0</strong> — no Kafka consumer-group rebalancing
 *       is needed because every agentic aggregate runs as a single-partition service.</li>
 *   <li>Processes commands in strict FIFO order; there is no prioritisation queue.</li>
 *   <li>Supports external domain-event subscriptions analogous to
 *       {@link org.elasticsoftware.akces.kafka.AggregatePartition}.</li>
 * </ul>
 *
 * <p>Actual command and event-sourcing logic is fully delegated to the supplied
 * {@link AgenticAggregateRuntime}.
 */
public class AgenticAggregatePartition implements Runnable, AutoCloseable, CommandBus {

    private static final Logger logger = LoggerFactory.getLogger(AgenticAggregatePartition.class);

    /** The fixed partition index used by all agentic aggregate partitions. */
    static final int AGENTIC_PARTITION = 0;

    /** Maximum time (in seconds) to wait for the partition to complete shutdown. */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;

    /** Maximum time (in seconds) to wait for the Kafka producer to close. */
    private static final int PRODUCER_CLOSE_TIMEOUT_SECONDS = 5;

    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final AgenticAggregateRuntime runtime;
    private final AggregateStateRepository stateRepository;
    private final Collection<DomainEventType<?>> externalDomainEventTypes;
    private final AkcesRegistry ackesRegistry;
    private final TopicPartition commandPartition;
    private final TopicPartition domainEventPartition;
    private final TopicPartition statePartition;
    private final Set<TopicPartition> externalEventPartitions = new HashSet<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private Consumer<String, ProtocolRecord> consumer;
    private Producer<String, ProtocolRecord> producer;
    private volatile org.elasticsoftware.akces.kafka.AggregatePartitionState processState;

    /**
     * Creates a new {@code AgenticAggregatePartition}.
     *
     * @param consumerFactory        factory for creating Kafka consumers
     * @param producerFactory        factory for creating transactional Kafka producers
     * @param runtime                the agentic aggregate runtime that processes commands and domain events
     * @param stateRepositoryFactory factory for creating the aggregate-state repository
     * @param externalDomainEventTypes the collection of external domain event types to subscribe to
     * @param ackesRegistry          registry for resolving command types, topics, and partitions
     */
    public AgenticAggregatePartition(
            ConsumerFactory<String, ProtocolRecord> consumerFactory,
            ProducerFactory<String, ProtocolRecord> producerFactory,
            AgenticAggregateRuntime runtime,
            AggregateStateRepositoryFactory stateRepositoryFactory,
            Collection<DomainEventType<?>> externalDomainEventTypes,
            AkcesRegistry ackesRegistry) {
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
        this.runtime = runtime;
        this.stateRepository = stateRepositoryFactory.create(runtime, AGENTIC_PARTITION);
        this.externalDomainEventTypes = externalDomainEventTypes;
        this.ackesRegistry = ackesRegistry;
        this.commandPartition = new TopicPartition(runtime.getName() + COMMANDS_SUFFIX, AGENTIC_PARTITION);
        this.domainEventPartition = new TopicPartition(runtime.getName() + DOMAINEVENTS_SUFFIX, AGENTIC_PARTITION);
        this.statePartition = new TopicPartition(runtime.getName() + AGGREGRATESTATE_SUFFIX, AGENTIC_PARTITION);
        this.processState = INITIALIZING;
    }

    /**
     * Main processing loop. Creates and hard-assigns a Kafka consumer to partition 0
     * of the command, domain-event, state, and any external domain-event topics, then
     * polls for records in FIFO order until the partition is closed.
     */
    @Override
    public void run() {
        try {
            logger.info("Starting AgenticAggregatePartition for {}Aggregate", runtime.getName());
            this.consumer = consumerFactory.createConsumer(
                    runtime.getName() + "AgenticAggregate-partition-" + AGENTIC_PARTITION,
                    runtime.getName() + "AgenticAggregate-partition-" + AGENTIC_PARTITION,
                    null);
            this.producer = producerFactory.createProducer(
                    runtime.getName() + "AgenticAggregate-partition-" + AGENTIC_PARTITION);

            // Resolve external event topic partitions from the registry.
            // Since agentic aggregates are singletons, subscribe to ALL partitions of each
            // external topic so that no events are missed regardless of which partition they
            // were produced on.
            externalDomainEventTypes.forEach(domainEventType -> {
                List<String> topics = ackesRegistry.resolveTopics(domainEventType);
                topics.forEach(topic -> {
                    List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
                    if (partitionInfos == null || partitionInfos.isEmpty()) {
                        logger.warn("No partition info found for external topic '{}'; skipping subscription", topic);
                    } else {
                        partitionInfos.forEach(pi ->
                                externalEventPartitions.add(new TopicPartition(topic, pi.partition())));
                        logger.info("Subscribing to {} partition(s) of external topic '{}'",
                                partitionInfos.size(), topic);
                    }
                });
            });

            // Hard-assign all partitions — no consumer group rebalancing needed
            List<TopicPartition> allPartitions = new ArrayList<>(
                    List.of(commandPartition, domainEventPartition, statePartition));
            allPartitions.addAll(externalEventPartitions);
            consumer.assign(allPartitions);
            logger.info("AgenticAggregatePartition for {}Aggregate assigned partitions {}",
                    runtime.getName(), consumer.assignment());

            while (processState != SHUTTING_DOWN) {
                process();
            }
            logger.info("Shutting down AgenticAggregatePartition for {}Aggregate", runtime.getName());
        } catch (Throwable t) {
            logger.error("Unexpected error in AgenticAggregatePartition for {}Aggregate",
                    runtime.getName(), t);
        } finally {
            closeResources();
        }
        logger.info("Finished shutting down AgenticAggregatePartition for {}Aggregate",
                runtime.getName());
        shutdownLatch.countDown();
    }

    /**
     * Signals this partition to stop processing and waits up to 10 seconds for shutdown.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    @Override
    public void close() throws InterruptedException {
        processState = SHUTTING_DOWN;
        try {
            if (shutdownLatch.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.info("AgenticAggregatePartition for {}Aggregate has been shutdown",
                        runtime.getName());
            } else {
                logger.warn("AgenticAggregatePartition for {}Aggregate did not shutdown within 10 seconds",
                        runtime.getName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Dispatches a {@link Command} produced by an external-event handler onto the correct
     * Kafka command topic. This is the {@link CommandBus} implementation used within
     * external-event processing.
     *
     * @param command the command to send
     */
    @Override
    public void send(Command command) {
        var commandType = ackesRegistry.resolveType(command.getClass());
        if (commandType != null) {
            String topic = ackesRegistry.resolveTopic(commandType, command);
            CommandRecord commandRecord = new CommandRecord(
                    null,
                    commandType.typeName(),
                    commandType.version(),
                    runtime.serialize(command),
                    PayloadEncoding.JSON,
                    command.getAggregateId(),
                    null,
                    null); // no response routing for system-originated commands
            Integer partition = ackesRegistry.resolvePartition(commandType, command);
            KafkaSender.send(producer, new ProducerRecord<>(topic, partition, commandRecord.id(), commandRecord));
        }
    }

    /**
     * Single iteration of the main poll loop, dispatched according to the current
     * {@link org.elasticsoftware.akces.kafka.AggregatePartitionState}.
     */
    private void process() {
        try {
            if (processState == PROCESSING) {
                ConsumerRecords<String, ProtocolRecord> records = consumer.poll(Duration.ofMillis(10));
                if (!records.isEmpty()) {
                    processRecords(records);
                } else {
                    resumeAgentTasks();
                }
            } else if (processState == LOADING_STATE) {
                ConsumerRecords<String, ProtocolRecord> stateRecords = consumer.poll(Duration.ofMillis(10));
                stateRepository.process(stateRecords.records(statePartition));
                long endOffset = consumer.endOffsets(List.of(statePartition))
                        .getOrDefault(statePartition, 0L);
                if (stateRecords.isEmpty() && endOffset <= consumer.position(statePartition)) {
                    // Done loading state — resume all non-state partitions and switch to PROCESSING.
                    // Memory state is derived directly from the loaded aggregate state records,
                    // so no separate memory-deque initialisation is needed here.
                    List<TopicPartition> toResume = new ArrayList<>(
                            List.of(commandPartition, domainEventPartition));
                    toResume.addAll(externalEventPartitions);
                    consumer.resume(toResume);
                    processState = PROCESSING;
                    logger.info("AgenticAggregatePartition for {}Aggregate finished loading state, " +
                            "switching to PROCESSING", runtime.getName());
                }
            } else if (processState == INITIALIZING) {
                logger.info("Initialising AgenticAggregatePartition for {}Aggregate", runtime.getName());
                long stateOffset = stateRepository.getOffset();
                if (stateOffset >= 0) {
                    consumer.seek(statePartition, stateOffset + 1);
                } else {
                    consumer.seekToBeginning(List.of(statePartition));
                }
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(List.of(statePartition));
                if (endOffsets.getOrDefault(statePartition, 0L) == 0L) {
                    // No existing state — transition to INITIALIZING_STATE to auto-create
                    // the singleton aggregate state in the next poll cycle.
                    logger.info("No existing state for {}Aggregate AgenticPartition, " +
                            "switching to INITIALIZING_STATE", runtime.getName());
                    processState = INITIALIZING_STATE;
                } else {
                    // Pause command/event/external topics while loading state
                    List<TopicPartition> toPause = new ArrayList<>(
                            List.of(commandPartition, domainEventPartition));
                    toPause.addAll(externalEventPartitions);
                    consumer.pause(toPause);
                    processState = LOADING_STATE;
                }
            } else if (processState == INITIALIZING_STATE) {
                logger.info("Auto-creating initial state for {}Aggregate AgenticPartition",
                        runtime.getName());
                try {
                    producer.beginTransaction();
                    runtime.initializeState(this::send, this::index);
                    producer.commitTransaction();
                    stateRepository.commit();
                    processState = PROCESSING;
                    logger.info("Successfully auto-created initial state for {}Aggregate, " +
                            "switching to PROCESSING", runtime.getName());
                } catch (Exception e) {
                    logger.error("Failed to auto-create initial state for {}Aggregate — " +
                            "shutting down", runtime.getName(), e);
                    try {
                        producer.abortTransaction();
                    } catch (KafkaException ae) {
                        logger.error("Failed to abort transaction", ae);
                    }
                    stateRepository.rollback();
                    processState = SHUTTING_DOWN;
                }
            }
        } catch (WakeupException | InterruptException ignore) {
            // Non-fatal; ignore
        } catch (KafkaException e) {
            logger.error("Fatal KafkaException in AgenticAggregatePartition for {}Aggregate during " +
                    "{} phase — shutting down", runtime.getName(), processState, e);
            processState = SHUTTING_DOWN;
        }
    }

    /**
     * Processes a batch of consumer records inside a single Kafka transaction.
     *
     * <p>External domain events are processed before commands (consistent with
     * {@link org.elasticsoftware.akces.kafka.AggregatePartition}).
     * After each command, the sliding-window eviction check is performed.
     *
     * @param allRecords the polled consumer records
     */
    private void processRecords(ConsumerRecords<String, ProtocolRecord> allRecords) {
        try {
            producer.beginTransaction();
            Map<TopicPartition, Long> offsets = new HashMap<>();

            // Process external domain events first
            externalEventPartitions.forEach(externalPartition ->
                    allRecords.records(externalPartition).forEach(record -> {
                        handleExternalEvent((DomainEventRecord) record.value());
                        offsets.put(externalPartition, record.offset());
                    }));

            // Process commands (FIFO)
            allRecords.records(commandPartition).forEach(record -> {
                handleCommand((CommandRecord) record.value());
                offsets.put(commandPartition, record.offset());
            });

            // Track domain-event offsets (records already produced from handleCommand)
            allRecords.records(domainEventPartition)
                    .forEach(r -> offsets.put(domainEventPartition, r.offset()));

            // Track state records
            List<ConsumerRecord<String, ProtocolRecord>> stateRecords =
                    allRecords.records(statePartition);
            if (!stateRecords.isEmpty()) {
                stateRepository.process(stateRecords);
                offsets.put(statePartition, stateRecords.getLast().offset());
            }

            if (!offsets.isEmpty()) {
                Map<TopicPartition, OffsetAndMetadata> offsetsToCommit = new HashMap<>();
                offsets.forEach((tp, offset) ->
                        offsetsToCommit.put(tp, new OffsetAndMetadata(offset + 1)));
                producer.sendOffsetsToTransaction(offsetsToCommit, consumer.groupMetadata());
            }

            producer.commitTransaction();
            stateRepository.commit();
        } catch (KafkaException e) {
            logger.error("Transaction failed for AgenticAggregatePartition of {}Aggregate — aborting",
                    runtime.getName(), e);
            try {
                producer.abortTransaction();
            } catch (KafkaException ae) {
                logger.error("Failed to abort transaction", ae);
            }
            stateRepository.rollback();
        }
    }

    /**
     * Handles a single command record by delegating to the {@link AgenticAggregateRuntime}.
     * A {@link CommandResponseRecord} is sent when {@code replyToTopicPartition} is set.
     *
     * @param commandRecord the incoming command record to process
     */
    private void handleCommand(CommandRecord commandRecord) {
        final List<DomainEventRecord> responseRecords =
                commandRecord.replyToTopicPartition() != null ? new ArrayList<>() : null;

        java.util.function.Consumer<ProtocolRecord> interceptingConsumer = pr -> {
            send(pr);
            if (pr instanceof DomainEventRecord der && responseRecords != null) {
                responseRecords.add(der);
            }
        };

        try {
            logger.trace("Handling CommandRecord with type {}", commandRecord.name());
            runtime.handleCommandRecord(
                    commandRecord,
                    interceptingConsumer,
                    this::index,
                    () -> stateRepository.get(commandRecord.aggregateId()));

            // Send a response so that AkcesClient.send() can complete its CompletionStage
            if (responseRecords != null) {
                CommandResponseRecord crr = new CommandResponseRecord(
                        commandRecord.tenantId(),
                        commandRecord.aggregateId(),
                        commandRecord.correlationId(),
                        commandRecord.id(),
                        responseRecords,
                        null); // agentic aggregates have no GDPR context
                TopicPartition replyTo = PartitionUtils.parseReplyToTopicPartition(
                        commandRecord.replyToTopicPartition());
                logger.trace("Sending CommandResponseRecord with commandId {} to {}",
                        crr.commandId(), replyTo);
                KafkaSender.send(producer,
                        new ProducerRecord<>(replyTo.topic(), replyTo.partition(),
                                crr.commandId(), crr));
            }
        } catch (IOException e) {
            logger.error("Error handling command {}", commandRecord.name(), e);
        }
    }

    /**
     * Handles an external domain event record by delegating to the
     * {@link AgenticAggregateRuntime}.
     *
     * @param eventRecord the external domain event record to process
     */
    private void handleExternalEvent(DomainEventRecord eventRecord) {
        try {
            logger.trace("Handling DomainEventRecord with type {} as External Event", eventRecord.name());
            runtime.handleExternalDomainEventRecord(
                    eventRecord,
                    this::send,
                    this::index,
                    () -> stateRepository.get(eventRecord.aggregateId()),
                    this);
        } catch (IOException e) {
            logger.error("Error handling external event {}", eventRecord.name(), e);
        }
    }

    /**
     * Resumes agent tasks for the singleton aggregate when no records are available
     * from the Kafka poll.
     *
     * <p>This method is called from the main processing loop when the partition is in
     * the {@code PROCESSING} state and the consumer poll returns no records. It first
     * checks whether the aggregate has any active agent tasks and only opens a Kafka
     * transaction when there is work to do, avoiding unnecessary transaction overhead
     * when idle.
     */
    private void resumeAgentTasks() {
        try {
            if (!runtime.hasActiveAgentTasks(() -> stateRepository.get(runtime.getName()))) {
                return;
            }

            producer.beginTransaction();

            runtime.resumeNextAgentTask(
                    this::send,
                    () -> stateRepository.get(runtime.getName()),
                    this);

            producer.commitTransaction();
            stateRepository.commit();
        } catch (KafkaException e) {
            logger.error("Transaction failed during resumeAgentTasks for {}Aggregate — aborting",
                    runtime.getName(), e);
            try {
                producer.abortTransaction();
            } catch (KafkaException ae) {
                logger.error("Failed to abort transaction", ae);
            }
            stateRepository.rollback();
        } catch (IOException e) {
            logger.error("Error resuming agent tasks for {}Aggregate", runtime.getName(), e);
            try {
                producer.abortTransaction();
            } catch (KafkaException ae) {
                logger.error("Failed to abort transaction", ae);
            }
            stateRepository.rollback();
        }
    }

    /**
     * Sends a {@link ProtocolRecord} to the appropriate Kafka topic.
     *
     * @param protocolRecord the record to send
     */
    private void send(ProtocolRecord protocolRecord) {
        if (protocolRecord instanceof AggregateStateRecord asr) {
            Future<RecordMetadata> result = KafkaSender.send(producer,
                    new ProducerRecord<>(statePartition.topic(), statePartition.partition(),
                            asr.aggregateId(), asr));
            stateRepository.prepare(asr, result);
        } else if (protocolRecord instanceof DomainEventRecord der) {
            KafkaSender.send(producer,
                    new ProducerRecord<>(domainEventPartition.topic(), domainEventPartition.partition(),
                            der.id(), der));
        }
    }

    /**
     * Handles domain-event indexing requests from the runtime.
     * Currently a no-op stub — agentic aggregates do not use secondary index topics by default.
     *
     * @param der    the domain event record to index
     * @param params the index parameters (ignored)
     */
    private void index(DomainEventRecord der, IndexParams params) {
        // Agentic aggregates do not use secondary index topics by default.
        logger.trace("Index requested for event {} — skipping (agentic aggregates do not index by default)",
                der.name());
    }

    /**
     * Closes the Kafka consumer, producer, and the state repository, suppressing exceptions
     * so that the owning thread can reach the {@link #shutdownLatch} countdown.
     */
    private void closeResources() {
        try {
            if (consumer != null) {
                consumer.close();
            }
        } catch (KafkaException e) {
            logger.error("Error closing Kafka consumer for AgenticAggregatePartition of {}Aggregate",
                    runtime.getName(), e);
        }
        try {
            if (producer != null) {
                producer.close(Duration.ofSeconds(PRODUCER_CLOSE_TIMEOUT_SECONDS));
            }
        } catch (KafkaException e) {
            logger.error("Error closing Kafka producer for AgenticAggregatePartition of {}Aggregate",
                    runtime.getName(), e);
        }
        try {
            stateRepository.close();
        } catch (IOException e) {
            logger.error("Error closing state repository for AgenticAggregatePartition of {}Aggregate",
                    runtime.getName(), e);
        }
    }

    /**
     * Returns {@code true} if this partition is in the {@code PROCESSING} state.
     *
     * @return {@code true} when actively processing commands
     */
    public boolean isProcessing() {
        return processState == PROCESSING;
    }
}
