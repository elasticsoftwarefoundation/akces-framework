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
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for KafkaTopicSchemaStorage using Testcontainers.
 */
@Testcontainers
class KafkaTopicSchemaStorageIntegrationTest {

    @Container
    private static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.3"))
            .withKraft();

    private static final String TOPIC_NAME = "test-schemas";
    private static final int REPLICATION_FACTOR = 1;

    private KafkaTopicSchemaStorageImpl storage;
    private Producer<String, byte[]> producer;
    private Consumer<String, byte[]> consumer;
    private AdminClient adminClient;

    @BeforeEach
    void setUp() {
        // Create Kafka producer
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        producer = new KafkaProducer<>(producerProps);

        // Create Kafka consumer
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumer = new KafkaConsumer<>(consumerProps);

        // Create admin client
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        adminClient = AdminClient.create(adminProps);

        // Create storage
        ObjectMapper objectMapper = new ObjectMapper();
        storage = new KafkaTopicSchemaStorageImpl(
                TOPIC_NAME,
                producer,
                consumer,
                adminClient,
                objectMapper,
                REPLICATION_FACTOR
        );

        storage.initialize();
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void testRegisterAndRetrieveSchema() throws Exception {
        // Given
        String schemaName = "TestCommand";
        int version = 1;
        JsonSchema schema = new JsonSchema("{\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"string\"}}}");

        // When
        storage.registerSchema(schemaName, schema, version);

        // Wait a bit for async processing
        Thread.sleep(500);

        // Then
        Optional<SchemaRecord> retrieved = storage.getSchema(schemaName, version);
        assertTrue(retrieved.isPresent());
        assertEquals(schemaName, retrieved.get().schemaName());
        assertEquals(version, retrieved.get().version());
        assertNotNull(retrieved.get().schema());
    }

    @Test
    void testRegisterMultipleVersions() throws Exception {
        // Given
        String schemaName = "TestEvent";
        JsonSchema schema1 = new JsonSchema("{\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"string\"}}}");
        JsonSchema schema2 = new JsonSchema("{\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"string\"}, \"name\": {\"type\": \"string\"}}}");

        // When
        storage.registerSchema(schemaName, schema1, 1);
        storage.registerSchema(schemaName, schema2, 2);

        // Wait a bit for async processing
        Thread.sleep(500);

        // Then
        List<SchemaRecord> schemas = storage.getSchemas(schemaName);
        assertEquals(2, schemas.size());
        assertEquals(1, schemas.get(0).version());
        assertEquals(2, schemas.get(1).version());
    }

    @Test
    void testDeleteSchema() throws Exception {
        // Given
        String schemaName = "TestCommand";
        int version = 1;
        JsonSchema schema = new JsonSchema("{\"type\": \"object\"}");

        storage.registerSchema(schemaName, schema, version);
        Thread.sleep(1000);

        // Verify it exists
        Optional<SchemaRecord> before = storage.getSchema(schemaName, version);
        assertTrue(before.isPresent());

        // When
        storage.deleteSchema(schemaName, version);
        Thread.sleep(2000);

        // Then
        Optional<SchemaRecord> after = storage.getSchema(schemaName, version);
        assertTrue(after.isEmpty());
    }

    @Test
    void testSchemaNotFound() throws Exception {
        // When
        Optional<SchemaRecord> result = storage.getSchema("NonExistent", 1);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetSchemasForNonExistentName() throws Exception {
        // When
        List<SchemaRecord> schemas = storage.getSchemas("NonExistent");

        // Then
        assertTrue(schemas.isEmpty());
    }

    @Test
    void testConcurrentRegistration() throws Exception {
        // Given
        String schemaName = "ConcurrentTest";
        int threadCount = 5;
        Thread[] threads = new Thread[threadCount];

        // When - register different versions concurrently
        for (int i = 0; i < threadCount; i++) {
            int version = i + 1;
            threads[i] = new Thread(() -> {
                try {
                    JsonSchema schema = new JsonSchema(
                            "{\"type\": \"object\", \"properties\": {\"version\": {\"const\": " + version + "}}}");
                    storage.registerSchema(schemaName, schema, version);
                } catch (Exception e) {
                    fail("Failed to register schema: " + e.getMessage());
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Wait for async processing
        Thread.sleep(1000);

        // Then
        List<SchemaRecord> schemas = storage.getSchemas(schemaName);
        assertEquals(threadCount, schemas.size());
    }

    @Test
    void testSchemaWithComplexStructure() throws Exception {
        // Given
        String schemaName = "ComplexSchema";
        int version = 1;
        String complexSchemaStr = """
                {
                  "type": "object",
                  "properties": {
                    "id": {"type": "string"},
                    "name": {"type": "string"},
                    "age": {"type": "integer"},
                    "address": {
                      "type": "object",
                      "properties": {
                        "street": {"type": "string"},
                        "city": {"type": "string"}
                      }
                    },
                    "tags": {
                      "type": "array",
                      "items": {"type": "string"}
                    }
                  },
                  "required": ["id", "name"]
                }
                """;
        JsonSchema schema = new JsonSchema(complexSchemaStr);

        // When
        storage.registerSchema(schemaName, schema, version);
        Thread.sleep(500);

        // Then
        Optional<SchemaRecord> retrieved = storage.getSchema(schemaName, version);
        assertTrue(retrieved.isPresent());
        assertEquals(schemaName, retrieved.get().schemaName());
        assertNotNull(retrieved.get().schema());
    }
}
