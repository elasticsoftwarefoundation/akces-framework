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

package org.elasticsoftware.akces.schemas.storage;

import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaTopicSchemaStorageTest {

    private ProducerFactory<String, SchemaRecord> producerFactory;
    private Producer<String, SchemaRecord> producer;
    private Consumer<String, SchemaRecord> consumer;
    private KafkaTopicSchemaStorage storage;
    
    private static final String TOPIC_NAME = "Akces-Schemas";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        producerFactory = mock(ProducerFactory.class);
        producer = mock(Producer.class);
        consumer = mock(Consumer.class);
        
        // Setup producer factory mock
        when(producerFactory.createProducer(anyString())).thenReturn(producer);
        
        // Setup consumer mocks
        List<PartitionInfo> partitionInfos = Collections.singletonList(
            new PartitionInfo(TOPIC_NAME, 0, null, null, null)
        );
        when(consumer.partitionsFor(TOPIC_NAME)).thenReturn(partitionInfos);
        when(consumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        when(consumer.assignment()).thenReturn(Collections.emptySet());
        
        storage = new KafkaTopicSchemaStorage(
            producerFactory,
            "testProducer",
            consumer
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
        
        // Then - verify that initialization sets up consumer properly
        verify(consumer).partitionsFor(TOPIC_NAME);
        verify(consumer).assign(anyList());
    }
    
    @Test
    void testProcess() {
        // Given
        storage.initialize();
        
        // Setup mock to return a schema record on poll
        SchemaRecord record = new SchemaRecord("TestCommand", 1, new JsonSchema("{\"type\":\"object\"}"), System.currentTimeMillis());
        ConsumerRecord<String, SchemaRecord> consumerRecord = new ConsumerRecord<>(
            TOPIC_NAME, 0, 0, "TestCommand-v1", record
        );
        ConsumerRecords<String, SchemaRecord> records = new ConsumerRecords<>(
            Map.of(new TopicPartition(TOPIC_NAME, 0), List.of(consumerRecord))
        );
        
        when(consumer.poll(any(Duration.class))).thenReturn(records, ConsumerRecords.empty());
        
        // When - call process to poll for updates
        storage.process();
        
        // Then - verify the record was added to cache
        Optional<SchemaRecord> cachedRecord = storage.getSchema("TestCommand", 1);
        assertTrue(cachedRecord.isPresent());
        assertEquals("TestCommand", cachedRecord.get().schemaName());
        assertEquals(1, cachedRecord.get().version());
    }

    @Test
    void testSaveSchema() throws Exception {
        // Given
        storage.initialize();
        String schemaName = "TestCommand";
        int version = 1;
        JsonSchema schema = new JsonSchema("{\"type\": \"object\"}");
        
        CompletableFuture<RecordMetadata> future = CompletableFuture.completedFuture(null);
        when(producer.send(any(ProducerRecord.class), any(Callback.class))).thenReturn(future);
        
        // When
        storage.saveSchema(schemaName, schema, version);
        
        // Then
        ArgumentCaptor<ProducerRecord<String, SchemaRecord>> captor = 
            ArgumentCaptor.forClass(ProducerRecord.class);
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(producer).beginTransaction();
        verify(producer).send(captor.capture(), callbackCaptor.capture());
        verify(producer).commitTransaction();
        verify(producer).close();
        
        ProducerRecord<String, SchemaRecord> record = captor.getValue();
        assertEquals(TOPIC_NAME, record.topic());
        assertEquals("TestCommand-v1", record.key());
        assertNotNull(record.value());
    }

    @Test
    void testSaveSchemaFailure() {
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
            () -> storage.saveSchema(schemaName, schema, version));
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
        ArgumentCaptor<Callback> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        when(producer.send(any(ProducerRecord.class), callbackCaptor.capture())).thenReturn(future);
        
        storage.saveSchema(schemaName, schema1, 1);
        storage.saveSchema(schemaName, schema2, 2);

        callbackCaptor.getAllValues().forEach(callback -> callback.onCompletion(null, null));
        
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
    void testClose() {
        // Given
        storage.initialize();
        
        // When
        storage.close();
        
        // Then
        // Producer is created on-demand in registerSchema, not stored as field
        // Only consumer is closed
        verify(consumer).close();
    }

    @Test
    void testReadOnlyModeWithNullProducer() {
        // Given - create storage with null producer (read-only mode)
        KafkaTopicSchemaStorage readOnlyStorage = new KafkaTopicSchemaStorage(
            null,
            null,
            consumer
        );
        
        try {
            readOnlyStorage.initialize();
            
            // When/Then - attempting to register should throw UnsupportedOperationException
            JsonSchema schema = new JsonSchema("{\"type\": \"object\"}");
            assertThrows(UnsupportedOperationException.class,
                () -> readOnlyStorage.saveSchema("TestCommand", schema, 1));
            
            // Should be able to read schemas
            Optional<SchemaRecord> result = readOnlyStorage.getSchema("NonExistent", 1);
            assertTrue(result.isEmpty());
            
            // Close should work with null producer - just verifying it doesn't throw
        } finally {
            readOnlyStorage.close();
        }
    }
}
