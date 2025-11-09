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
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaTopicSchemaStorageImplTest {

    private Producer<String, SchemaRecord> producer;
    private Consumer<String, SchemaRecord> consumer;
    private Admin adminClient;
    private KafkaTopicSchemaStorageImpl storage;
    
    private static final String TOPIC_NAME = "test-schemas";
    private static final int REPLICATION_FACTOR = 1;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        producer = mock(Producer.class);
        consumer = mock(Consumer.class);
        adminClient = mock(Admin.class);
        
        // Setup admin client mock
        CreateTopicsResult createTopicsResult = mock(CreateTopicsResult.class);
        KafkaFuture<Void> future = KafkaFuture.completedFuture(null);
        when(createTopicsResult.all()).thenReturn(future);
        when(adminClient.createTopics(anyList())).thenReturn(createTopicsResult);
        
        // Setup consumer mocks
        List<PartitionInfo> partitionInfos = Collections.singletonList(
            new PartitionInfo(TOPIC_NAME, 0, null, null, null)
        );
        when(consumer.partitionsFor(TOPIC_NAME)).thenReturn(partitionInfos);
        when(consumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        
        storage = new KafkaTopicSchemaStorageImpl(
            TOPIC_NAME,
            producer,
            consumer,
            adminClient,
            REPLICATION_FACTOR
        );
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void testInitialize() {
        // When
        storage.initialize();
        
        // Then
        verify(adminClient).createTopics(argThat(topics -> {
            NewTopic topic = topics.iterator().next();
            return topic.name().equals(TOPIC_NAME) &&
                   topic.numPartitions() == 1 &&
                   topic.replicationFactor() == REPLICATION_FACTOR &&
                   topic.configs().get("cleanup.policy").equals("compact");
        }));
        verify(consumer).partitionsFor(TOPIC_NAME);
        verify(consumer).assign(anyList());
    }

    @Test
    void testRegisterSchema() throws Exception {
        // Given
        storage.initialize();
        String schemaName = "TestCommand";
        int version = 1;
        JsonSchema schema = new JsonSchema("{\"type\": \"object\"}");
        
        CompletableFuture<RecordMetadata> future = CompletableFuture.completedFuture(null);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        
        // When
        storage.registerSchema(schemaName, schema, version);
        
        // Then
        ArgumentCaptor<ProducerRecord<String, SchemaRecord>> captor = 
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture());
        verify(producer).flush();
        
        ProducerRecord<String, SchemaRecord> record = captor.getValue();
        assertEquals(TOPIC_NAME, record.topic());
        assertEquals("TestCommand-v1", record.key());
        assertNotNull(record.value());
    }

    @Test
    void testRegisterSchemaFailure() {
        // Given
        storage.initialize();
        String schemaName = "TestCommand";
        int version = 1;
        JsonSchema schema = new JsonSchema("{\"type\": \"object\"}");
        
        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka error"));
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        
        // When/Then
        assertThrows(SchemaException.class, 
            () -> storage.registerSchema(schemaName, schema, version));
    }

    @Test
    void testGetSchemaNotFound() throws Exception {
        // Given
        storage.initialize();
        
        // When
        Optional<SchemaRecord> result = storage.getSchema("NonExistent", 1);
        
        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetSchemas() throws Exception {
        // Given
        storage.initialize();
        String schemaName = "TestCommand";
        
        // Register multiple versions
        JsonSchema schema1 = new JsonSchema("{\"type\": \"object\", \"version\": 1}");
        JsonSchema schema2 = new JsonSchema("{\"type\": \"object\", \"version\": 2}");
        
        CompletableFuture<RecordMetadata> future = CompletableFuture.completedFuture(null);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        
        storage.registerSchema(schemaName, schema1, 1);
        storage.registerSchema(schemaName, schema2, 2);
        
        // When
        List<SchemaRecord> schemas = storage.getSchemas(schemaName);
        
        // Then
        assertEquals(2, schemas.size());
        assertEquals(1, schemas.get(0).version());
        assertEquals(2, schemas.get(1).version());
    }

    @Test
    void testGetSchemasEmpty() throws Exception {
        // Given
        storage.initialize();
        
        // When
        List<SchemaRecord> schemas = storage.getSchemas("NonExistent");
        
        // Then
        assertTrue(schemas.isEmpty());
    }

    @Test
    void testDeleteSchema() throws Exception {
        // Given
        storage.initialize();
        String schemaName = "TestCommand";
        int version = 1;
        JsonSchema schema = new JsonSchema("{\"type\": \"object\"}");
        
        CompletableFuture<RecordMetadata> future = CompletableFuture.completedFuture(null);
        when(producer.send(any(ProducerRecord.class))).thenReturn(future);
        
        // First register
        storage.registerSchema(schemaName, schema, version);
        
        // When
        storage.deleteSchema(schemaName, version);
        
        // Then
        ArgumentCaptor<ProducerRecord<String, SchemaRecord>> captor = 
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer, times(2)).send(captor.capture());
        
        // Second call should be the tombstone
        ProducerRecord<String, SchemaRecord> tombstone = captor.getAllValues().get(1);
        assertEquals("TestCommand-v1", tombstone.key());
        assertNull(tombstone.value());
        
        // Verify it's removed from cache
        Optional<SchemaRecord> result = storage.getSchema(schemaName, version);
        assertTrue(result.isEmpty());
    }

    @Test
    void testClose() {
        // Given
        storage.initialize();
        
        // When
        storage.close();
        
        // Then
        verify(producer).close();
        verify(consumer).close();
        verify(adminClient).close();
    }
}
