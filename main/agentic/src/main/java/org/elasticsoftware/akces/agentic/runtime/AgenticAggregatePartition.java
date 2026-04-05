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
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.aggregate.IndexParams;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.elasticsoftware.akces.kafka.AggregatePartitionState.INITIALIZING;
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
 *   <li>Enforces a <em>sliding-window</em> memory capacity: whenever a
 *       {@link MemoryStoredEvent} would push the number of stored memories above
 *       {@code maxMemories}, a {@link MemoryRevokedEvent} is emitted automatically
 *       (within the same Kafka transaction) to evict the oldest entry.</li>
 * </ul>
 *
 * <p>Actual command and event-sourcing logic is fully delegated to the supplied
 * {@link AggregateRuntime}.
 */
public class AgenticAggregatePartition implements Runnable, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AgenticAggregatePartition.class);

    /** The fixed partition index used by all agentic aggregate partitions. */
    static final int AGENTIC_PARTITION = 0;

    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final AggregateRuntime runtime;
    private final AggregateStateRepository stateRepository;
    private final int maxMemories;
    private final ObjectMapper objectMapper;
    private final TopicPartition commandPartition;
    private final TopicPartition domainEventPartition;
    private final TopicPartition statePartition;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /**
     * Per-aggregate in-memory deque used for sliding-window eviction.
     * Key: aggregateId; Value: ordered list of memoryIds (oldest first).
     */
    private final Map<String, Deque<String>> memoryDeques = new HashMap<>();

    private Consumer<String, ProtocolRecord> consumer;
    private Producer<String, ProtocolRecord> producer;
    private volatile org.elasticsoftware.akces.kafka.AggregatePartitionState processState;

    /**
     * Creates a new {@code AgenticAggregatePartition}.
     *
     * @param consumerFactory        factory for creating Kafka consumers
     * @param producerFactory        factory for creating transactional Kafka producers
     * @param runtime                the aggregate runtime that processes commands and domain events
     * @param stateRepositoryFactory factory for creating the aggregate-state repository
     * @param maxMemories            maximum number of memories allowed before sliding-window eviction
     */
    public AgenticAggregatePartition(
            ConsumerFactory<String, ProtocolRecord> consumerFactory,
            ProducerFactory<String, ProtocolRecord> producerFactory,
            AggregateRuntime runtime,
            AggregateStateRepositoryFactory stateRepositoryFactory,
            int maxMemories) {
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
        this.runtime = runtime;
        this.stateRepository = stateRepositoryFactory.create(runtime, AGENTIC_PARTITION);
        this.maxMemories = maxMemories;
        // Plain ObjectMapper instance used for JSON payload inspection (sliding-window tracking).
        this.objectMapper = new ObjectMapper();
        this.commandPartition = new TopicPartition(runtime.getName() + COMMANDS_SUFFIX, AGENTIC_PARTITION);
        this.domainEventPartition = new TopicPartition(runtime.getName() + DOMAINEVENTS_SUFFIX, AGENTIC_PARTITION);
        this.statePartition = new TopicPartition(runtime.getName() + AGGREGRATESTATE_SUFFIX, AGENTIC_PARTITION);
        this.processState = INITIALIZING;
    }

    /**
     * Main processing loop.  Creates and hard-assigns a Kafka consumer to partition 0
     * of the command, domain-event, and aggregate-state topics, then polls for records
     * in FIFO order until the partition is closed.
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
            // Hard-assign partition 0 only — no consumer group rebalancing needed
            consumer.assign(List.of(commandPartition, domainEventPartition, statePartition));
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
            if (shutdownLatch.await(10, TimeUnit.SECONDS)) {
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
     * Single iteration of the main poll loop, dispatched according to the current
     * {@link org.elasticsoftware.akces.kafka.AggregatePartitionState}.
     */
    private void process() {
        try {
            if (processState == PROCESSING) {
                ConsumerRecords<String, ProtocolRecord> records = consumer.poll(Duration.ofMillis(10));
                if (!records.isEmpty()) {
                    processRecords(records);
                }
            } else if (processState == LOADING_STATE) {
                ConsumerRecords<String, ProtocolRecord> stateRecords = consumer.poll(Duration.ofMillis(10));
                stateRepository.process(stateRecords.records(statePartition));
                // Also initialise the memory deques from the events topic while loading
                stateRecords.records(domainEventPartition).forEach(this::trackMemoryEventDuringLoad);
                long endOffset = consumer.endOffsets(List.of(statePartition))
                        .getOrDefault(statePartition, 0L);
                if (stateRecords.isEmpty() && endOffset <= consumer.position(statePartition)) {
                    // Done loading state — resume command topic and switch to PROCESSING
                    consumer.resume(List.of(commandPartition, domainEventPartition));
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
                    // No existing state — start processing immediately
                    logger.info("No existing state for {}Aggregate AgenticPartition, starting PROCESSING",
                            runtime.getName());
                    processState = PROCESSING;
                } else {
                    // Pause command/event topics while loading state
                    consumer.pause(List.of(commandPartition, domainEventPartition));
                    processState = LOADING_STATE;
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
     * <p>Commands are dispatched to the {@link AggregateRuntime} in FIFO order.
     * After each {@link MemoryStoredEvent} the sliding-window eviction check is performed.
     *
     * @param allRecords the polled consumer records
     */
    private void processRecords(ConsumerRecords<String, ProtocolRecord> allRecords) {
        try {
            producer.beginTransaction();
            Map<TopicPartition, Long> offsets = new HashMap<>();

            // Process commands (FIFO)
            allRecords.records(commandPartition).forEach(record -> {
                handleCommand((CommandRecord) record.value());
                offsets.put(commandPartition, record.offset());
            });

            // Track domain-event offsets (we already sent the records from handleCommand)
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
     * Handles a single command record by delegating to the {@link AggregateRuntime},
     * wrapping the record consumer to intercept {@link MemoryStoredEvent}s and enforce
     * the sliding-window eviction policy.
     *
     * @param commandRecord the incoming command record to process
     */
    private void handleCommand(CommandRecord commandRecord) {
        // Track MemoryStored events emitted during this command for sliding-window check
        List<DomainEventRecord> memoryStoredEvents = new ArrayList<>();

        java.util.function.Consumer<ProtocolRecord> interceptingConsumer = pr -> {
            send(pr);
            if (pr instanceof DomainEventRecord der) {
                trackMemoryEvent(der);
                if ("MemoryStored".equals(der.name())) {
                    memoryStoredEvents.add(der);
                }
            }
        };

        try {
            logger.trace("Handling CommandRecord with type {}", commandRecord.name());
            runtime.handleCommandRecord(
                    commandRecord,
                    interceptingConsumer,
                    this::index,
                    () -> stateRepository.get(commandRecord.aggregateId()));

            // After command processing, apply sliding-window eviction for each new memory
            for (DomainEventRecord storedEvent : memoryStoredEvents) {
                enforceMemorySlidingWindow(storedEvent.aggregateId(), storedEvent.tenantId(),
                        storedEvent.correlationId(), storedEvent.generation());
            }
        } catch (IOException e) {
            logger.error("Error handling command {}", commandRecord.name(), e);
        }
    }

    /**
     * Enforces the sliding-window memory limit for the given aggregate.
     *
     * <p>If the number of tracked memories for the aggregate meets or exceeds
     * {@code maxMemories}, a {@link MemoryRevokedEvent} is emitted for the oldest
     * entry to bring the count back within the allowed window.
     *
     * @param aggregateId   the aggregate whose memory count should be checked
     * @param tenantId      tenant identifier propagated from the triggering command
     * @param correlationId correlation identifier propagated from the triggering command
     * @param generation    generation counter of the originating domain event
     */
    private void enforceMemorySlidingWindow(String aggregateId, String tenantId,
                                            String correlationId, long generation) {
        Deque<String> deque = memoryDeques.get(aggregateId);
        if (deque == null || deque.size() <= maxMemories) {
            return;
        }
        // Evict the oldest memory (front of deque)
        String oldestMemoryId = deque.pollFirst();
        if (oldestMemoryId == null) {
            return;
        }
        logger.debug("Sliding-window eviction: revoking memory {} for aggregate {}",
                oldestMemoryId, aggregateId);
        try {
            MemoryRevokedEvent evictionEvent = new MemoryRevokedEvent(
                    aggregateId,
                    oldestMemoryId,
                    "sliding window eviction",
                    Instant.now());
            byte[] payload = objectMapper.writeValueAsBytes(evictionEvent);
            DomainEventRecord evictionRecord = new DomainEventRecord(
                    tenantId,
                    "MemoryRevoked",
                    1,
                    payload,
                    PayloadEncoding.JSON,
                    aggregateId,
                    correlationId,
                    generation + 1);
            send(evictionRecord);
        } catch (tools.jackson.core.JacksonException e) {
            logger.error("Failed to serialize MemoryRevokedEvent for aggregate {} memory {}",
                    aggregateId, oldestMemoryId, e);
        }
    }

    /**
     * Tracks a domain event record to maintain the in-memory deque for sliding-window eviction.
     *
     * <p>Called during normal PROCESSING mode for each emitted domain event.
     *
     * @param der the domain event record to inspect
     */
    private void trackMemoryEvent(DomainEventRecord der) {
        try {
            if ("MemoryStored".equals(der.name())) {
                JsonNode tree = objectMapper.readTree(der.payload());
                JsonNode memoryIdNode = tree.get("memoryId");
                if (memoryIdNode != null && !memoryIdNode.isNull()) {
                    memoryDeques.computeIfAbsent(der.aggregateId(), id -> new ArrayDeque<>())
                            .addLast(memoryIdNode.stringValue());
                }
            } else if ("MemoryRevoked".equals(der.name())) {
                JsonNode tree = objectMapper.readTree(der.payload());
                JsonNode memoryIdNode = tree.get("memoryId");
                if (memoryIdNode != null && !memoryIdNode.isNull()) {
                    Deque<String> deque = memoryDeques.get(der.aggregateId());
                    if (deque != null) {
                        deque.remove(memoryIdNode.stringValue());
                    }
                }
            }
        } catch (tools.jackson.core.JacksonException e) {
            logger.warn("Failed to parse memory event payload for deque tracking: {}", der.name(), e);
        }
    }

    /**
     * Variant of {@link #trackMemoryEvent(DomainEventRecord)} used during the state-loading
     * phase to initialise the in-memory deques from the historical event log.
     *
     * @param record the consumer record wrapping the domain event
     */
    private void trackMemoryEventDuringLoad(ConsumerRecord<String, ProtocolRecord> record) {
        if (record.value() instanceof DomainEventRecord der) {
            trackMemoryEvent(der);
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
                producer.close(Duration.ofSeconds(5));
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
