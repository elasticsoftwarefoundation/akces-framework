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
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.control.AkcesRegistry;
import org.elasticsoftware.akces.gdpr.GDPRContextRepository;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.util.HostUtils;
import org.elasticsoftware.akces.util.KafkaSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.elasticsoftware.akces.query.reflector.ReflectorPartitionState.*;
import static org.elasticsoftware.akces.query.reflector.ReflectorResult.*;

public class ReflectorPartition implements Runnable, AutoCloseable, CommandBus {
    private static final Logger logger = LoggerFactory.getLogger(ReflectorPartition.class);

    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final ReflectorRuntime runtime;
    private final GDPRContextRepository gdprContextRepository;
    private final ObjectMapper objectMapper;
    private final Integer id;
    private final Set<TopicPartition> externalEventPartitions = new HashSet<>();
    private final Collection<DomainEventType<?>> externalDomainEventTypes;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AkcesRegistry akcesRegistry;
    private final TopicPartition gdprKeyPartition;
    /** Maps paused partitions to the earliest instant at which they may be resumed. */
    private final Map<TopicPartition, Instant> pausedUntil = new HashMap<>();
    /** Tracks the current retry attempt count per partition for linear backoff calculation. */
    private final Map<TopicPartition, Integer> retryAttempts = new HashMap<>();

    private Consumer<String, ProtocolRecord> consumer;
    private Producer<String, ProtocolRecord> producer;
    private volatile ReflectorPartitionState processState;
    private Map<TopicPartition, Long> initializedEndOffsets = Collections.emptyMap();
    private volatile Thread reflectorPartitionThread = null;

    public ReflectorPartition(ConsumerFactory<String, ProtocolRecord> consumerFactory,
                               ProducerFactory<String, ProtocolRecord> producerFactory,
                               ReflectorRuntime runtime,
                               GDPRContextRepositoryFactory gdprContextRepositoryFactory,
                               ObjectMapper objectMapper,
                               Integer id,
                               TopicPartition gdprKeyPartition,
                               Collection<DomainEventType<?>> externalDomainEventTypes,
                               AkcesRegistry akcesRegistry) {
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.id = id;
        this.externalDomainEventTypes = externalDomainEventTypes;
        this.akcesRegistry = akcesRegistry;
        this.gdprKeyPartition = gdprKeyPartition;
        this.processState = INITIALIZING;
        this.gdprContextRepository = gdprContextRepositoryFactory.create(runtime.getName(), id);
    }

    public Integer getId() {
        return id;
    }

    @Override
    public void run() {
        try {
            this.reflectorPartitionThread = Thread.currentThread();
            ReflectorPartitionCommandBus.registerCommandBus(this);
            logger.info("Starting ReflectorPartition {} of {}Reflector", id, runtime.getName());
            this.consumer = consumerFactory.createConsumer(
                    runtime.getName() + "Reflector-partition-" + id,
                    runtime.getName() + "Reflector-partition-" + id + "-" + HostUtils.getHostName(),
                    null);
            this.producer = producerFactory.createProducer(
                    runtime.getName() + "Reflector-partition-" + id + "-" + HostUtils.getHostName());
            // resolve the external event partitions
            Set<String> distinctTopics = externalDomainEventTypes.stream()
                    .map(akcesRegistry::resolveTopic)
                    .collect(Collectors.toSet());
            externalEventPartitions.addAll(distinctTopics.stream()
                    .map(topic -> new TopicPartition(topic, id))
                    .collect(Collectors.toSet()));
            // make a hard assignment, only assign the gdprKeyPartition if needed
            consumer.assign(Stream.concat(
                    runtime.shouldHandlePIIData() ? Stream.of(gdprKeyPartition) : Stream.empty(),
                    externalEventPartitions.stream()).toList());
            logger.info("Assigned partitions {} for ReflectorPartition {} of {}Reflector",
                    consumer.assignment(), id, runtime.getName());
            while (processState != SHUTTING_DOWN) {
                process();
            }
            logger.info("Shutting down ReflectorPartition {} of {}Reflector", id, runtime.getName());
        } catch (Throwable t) {
            logger.error("Unexpected error in ReflectorPartition {} of {}Reflector", id, runtime.getName(), t);
        } finally {
            try {
                consumer.close(Duration.ofSeconds(5));
                producer.close(Duration.ofSeconds(5));
            } catch (InterruptException e) {
                Thread.currentThread().interrupt();
            } catch (KafkaException e) {
                logger.error("Error closing consumer/producer", e);
            }
            try {
                gdprContextRepository.close();
            } catch (IOException e) {
                logger.error("Error closing gdpr context repository", e);
            }
            ReflectorPartitionCommandBus.registerCommandBus(null);
        }
        logger.info("Finished Shutting down ReflectorPartition {} of {}Reflector", id, runtime.getName());
        shutdownLatch.countDown();
    }

