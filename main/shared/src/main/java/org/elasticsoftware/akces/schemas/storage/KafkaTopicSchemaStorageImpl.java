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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Kafka-based implementation of schema storage using a compacted topic.
 * This implementation uses:
 * - Kafka producer for writing schemas
 * - Kafka consumer for reading schemas
 * - Caffeine cache for performance
 * - Background polling thread to keep cache updated
 */
public class KafkaTopicSchemaStorageImpl implements KafkaTopicSchemaStorage {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaTopicSchemaStorageImpl.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 100;
    
    private final String topicName;
    private final Producer<String, byte[]> producer;
    private final Consumer<String, byte[]> consumer;
    private final Admin adminClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, SchemaRecord> cache;
    private final ScheduledExecutorService pollExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int replicationFactor;
    private final long cacheTtlSeconds;
    
    /**
     * Creates a new Kafka-based schema storage.
     * 
     * @param topicName the name of the Kafka topic to use for storage
     * @param producer Kafka producer for writing schemas
     * @param consumer Kafka consumer for reading schemas
     * @param adminClient Kafka admin client for topic management
     * @param objectMapper Jackson ObjectMapper for JSON serialization
     * @param cacheTtlSeconds cache TTL in seconds (0 for no expiration)
     * @param replicationFactor replication factor for the schema topic
     */
    public KafkaTopicSchemaStorageImpl(
            String topicName,
            Producer<String, byte[]> producer,
            Consumer<String, byte[]> consumer,
            Admin adminClient,
            ObjectMapper objectMapper,
            long cacheTtlSeconds,
            int replicationFactor) {
        this.topicName = topicName;
        this.producer = producer;
        this.consumer = consumer;
        this.adminClient = adminClient;
        this.objectMapper = objectMapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.replicationFactor = replicationFactor;
        
        // Initialize cache
        var cacheBuilder = Caffeine.newBuilder();
        if (cacheTtlSeconds > 0) {
            cacheBuilder.expireAfterWrite(Duration.ofSeconds(cacheTtlSeconds));
        }
        this.cache = cacheBuilder.build();
        
        // Create background polling executor
        this.pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "schema-storage-poller");
            t.setDaemon(true);
            return t;
        });
    }
    
    @Override
    public void initialize() throws SchemaException {
        try {
            // Create topic if it doesn't exist
            createTopicIfNeeded();
            
            // Use manual assignment for the consumer
            List<TopicPartition> partitions = consumer.partitionsFor(topicName).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .collect(Collectors.toList());
            
            if (partitions.isEmpty()) {
                logger.warn("No partitions found for topic: {}", topicName);
            } else {
                // Assign partitions to consumer
                consumer.assign(partitions);
                
                // Load initial cache from the beginning of the topic
                loadInitialCache();
            }
            
            // Start background polling
            running.set(true);
            pollExecutor.scheduleWithFixedDelay(
                    this::pollForUpdates,
                    1, // initial delay
                    1, // period
                    TimeUnit.SECONDS
            );
            
            logger.info("Initialized Kafka schema storage for topic: {}", topicName);
        } catch (Exception e) {
            throw new SchemaException(
                    "Failed to initialize schema storage",
                    topicName,
                    KafkaTopicSchemaStorageImpl.class,
                    e);
        }
    }
    
    @Override
    public void registerSchema(String schemaName, JsonSchema schema, int version) throws SchemaException {
        String key = createKey(schemaName, version);
        SchemaRecord record = new SchemaRecord(schemaName, version, schema, System.currentTimeMillis());
        
        try {
            byte[] value = serializeSchemaRecord(record);
            ProducerRecord<String, byte[]> producerRecord = new ProducerRecord<>(topicName, key, value);
            
            // Send with retries
            producer.send(producerRecord).get(10, TimeUnit.SECONDS);
            producer.flush();
            
            // Update cache
            cache.put(key, record);
            
            logger.debug("Registered schema {} version {}", schemaName, version);
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
    public void deleteSchema(String schemaName, int version) throws SchemaException {
        String key = createKey(schemaName, version);
        
        try {
            // Write tombstone record (null value)
            ProducerRecord<String, byte[]> tombstone = new ProducerRecord<>(topicName, key, null);
            producer.send(tombstone).get(10, TimeUnit.SECONDS);
            producer.flush();
            
            // Remove from cache
            cache.invalidate(key);
            
            logger.debug("Deleted schema {} version {}", schemaName, version);
        } catch (Exception e) {
            throw new SchemaException(
                    "Failed to delete schema",
                    schemaName,
                    SchemaRecord.class,
                    e);
        }
    }
    
    @Override
    public void close() {
        running.set(false);
        pollExecutor.shutdown();
        try {
            if (!pollExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                pollExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        producer.close();
        consumer.close();
        adminClient.close();
        
        logger.info("Closed Kafka schema storage");
    }
    
    /**
     * Creates the schema topic if it doesn't exist.
     */
    private void createTopicIfNeeded() {
        try {
            Map<String, String> topicConfigs = new HashMap<>();
            topicConfigs.put("cleanup.policy", "compact");
            topicConfigs.put("min.compaction.lag.ms", "0");
            topicConfigs.put("delete.retention.ms", "86400000"); // 1 day
            
            NewTopic newTopic = new NewTopic(topicName, 1, (short) replicationFactor)
                    .configs(topicConfigs);
            
            adminClient.createTopics(Collections.singletonList(newTopic)).all().get(30, TimeUnit.SECONDS);
            logger.info("Created schema topic: {}", topicName);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                logger.debug("Schema topic already exists: {}", topicName);
            } else {
                logger.error("Failed to create schema topic", e);
                throw new RuntimeException("Failed to create schema topic", e);
            }
        } catch (Exception e) {
            logger.error("Failed to create schema topic", e);
            throw new RuntimeException("Failed to create schema topic", e);
        }
    }
    
    /**
     * Loads the initial cache by reading all records from the topic.
     */
    private void loadInitialCache() {
        try {
            // Get assigned partitions
            Set<TopicPartition> assignedPartitions = consumer.assignment();
            
            if (assignedPartitions.isEmpty()) {
                logger.warn("No partitions assigned for topic: {}", topicName);
                return;
            }
            
            // Seek to beginning
            consumer.seekToBeginning(assignedPartitions);
            
            // Read all records
            boolean done = false;
            int recordCount = 0;
            int emptyPolls = 0;
            final int maxEmptyPolls = 3;
            
            while (!done) {
                ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    emptyPolls++;
                    if (emptyPolls >= maxEmptyPolls) {
                        done = true;
                    }
                } else {
                    emptyPolls = 0;
                    for (ConsumerRecord<String, byte[]> record : records) {
                        processRecord(record);
                        recordCount++;
                    }
                }
            }
            
            logger.info("Loaded {} schema records into cache", recordCount);
        } catch (Exception e) {
            logger.error("Failed to load initial cache", e);
            throw new RuntimeException("Failed to load initial cache", e);
        }
    }
    
    /**
     * Background polling to keep cache updated.
     */
    private void pollForUpdates() {
        if (!running.get()) {
            return;
        }
        
        try {
            ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
            for (ConsumerRecord<String, byte[]> record : records) {
                processRecord(record);
            }
        } catch (Exception e) {
            logger.error("Error during background polling", e);
        }
    }
    
    /**
     * Processes a single consumer record, updating the cache.
     */
    private void processRecord(ConsumerRecord<String, byte[]> record) {
        String key = record.key();
        byte[] value = record.value();
        
        if (value == null) {
            // Tombstone - remove from cache
            cache.invalidate(key);
            logger.trace("Removed schema from cache: {}", key);
        } else {
            try {
                SchemaRecord schemaRecord = deserializeSchemaRecord(value);
                cache.put(key, schemaRecord);
                logger.trace("Added schema to cache: {}", key);
            } catch (Exception e) {
                logger.error("Failed to deserialize schema record for key: {}", key, e);
            }
        }
    }
    
    /**
     * Creates a cache key from schema name and version.
     */
    private String createKey(String schemaName, int version) {
        return schemaName + "-v" + version;
    }
    
    /**
     * Serializes a SchemaRecord to bytes using JSON.
     */
    private byte[] serializeSchemaRecord(SchemaRecord record) throws Exception {
        // Create a simple map for serialization
        Map<String, Object> map = new HashMap<>();
        map.put("schemaName", record.schemaName());
        map.put("version", record.version());
        map.put("schema", record.schema().toString());
        map.put("registeredAt", record.registeredAt());
        
        return objectMapper.writeValueAsBytes(map);
    }
    
    /**
     * Deserializes bytes to a SchemaRecord using JSON.
     */
    private SchemaRecord deserializeSchemaRecord(byte[] bytes) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.readValue(bytes, Map.class);
        
        String schemaName = (String) map.get("schemaName");
        int version = ((Number) map.get("version")).intValue();
        String schemaStr = (String) map.get("schema");
        long registeredAt = ((Number) map.get("registeredAt")).longValue();
        
        JsonSchema schema = new JsonSchema(schemaStr);
        
        return new SchemaRecord(schemaName, version, schema, registeredAt);
    }
}
