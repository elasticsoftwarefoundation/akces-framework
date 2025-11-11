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

package org.elasticsoftware.akces.schemas.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import jakarta.annotation.Nullable;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Kafka-based implementation of schema storage using a compacted topic.
 * This implementation uses:
 * - Kafka producer factory for creating transactional producers when writing schemas
 * - Kafka consumer for reading schemas
 * - Caffeine cache for performance
 * - Process method to be called by controller for cache updates
 */
public class KafkaTopicSchemaStorageImpl implements KafkaTopicSchemaStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaTopicSchemaStorageImpl.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final String TOPIC_NAME = "Akces-Schemas";
    
    private final ProducerFactory<String, SchemaRecord> producerFactory;
    private final String txIdPrefix;
    private final Consumer<String, SchemaRecord> consumer;
    private final Cache<String, SchemaRecord> cache;
    
    /**
     * Creates a new Kafka-based schema storage.
     * 
     * @param producerFactory Kafka producer factory for writing schemas (can be null for read-only mode)
     * @param txIdPrefix Transaction ID prefix for creating unique producer IDs
     * @param consumer Kafka consumer for reading schemas
     */
    public KafkaTopicSchemaStorageImpl(
            @Nullable ProducerFactory<String, SchemaRecord> producerFactory,
            @Nullable String txIdPrefix,
            Consumer<String, SchemaRecord> consumer) {
        this.producerFactory = producerFactory;
        this.txIdPrefix = txIdPrefix;
        this.consumer = consumer;
        
        // Initialize cache with no expiration
        this.cache = Caffeine.newBuilder().build();
    }
    
    @Override
    public void initialize() throws SchemaException {
        try {
            // Use manual assignment for the consumer
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC_NAME).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .collect(Collectors.toList());
            
            if (partitions.isEmpty()) {
                logger.warn("No partitions found for topic: {}", TOPIC_NAME);
            } else {
                // Assign partitions to consumer
                consumer.assign(partitions);
                
                // Load initial cache from the beginning of the topic
                loadInitialCache();
            }
            
            logger.info("Initialized Kafka schema storage for topic: {}", TOPIC_NAME);
        } catch (Exception e) {
            throw new SchemaException(
                    "Failed to initialize schema storage",
                    TOPIC_NAME,
                    KafkaTopicSchemaStorageImpl.class,
                    e);
        }
    }
    
    /**
     * Process method to be called by the controller thread to poll for schema updates.
     * This method polls the Kafka topic for new schema records and updates the cache.
     */
    public void process() {
        try {
            ConsumerRecords<String, SchemaRecord> records = consumer.poll(POLL_TIMEOUT);
            for (ConsumerRecord<String, SchemaRecord> record : records) {
                processRecord(record);
            }
        } catch (Exception e) {
            logger.error("Error during schema storage processing", e);
        }
    }
    
    @Override
    public synchronized void registerSchema(String schemaName, JsonSchema schema, int version) throws SchemaException {
        if (producerFactory == null) {
            throw new UnsupportedOperationException("Schema registration is not supported in read-only mode");
        }
        
        String key = createKey(schemaName, version);
        SchemaRecord record = new SchemaRecord(schemaName, version, schema, System.currentTimeMillis());
        
        // Create producer only when needed in try-with-resources
        try (Producer<String, SchemaRecord> producer = producerFactory.createProducer(txIdPrefix)) {
            ProducerRecord<String, SchemaRecord> producerRecord = new ProducerRecord<>(TOPIC_NAME, key, record);
            
            // Begin transaction
            producer.beginTransaction();
            
            try {
                // Send within transaction
                producer.send(producerRecord).get(10, TimeUnit.SECONDS);
                
                // Commit transaction
                producer.commitTransaction();
                
                // Update cache
                cache.put(key, record);
                
                logger.debug("Registered schema {} version {}", schemaName, version);
            } catch (Exception e) {
                // Abort transaction on error
                producer.abortTransaction();
                throw e;
            }
        } catch (Exception e) {
            throw new SchemaException(
                    "Failed to register schema",
                    schemaName,
                    schema.getClass(),
                    e);
        }
    }
    
    @Override
    public List<SchemaRecord> getSchemas(String schemaName) throws SchemaException {
        try {
            // Get all versions from cache
            return cache.asMap().values().stream()
                    .filter(record -> record.schemaName().equals(schemaName))
                    .sorted(Comparator.comparingInt(SchemaRecord::version))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new SchemaException(
                    "Failed to retrieve schemas",
                    schemaName,
                    SchemaRecord.class,
                    e);
        }
    }
    
    @Override
    public Optional<SchemaRecord> getSchema(String schemaName, int version) throws SchemaException {
        try {
            String key = createKey(schemaName, version);
            SchemaRecord record = cache.getIfPresent(key);
            return Optional.ofNullable(record);
        } catch (Exception e) {
            throw new SchemaException(
                    "Failed to retrieve schema",
                    schemaName,
                    SchemaRecord.class,
                    e);
        }
    }
    
    @Override
    public void close() {
        // Producer is created on-demand in try-with-resources, so no need to close it here
        consumer.close();
        
        logger.info("Closed Kafka schema storage");
    }
    
    /**
     * Loads the initial cache by reading all records from the topic.
     * Uses the same approach as INITIAL_REBALANCING in AkcesAggregateController.
     */
    private void loadInitialCache() {
        try {
            // Get assigned partitions
            Set<TopicPartition> assignedPartitions = consumer.assignment();
            
            if (assignedPartitions.isEmpty()) {
                logger.warn("No partitions assigned for topic: {}", TOPIC_NAME);
                return;
            }
            
            // Seek to beginning to load all
            consumer.seekToBeginning(assignedPartitions);
            
            // Find the end offsets so we know when to stop
            Map<TopicPartition, Long> initializedEndOffsets = consumer.endOffsets(assignedPartitions);
            
            int recordCount = 0;
            
            // Read records until we reach the end offset for all partitions
            ConsumerRecords<String, SchemaRecord> consumerRecords = consumer.poll(POLL_TIMEOUT);
            while (!initializedEndOffsets.isEmpty()) {
                for (ConsumerRecord<String, SchemaRecord> record : consumerRecords) {
                    processRecord(record);
                    recordCount++;
                }
                
                // Test for stop condition - remove partitions that have reached their end offset
                if (consumerRecords.isEmpty()) {
                    initializedEndOffsets.entrySet().removeIf(entry -> 
                        entry.getValue() <= consumer.position(entry.getKey()));
                }
                
                // Poll again
                consumerRecords = consumer.poll(POLL_TIMEOUT);
            }
            
            logger.info("Loaded {} schema records into cache", recordCount);
        } catch (Exception e) {
            logger.error("Failed to load initial cache", e);
            throw new RuntimeException("Failed to load initial cache", e);
        }
    }
    
    /**
     * Processes a single consumer record, updating the cache.
     */
    private void processRecord(ConsumerRecord<String, SchemaRecord> record) {
        String key = record.key();
        SchemaRecord value = record.value();
        
        if (value != null) {
            cache.put(key, value);
            logger.trace("Added schema to cache: {}", key);
        }
    }
    
    /**
     * Creates a cache key from schema name and version.
     */
    private String createKey(String schemaName, int version) {
        return schemaName + "-v" + version;
    }
}
