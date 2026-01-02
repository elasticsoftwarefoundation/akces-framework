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

package org.elasticsoftware.akces.gdpr;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.protocol.GDPRKeyRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class InMemoryGDPRContextRepositoryTests {

    private InMemoryGDPRContextRepository repository;

    @BeforeEach
    public void setUp() {
        repository = new InMemoryGDPRContextRepository();
    }

    @AfterEach
    public void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    public void testInitialOffsetIsMinusOne() {
        assertEquals(-1L, repository.getOffset());
    }

    @Test
    public void testExistsReturnsFalseForNonExistentAggregateId() {
        assertFalse(repository.exists("non-existent-id"));
    }

    @Test
    public void testGetReturnsNoopContextForNonExistentAggregateId() {
        GDPRContext context = repository.get("non-existent-id");
        
        assertNotNull(context);
        assertInstanceOf(NoopGDPRContext.class, context);
        assertEquals("non-existent-id", context.aggregateId());
    }

    @Test
    public void testPrepareAndCommit() {
        String aggregateId = "test-aggregate-123";
        byte[] keyBytes = GDPRKeyUtils.createKey().getEncoded();
        GDPRKeyRecord record = new GDPRKeyRecord("TEST_TENANT", aggregateId, keyBytes);
        
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("gdpr-keys", 0), 100, 0, System.currentTimeMillis(), 0, 0);
        CompletableFuture<RecordMetadata> future = CompletableFuture.completedFuture(metadata);
        
        repository.prepare(record, future);
        repository.commit();
        
        assertTrue(repository.exists(aggregateId));
        assertEquals(100L, repository.getOffset());
    }

    @Test
    public void testGetReturnsEncryptingContextAfterCommit() {
        String aggregateId = "ef234add-e0df-4769-b5f4-612a3207bad3";
        byte[] keyBytes = GDPRKeyUtils.createKey().getEncoded();
        GDPRKeyRecord record = new GDPRKeyRecord("TEST_TENANT", aggregateId, keyBytes);
        
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("gdpr-keys", 0), 50, 0, System.currentTimeMillis(), 0, 0);
        CompletableFuture<RecordMetadata> future = CompletableFuture.completedFuture(metadata);
        
        repository.prepare(record, future);
        repository.commit();
        
        GDPRContext context = repository.get(aggregateId);
        
        assertNotNull(context);
        assertInstanceOf(EncryptingGDPRContext.class, context);
        assertEquals(aggregateId, context.aggregateId());
        
        // Test encryption/decryption works
        String encrypted = context.encrypt("test data");
        assertNotEquals("test data", encrypted);
        assertEquals("test data", context.decrypt(encrypted));
    }

    @Test
    public void testRollback() {
        String aggregateId = "rollback-test-id";
        byte[] keyBytes = GDPRKeyUtils.createKey().getEncoded();
        GDPRKeyRecord record = new GDPRKeyRecord("TEST_TENANT", aggregateId, keyBytes);
        
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("gdpr-keys", 0), 75, 0, System.currentTimeMillis(), 0, 0);
        CompletableFuture<RecordMetadata> future = CompletableFuture.completedFuture(metadata);
        
        repository.prepare(record, future);
        repository.rollback();
        
        assertFalse(repository.exists(aggregateId));
        assertEquals(-1L, repository.getOffset());
    }

    @Test
    public void testProcess() {
        String aggregateId = "process-test-id";
        byte[] keyBytes = GDPRKeyUtils.createKey().getEncoded();
        GDPRKeyRecord record = new GDPRKeyRecord("TEST_TENANT", aggregateId, keyBytes);
        
        ConsumerRecord<String, ProtocolRecord> consumerRecord = 
            new ConsumerRecord<>("gdpr-keys", 0, 200L, aggregateId, record);
        
        repository.process(List.of(consumerRecord));
        
        assertTrue(repository.exists(aggregateId));
        assertEquals(200L, repository.getOffset());
    }

    @Test
    public void testProcessWithNullRecord() {
        String aggregateId = "deleted-aggregate-id";
        byte[] keyBytes = GDPRKeyUtils.createKey().getEncoded();
        GDPRKeyRecord record = new GDPRKeyRecord("TEST_TENANT", aggregateId, keyBytes);
        
        // First add a record
        ConsumerRecord<String, ProtocolRecord> addRecord = 
            new ConsumerRecord<>("gdpr-keys", 0, 100L, aggregateId, record);
        repository.process(List.of(addRecord));
        assertTrue(repository.exists(aggregateId));
        
        // Then delete it with null value
        ConsumerRecord<String, ProtocolRecord> deleteRecord = 
            new ConsumerRecord<>("gdpr-keys", 0, 101L, aggregateId, null);
        repository.process(List.of(deleteRecord));
        
        assertFalse(repository.exists(aggregateId));
        assertEquals(101L, repository.getOffset());
    }

    @Test
    public void testMultipleCommits() {
        RecordMetadata metadata1 = new RecordMetadata(new TopicPartition("gdpr-keys", 0), 10, 0, System.currentTimeMillis(), 0, 0);
        RecordMetadata metadata2 = new RecordMetadata(new TopicPartition("gdpr-keys", 0), 20, 0, System.currentTimeMillis(), 0, 0);
        
        GDPRKeyRecord record1 = new GDPRKeyRecord("TEST_TENANT", "agg-1", GDPRKeyUtils.createKey().getEncoded());
        GDPRKeyRecord record2 = new GDPRKeyRecord("TEST_TENANT", "agg-2", GDPRKeyUtils.createKey().getEncoded());
        
        repository.prepare(record1, CompletableFuture.completedFuture(metadata1));
        repository.prepare(record2, CompletableFuture.completedFuture(metadata2));
        repository.commit();
        
        assertTrue(repository.exists("agg-1"));
        assertTrue(repository.exists("agg-2"));
        assertEquals(20L, repository.getOffset());  // Should be highest offset
    }

    @Test
    public void testClose() {
        String aggregateId = "close-test-id";
        byte[] keyBytes = GDPRKeyUtils.createKey().getEncoded();
        GDPRKeyRecord record = new GDPRKeyRecord("TEST_TENANT", aggregateId, keyBytes);
        
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("gdpr-keys", 0), 50, 0, System.currentTimeMillis(), 0, 0);
        repository.prepare(record, CompletableFuture.completedFuture(metadata));
        repository.commit();
        
        assertTrue(repository.exists(aggregateId));
        
        repository.close();
        
        // After close, repository should be empty
        assertFalse(repository.exists(aggregateId));
    }

    @Test
    public void testCommitWithoutPrepare() {
        // Should not throw exception
        repository.commit();
        assertEquals(-1L, repository.getOffset());
    }
}
