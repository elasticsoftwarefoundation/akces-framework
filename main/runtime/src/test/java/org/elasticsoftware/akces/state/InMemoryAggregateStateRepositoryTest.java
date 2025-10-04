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

package org.elasticsoftware.akces.state;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryAggregateStateRepositoryTest {

    @Test
    void testGetWhenEmpty() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        AggregateStateRecord result = repository.get("nonexistent");
        assertNull(result);
    }

    @Test
    void testPrepareAndCommit() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        
        AggregateStateRecord record = createStateRecord("id1");
        Future<RecordMetadata> metadata = createMetadataFuture(10);
        
        repository.prepare(record, metadata);
        repository.commit();
        
        AggregateStateRecord result = repository.get("id1");
        assertNotNull(result);
        assertEquals("id1", result.aggregateId());
        assertEquals(10, repository.getOffset());
    }

    @Test
    void testGetFromTransactionMap() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        
        AggregateStateRecord record = createStateRecord("id1");
        Future<RecordMetadata> metadata = createMetadataFuture(5);
        
        repository.prepare(record, metadata);
        
        // Should get from transaction map before commit
        AggregateStateRecord result = repository.get("id1");
        assertNotNull(result);
        assertEquals("id1", result.aggregateId());
    }

    @Test
    void testRollback() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        
        AggregateStateRecord record = createStateRecord("id1");
        Future<RecordMetadata> metadata = createMetadataFuture(5);
        
        repository.prepare(record, metadata);
        repository.rollback();
        
        // After rollback, should not find the record
        AggregateStateRecord result = repository.get("id1");
        assertNull(result);
    }

    @Test
    void testMultiplePrepareAndCommit() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        
        AggregateStateRecord record1 = createStateRecord("id1");
        Future<RecordMetadata> metadata1 = createMetadataFuture(10);
        repository.prepare(record1, metadata1);
        
        AggregateStateRecord record2 = createStateRecord("id2");
        Future<RecordMetadata> metadata2 = createMetadataFuture(15);
        repository.prepare(record2, metadata2);
        
        repository.commit();
        
        assertNotNull(repository.get("id1"));
        assertNotNull(repository.get("id2"));
        assertEquals(15, repository.getOffset()); // Should be the max offset
    }

    @Test
    void testProcess() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        
        List<ConsumerRecord<String, ProtocolRecord>> records = new ArrayList<>();
        AggregateStateRecord stateRecord = createStateRecord("id1");
        ConsumerRecord<String, ProtocolRecord> consumerRecord = 
            new ConsumerRecord<>("test-topic", 0, 20, "id1", stateRecord);
        records.add(consumerRecord);
        
        repository.process(records);
        
        assertNotNull(repository.get("id1"));
        assertEquals(20, repository.getOffset());
    }

    @Test
    void testProcessWithNullValue() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        
        // First add a record
        AggregateStateRecord stateRecord = createStateRecord("id1");
        Future<RecordMetadata> metadata = createMetadataFuture(10);
        repository.prepare(stateRecord, metadata);
        repository.commit();
        assertNotNull(repository.get("id1"));
        
        // Now process a null value to delete it
        List<ConsumerRecord<String, ProtocolRecord>> records = new ArrayList<>();
        ConsumerRecord<String, ProtocolRecord> consumerRecord = 
            new ConsumerRecord<>("test-topic", 0, 25, "id1", null);
        records.add(consumerRecord);
        
        repository.process(records);
        
        assertNull(repository.get("id1"));
        assertEquals(25, repository.getOffset());
    }

    @Test
    void testClose() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        
        AggregateStateRecord record = createStateRecord("id1");
        Future<RecordMetadata> metadata = createMetadataFuture(10);
        repository.prepare(record, metadata);
        repository.commit();
        
        assertNotNull(repository.get("id1"));
        
        repository.close();
        
        // After close, should be empty
        assertNull(repository.get("id1"));
    }

    @Test
    void testCommitWithoutPrepare() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        
        // Should handle commit without any prepared records
        repository.commit();
        
        assertEquals(-1, repository.getOffset());
    }

    @Test
    void testInitialOffset() {
        InMemoryAggregateStateRepository repository = new InMemoryAggregateStateRepository();
        assertEquals(-1, repository.getOffset());
    }

    private AggregateStateRecord createStateRecord(String aggregateId) {
        return new AggregateStateRecord(
            "AKCES",
            "TestAggregate",
            1,
            new byte[]{1, 2, 3},
            PayloadEncoding.JSON,
            aggregateId,
            UUID.randomUUID().toString(),
            1
        );
    }

    private Future<RecordMetadata> createMetadataFuture(long offset) {
        RecordMetadata metadata = new RecordMetadata(
            new TopicPartition("test-topic", 0),
            offset,
            0,
            System.currentTimeMillis(),
            16,
            100
        );
        return CompletableFuture.completedFuture(metadata);
    }
}
