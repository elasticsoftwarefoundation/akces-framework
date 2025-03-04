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

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.control.AkcesRegistry;
import org.elasticsoftware.akces.gdpr.GDPRContextHolder;
import org.elasticsoftware.akces.gdpr.GDPRContextRepository;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.util.HostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.elasticsoftware.akces.query.database.DatabaseModelPartitionState.*;

public class DatabaseModelPartition implements Runnable, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseModelPartition.class);
    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final DatabaseModelRuntime runtime;
    private final GDPRContextRepository gdprContextRepository;
    private final Integer id;
    private final Set<TopicPartition> externalEventPartitions = new HashSet<>();
    private final Collection<DomainEventType<?>> externalDomainEventTypes;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private Consumer<String, ProtocolRecord> consumer;
    private volatile DatabaseModelPartitionState processState;
    private Map<TopicPartition, Long> initializedEndOffsets = Collections.emptyMap();
    private final AkcesRegistry akcesRegistry;
    private final TopicPartition gdprKeyPartition;

    public DatabaseModelPartition(ConsumerFactory<String, ProtocolRecord> consumerFactory,
                                  DatabaseModelRuntime runtime,
                                  GDPRContextRepositoryFactory gdprContextRepositoryFactory,
                                  Integer id,
                                  TopicPartition gdprKeyPartition,
                                  Collection<DomainEventType<?>> externalDomainEventTypes,
                                  AkcesRegistry akcesRegistry) {
        this.akcesRegistry = akcesRegistry;
        this.consumerFactory = consumerFactory;
        this.runtime = runtime;
        this.id = id;
        this.externalDomainEventTypes = externalDomainEventTypes;
        this.processState = INITIALIZING;
        this.gdprContextRepository = gdprContextRepositoryFactory.create(runtime.getName(), id);
        this.gdprKeyPartition = gdprKeyPartition;
    }

    public Integer getId() {
        return id;
    }

    @Override
    public void run() {
        try {
            logger.info("Starting DatabaseModelPartition {} of {}DatabaseModel", id, runtime.getName());
            this.consumer = consumerFactory.createConsumer(
                    runtime.getName() + "DatabaseModel-partition-" + id,
                    runtime.getName() + "DatabaseModel-partition-" + id + "-" + HostUtils.getHostName(),
                    null);
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
            logger.info("Assigned partitions {} for DatabaseModelPartition {} of {}DatabaseModel", consumer.assignment(), id, runtime.getName());
            while (processState != SHUTTING_DOWN) {
                process();
            }
            logger.info("Shutting down DatabaseModelPartition {} of {}DatabaseModel", id, runtime.getName());
        } catch (Throwable t) {
            logger.error("Unexpected error in DatabaseModelPartition {} of {}DatabaseModel", id, runtime.getName(), t);
        } finally {
            try {
                consumer.close(Duration.ofSeconds(5));
            } catch (InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                logger.error("Error closing consumer/producer", e);
            }
            try {
                gdprContextRepository.close();
            } catch (IOException e) {
                logger.error("Error closing gdpr context repository", e);
            }
        }
        logger.info("Finished Shutting down DatabaseModelPartition {} of {}DatabaseModel", id, runtime.getName());
        shutdownLatch.countDown();
    }

    @Override
    public void close() {
        processState = SHUTTING_DOWN;
        // wait maximum of 10 seconds for the shutdown to complete
        try {
            if (shutdownLatch.await(10, TimeUnit.SECONDS)) {
                logger.info("DatabaseModelPartition={} has been shutdown", id);
            } else {
                logger.warn("DatabaseModelPartition={} did not shutdown within 10 seconds", id);
            }
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void setupGDPRContext(String tenantId, String aggregateId) {
        // setup the context, this will either be a DefaultGDPRContext or a NoopGDPRContext
        GDPRContextHolder.setCurrentGDPRContext(gdprContextRepository.get(aggregateId));
    }

    private void tearDownGDPRContext() {
        GDPRContextHolder.resetCurrentGDPRContext();
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
                    // resume the other topics
                    consumer.resume(externalEventPartitions);
                    // go immediately to processing
                    processState = PROCESSING;
                }
            }  else if (processState == INITIALIZING) {
                logger.info(
                        "Initializing DatabaseModelPartition {} of {}Aggregate. Will {}",
                        id,
                        runtime.getName(),
                        runtime.shouldHandlePIIData() ? "Handle PII Data" : "Not Handle PII Data");
                // we need to initialize the offsets
                runtime.initializeOffsets(externalEventPartitions).forEach((topicPartition, offset)
                        -> consumer.seek(topicPartition, offset + 1));
                // handle possible GDPRContext setup
                if(runtime.shouldHandlePIIData()) {
                    // find the right offset to start reading the gdpr keys from
                    long gdprKeyRepositoryOffset = gdprContextRepository.getOffset();
                    if (gdprKeyRepositoryOffset >= 0) {
                        logger.info(
                                "Resuming GDPRKeys from offset {} for DatabaseModelPartition {} of {}DatabaseModel",
                                gdprKeyRepositoryOffset,
                                id,
                                runtime.getName());
                        consumer.seek(gdprKeyPartition, gdprContextRepository.getOffset() + 1);
                    } else {
                        consumer.seekToBeginning(singletonList(gdprKeyPartition));
                    }
                    // find the end offsets so we know when to stop
                    initializedEndOffsets = consumer.endOffsets(List.of(gdprKeyPartition));
                    logger.info("Loading GDPR Keys for DatabaseModelPartition {} of {}DatabaseModel", id, runtime.getName());
                    // pause the other topics
                    consumer.pause(externalEventPartitions);
                    processState = LOADING_GDPR_KEYS;
                } else {
                    // we should skip the LOADING_GDPR_KEYS phase and go directly to PROCESSING
                    // resume the other topics
                    consumer.resume(externalEventPartitions);
                    // go immediately to processing
                    processState = PROCESSING;
                }
            }
        } catch (WakeupException | InterruptException ignore) {
            // non-fatal. ignore
        } catch (KafkaException e) {
            // fatal
            logger.error("Fatal error during " + processState + " phase, shutting down DatabaseModelPartition " + id + " of " + runtime.getName() + "DatabaseModel", e);
            processState = SHUTTING_DOWN;
        }
    }

    private void processRecords(ConsumerRecords<String, ProtocolRecord> allRecords) {
        if (logger.isTraceEnabled()) {
            logger.trace("Processing {} records in a single batch", allRecords.count());
            logger.trace("Processing {} gdpr key records", allRecords.records(gdprKeyPartition).size());
            if (!externalEventPartitions.isEmpty()) {
                logger.trace("Processing {} external event records", externalEventPartitions.stream()
                        .map(externalEventPartition -> allRecords.records(externalEventPartition).size())
                        .mapToInt(Integer::intValue).sum());
            }
        }
        // first handle gdpr keys
        List<ConsumerRecord<String, ProtocolRecord>> gdprKeyRecords = allRecords.records(gdprKeyPartition);
        if (!gdprKeyRecords.isEmpty()) {
            gdprContextRepository.process(gdprKeyRecords);
        }
        // second handle external events
        // TODO: we probably need to handle the external events in order
        try {
            runtime.apply(allRecords.partitions().stream()
                    .filter(topicPartition -> !topicPartition.equals(gdprKeyPartition))
                    .collect(Collectors.toMap(
                            partition -> partition,
                            allRecords::records
                    )), gdprContextRepository::get);
        } catch(IOException e) {
            // TODO: we should probably crash because data is missing now
            logger.error("IOException while processing events", e);
        }
    }

    public boolean isProcessing() {
        return processState == PROCESSING;
    }
}