    @Override
    public void close() {
        processState = SHUTTING_DOWN;
        // wait maximum of 10 seconds for the shutdown to complete
        try {
            if (shutdownLatch.await(10, TimeUnit.SECONDS)) {
                logger.info("ReflectorPartition={} has been shutdown", id);
            } else {
                logger.warn("ReflectorPartition={} did not shutdown within 10 seconds", id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void send(Command command) {
        // this implementation is only meant to be called from the ReflectorPartition thread
        if (Thread.currentThread() != reflectorPartitionThread) {
            throw new IllegalStateException("send() can only be called from the ReflectorPartition thread");
        }
        CommandType<?> commandType = akcesRegistry.resolveType(command.getClass());
        if (commandType != null) {
            String topic = akcesRegistry.resolveTopic(commandType, command);
            byte[] payload;
            try {
                payload = objectMapper.writeValueAsBytes(command);
            } catch (Exception e) {
                logger.error("Error serializing command {}", commandType.typeName(), e);
                throw new RuntimeException(e);
            }
            CommandRecord commandRecord = new CommandRecord(
                    null,
                    commandType.typeName(),
                    commandType.version(),
                    payload,
                    PayloadEncoding.JSON,
                    command.getAggregateId(),
                    null,
                    null); // don't send a response
            Integer partition = akcesRegistry.resolvePartition(commandType, command);
            KafkaSender.send(producer, new ProducerRecord<>(topic, partition, commandRecord.id(), commandRecord));
        }
    }

    private void process() {
        try {
            if (processState == PROCESSING) {
                // resume any paused partitions whose backoff has elapsed
                resumeReadyPartitions();
                ConsumerRecords<String, ProtocolRecord> allRecords = consumer.poll(Duration.ofMillis(10));
                if (!allRecords.isEmpty()) {
                    processRecords(allRecords);
                }
            } else if (processState == LOADING_GDPR_KEYS) {
                ConsumerRecords<String, ProtocolRecord> gdprKeyRecords = consumer.poll(Duration.ofMillis(10));
                gdprContextRepository.process(gdprKeyRecords.records(gdprKeyPartition));
                // stop condition
                if (gdprKeyRecords.isEmpty() && initializedEndOffsets.getOrDefault(gdprKeyPartition, 0L) <= consumer.position(gdprKeyPartition)) {
                    // resume the other topics
                    consumer.resume(externalEventPartitions);
                    processState = PROCESSING;
                }
            } else if (processState == INITIALIZING) {
                logger.info(
                        "Initializing ReflectorPartition {} of {}Reflector. Will {}",
                        id,
                        runtime.getName(),
                        runtime.shouldHandlePIIData() ? "Handle PII Data" : "Not Handle PII Data");
                // handle possible GDPRContext setup
                if (runtime.shouldHandlePIIData()) {
                    long gdprKeyRepositoryOffset = gdprContextRepository.getOffset();
                    if (gdprKeyRepositoryOffset >= 0) {
                        logger.info(
                                "Resuming GDPRKeys from offset {} for ReflectorPartition {} of {}Reflector",
                                gdprKeyRepositoryOffset,
                                id,
                                runtime.getName());
                        consumer.seek(gdprKeyPartition, gdprContextRepository.getOffset() + 1);
                    } else {
                        consumer.seekToBeginning(singletonList(gdprKeyPartition));
                    }
                    // find the end offsets so we know when to stop
                    initializedEndOffsets = consumer.endOffsets(List.of(gdprKeyPartition));
                    logger.info("Loading GDPR Keys for ReflectorPartition {} of {}Reflector", id, runtime.getName());
                    // pause the external event partitions while loading GDPR keys
                    consumer.pause(externalEventPartitions);
                    processState = LOADING_GDPR_KEYS;
                } else {
                    // skip the LOADING_GDPR_KEYS phase and go directly to PROCESSING
                    consumer.resume(externalEventPartitions);
                    processState = PROCESSING;
                }
            }
        } catch (WakeupException | InterruptException ignore) {
            // non-fatal, ignore
        } catch (KafkaException e) {
            // fatal
            logger.error("Fatal error during {} phase, shutting down ReflectorPartition {} of {}Reflector",
                    processState, id, runtime.getName(), e);
            processState = SHUTTING_DOWN;
        }
    }

    private void resumeReadyPartitions() {
        if (!pausedUntil.isEmpty()) {
            Instant now = Instant.now();
            Set<TopicPartition> toResume = new HashSet<>();
            pausedUntil.entrySet().removeIf(entry -> {
                if (now.isAfter(entry.getValue())) {
                    toResume.add(entry.getKey());
                    return true;
                }
                return false;
            });
            if (!toResume.isEmpty()) {
                logger.debug("Resuming {} paused partition(s) for ReflectorPartition {} of {}Reflector",
                        toResume.size(), id, runtime.getName());
                consumer.resume(toResume);
            }
        }
    }

    private void processRecords(ConsumerRecords<String, ProtocolRecord> allRecords) {
        if (logger.isTraceEnabled()) {
            logger.trace("Processing {} records in poll batch for ReflectorPartition {} of {}Reflector",
                    allRecords.count(), id, runtime.getName());
        }
        // first handle GDPR key records (outside of the event transaction)
        if (runtime.shouldHandlePIIData()) {
            List<ConsumerRecord<String, ProtocolRecord>> gdprKeyRecords = allRecords.records(gdprKeyPartition);
            if (!gdprKeyRecords.isEmpty()) {
                gdprContextRepository.process(gdprKeyRecords);
            }
        }
        // process domain events one at a time, each in its own transaction
        for (TopicPartition partition : allRecords.partitions()) {
            if (partition.equals(gdprKeyPartition)) {
                continue; // already handled above
            }
            for (ConsumerRecord<String, ProtocolRecord> record : allRecords.records(partition)) {
                if (record.value() instanceof DomainEventRecord domainEventRecord) {
                    processEventRecord(domainEventRecord, partition, record.offset());
                }
            }
        }
    }

    private void processEventRecord(DomainEventRecord domainEventRecord,
                                    TopicPartition topicPartition,
                                    long offset) {
        ReflectorResult result;
        try {
            result = runtime.apply(domainEventRecord, topicPartition, gdprContextRepository::get);
        } catch (IOException e) {
            logger.error("IOException deserializing event '{}' on partition {} of {}Reflector",
                    domainEventRecord.name(), topicPartition, runtime.getName(), e);
            // treat deserialization failure as a permanent failure — skip the event
            result = FAILURE;
        }

        switch (result) {
            case RETRY_PENDING -> {
                // backoff has not elapsed yet; pause the partition and seek back so the event is re-delivered
                int attempt = retryAttempts.merge(topicPartition, 1, Integer::sum);
                long backoffMs = (long) attempt * runtime.getRetryBackoffBaseMs();
                pausedUntil.put(topicPartition, Instant.now().plusMillis(backoffMs));
                consumer.pause(Set.of(topicPartition));
                consumer.seek(topicPartition, offset);
                logger.debug("ReflectorPartition {} of {}Reflector: RETRY_PENDING for event '{}' on partition {} " +
                                "(attempt {}); pausing for {} ms",
                        id, runtime.getName(), domainEventRecord.name(), topicPartition, attempt, backoffMs);
            }
            case SUCCESS -> {
                // Only clear retry state when a handler actually ran (lastEvent non-null).
                // If lastEvent is null, the event had no handler and was silently skipped;
                // the partition may still be paused for a pending retry on a prior record.
                if (runtime.getLastEvent() != null) {
                    retryAttempts.remove(topicPartition);
                    pausedUntil.remove(topicPartition);
                }
                commitEventWithTransaction(
                        domainEventRecord, topicPartition, offset,
                        true, runtime.getLastEvent(), runtime.getLastResult(), null);
            }
            case FAILURE -> {
                retryAttempts.remove(topicPartition);
                pausedUntil.remove(topicPartition);
                commitEventWithTransaction(
                        domainEventRecord, topicPartition, offset,
                        false, runtime.getLastEvent(), null, runtime.getLastException());
            }
        }
    }

    private void commitEventWithTransaction(DomainEventRecord domainEventRecord,
                                            TopicPartition topicPartition,
                                            long offset,
                                            boolean success,
                                            org.elasticsoftware.akces.events.DomainEvent event,
                                            Object result,
                                            Exception exception) {
        try {
            producer.beginTransaction();
            try {
                if (event != null) {
                    if (success) {
                        runtime.handleSuccess(event, result, this);
                    } else {
                        runtime.handleFailure(event, exception, this);
                    }
                }
                producer.sendOffsetsToTransaction(
                        Map.of(topicPartition, new OffsetAndMetadata(offset + 1)),
                        consumer.groupMetadata());
                producer.commitTransaction();
                gdprContextRepository.commit();
            } catch (InvalidProducerEpochException e) {
                producer.abortTransaction();
                consumer.seek(topicPartition, offset);
                gdprContextRepository.rollback();
                logger.warn("Transaction aborted for event '{}' on partition {} of {}Reflector due to InvalidProducerEpochException",
                        domainEventRecord.name(), topicPartition, runtime.getName(), e);
            } catch (KafkaException e) {
                producer.abortTransaction();
                consumer.seek(topicPartition, offset);
                gdprContextRepository.rollback();
                logger.error("Transaction aborted for event '{}' on partition {} of {}Reflector",
                        domainEventRecord.name(), topicPartition, runtime.getName(), e);
            }
        } catch (KafkaException e) {
            logger.error("Error beginning transaction for event '{}' on partition {} of {}Reflector",
                    domainEventRecord.name(), topicPartition, runtime.getName(), e);
        }
    }

    public boolean isProcessing() {
        return processState == PROCESSING;
    }
}
