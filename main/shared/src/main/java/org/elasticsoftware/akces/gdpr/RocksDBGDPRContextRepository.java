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

package org.elasticsoftware.akces.gdpr;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.primitives.Longs;
import jakarta.annotation.Nonnull;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.elasticsoftware.akces.protocol.GDPRKeyRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.kafka.RecordAndMetadata;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

public class RocksDBGDPRContextRepository implements GDPRContextRepository {
    private static final Logger log = LoggerFactory.getLogger(RocksDBGDPRContextRepository.class);
    // special key to store the kafka offset (long)
    private static final byte[] OFFSET = new byte[]{0x4f, 0x46, 0x46, 0x53, 0x45, 0x54};
    private final TransactionDB db;
    private final File rocksDBDataDir;
    private final Map<String, RecordAndMetadata<GDPRKeyRecord>> transactionStateRecordMap = new HashMap<>();
    private final String topicName;
    private final Serializer<ProtocolRecord> serializer;
    private final Deserializer<ProtocolRecord> deserializer;
    private boolean aggregateIdIsUUID = false;
    private boolean aggregateIdTypeCheckDone = false;
    private final LoadingCache<String, GDPRContext> gdprContexts = Caffeine.newBuilder()
            .maximumSize(1000)
            .build(this::createGDPRContext);
    private long lastOffset = ProduceResponse.INVALID_OFFSET;

    public RocksDBGDPRContextRepository(String baseDir,
                                        String partitionId,
                                        String topicName,
                                        Serializer<ProtocolRecord> serializer,
                                        Deserializer<ProtocolRecord> deserializer) {
        this.topicName = topicName;
        this.serializer = serializer;
        this.deserializer = deserializer;
        RocksDB.loadLibrary();
        final Options options = new Options();
        final TransactionDBOptions transactionDBOptions = new TransactionDBOptions();
        options.setCreateIfMissing(true);
        this.rocksDBDataDir = new File(baseDir, partitionId);
        try {
            Files.createDirectories(this.rocksDBDataDir.getParentFile().toPath());
            Files.createDirectories(this.rocksDBDataDir.getAbsoluteFile().toPath());
            db = TransactionDB.open(options, transactionDBOptions, this.rocksDBDataDir.getAbsolutePath());
            // see if we need to initialize the keyspaces
            // initialize the offset
            initializeOffset();
            log.info("RocksDBGDPRContextRepository for partition {} initialized in folder {}", partitionId, this.rocksDBDataDir.getAbsolutePath());
        } catch (IOException | RocksDBException e) {
            throw new GDPRContextRepositoryException("Error initializing RocksDB", e);
        }
    }

    @Override
    public void close() {
        try {
            db.syncWal();
        } catch (RocksDBException e) {
            log.error("Error syncing WAL. Exception: '{}', message: '{}'", e.getCause(), e.getMessage(), e);
        }
        db.close();
    }

    private void initializeOffset() {
        try {
            byte[] offsetBytes = db.get(OFFSET);
            if (offsetBytes != null) {
                lastOffset = Longs.fromByteArray(offsetBytes);
            }
        } catch (RocksDBException e) {
            throw new GDPRContextRepositoryException("Error initializing offset", e);
        }
    }

    private void updateOffset(long offset) {
        this.lastOffset = offset;
        log.trace("Updated offset to {}", offset);
    }

    @Override
    public long getOffset() {
        return lastOffset;
    }

    @Override
    public void prepare(GDPRKeyRecord record, Future<RecordMetadata> recordMetadataFuture) {
        checkAggregateIdType(record.aggregateId());
        transactionStateRecordMap.put(record.aggregateId(), new RecordAndMetadata<>(record, recordMetadataFuture));
    }

    @Override
    public void commit() {
        if (!transactionStateRecordMap.isEmpty()) {
            // start writing the transactions (no need to resolve the futures just yet)
            Transaction transaction = db.beginTransaction(new WriteOptions());
            try {
                for (RecordAndMetadata<?> recordAndMetadata : transactionStateRecordMap.values()) {
                    transaction.put(keyBytes(recordAndMetadata.record().aggregateId()), serializer.serialize(topicName, recordAndMetadata.record()));
                }
                // now we need to find the highest offset in this batch
                long offset = transactionStateRecordMap.values().stream()
                        .map(RecordAndMetadata::metadata)
                        .map(recordMetadataFuture -> {
                            try {
                                return recordMetadataFuture.get();
                            } catch (Exception e) {
                                log.error("Error getting offset. Exception: '{}', message: '{}'", e.getCause(), e.getMessage(), e);
                                return null;
                            }
                        })
                        .map(recordMetadata -> recordMetadata != null ? recordMetadata.offset() : ProduceResponse.INVALID_OFFSET)
                        .max(Long::compareTo).orElse(ProduceResponse.INVALID_OFFSET);
                transaction.put(OFFSET, Longs.toByteArray(offset));
                transaction.commit();
                transaction.close();
                updateOffset(offset);
            } catch (RocksDBException e) {
                throw new GDPRContextRepositoryException("Error committing records", e);
            } finally {
                transactionStateRecordMap.clear();
            }
        }
    }

    @Override
    public void rollback() {
        transactionStateRecordMap.clear();
    }

    @Override
    public void process(List<ConsumerRecord<String, ProtocolRecord>> consumerRecords) {
        // optimize for replays
        long offset = consumerRecords.stream()
                .map(ConsumerRecord::offset)
                .max(Long::compareTo).orElse(ProduceResponse.INVALID_OFFSET);
        if (offset > lastOffset) {
            Transaction transaction = db.beginTransaction(new WriteOptions());
            try {
                for (ConsumerRecord<String, ProtocolRecord> consumerRecord : consumerRecords) {
                    if(consumerRecord.value() != null) {
                        // write
                        transaction.put(keyBytes(consumerRecord.key()), serializer.serialize(topicName, consumerRecord.value()));
                    } else {
                        // record was removed because the Aggregate needs to be forgotten. remove the key
                        transaction.delete(keyBytes(consumerRecord.key()));
                    }
                }
                transaction.put(OFFSET, Longs.toByteArray(offset));
                transaction.commit();
                transaction.close();
                // invalidate any cached entries
                consumerRecords.forEach(consumerRecord -> gdprContexts.invalidate(consumerRecord.key()));
                updateOffset(offset);
            } catch (RocksDBException e) {
                throw new GDPRContextRepositoryException("Error processing records", e);
            }
        }
    }

    @Override
    public boolean exists(String aggregateId) {
        return transactionStateRecordMap.containsKey(aggregateId) || db.keyExists(keyBytes(aggregateId));
    }

    @Override
    @Nonnull
    public GDPRContext get(String aggregateId) {
        return gdprContexts.get(aggregateId);
    }

    private GDPRContext createGDPRContext(String aggregateId) {
        GDPRKeyRecord record = getGDPRKeyRecord(aggregateId);
        if (record != null) {
            checkAggregateIdType(aggregateId);
            return new EncryptingGDPRContext(record.aggregateId(), record.payload(), aggregateIdIsUUID);
        } else {
            return new NoopGDPRContext(aggregateId);
        }
    }

    private GDPRKeyRecord getGDPRKeyRecord(String aggregateId) {
        checkAggregateIdType(aggregateId);
        // return from the transaction map if present
        if (transactionStateRecordMap.containsKey(aggregateId)) {
            return transactionStateRecordMap.get(aggregateId).record();
        } else {
            byte[] keyBytes = keyBytes(aggregateId);
            if (db.keyExists(keyBytes)) {
                try {
                    return (GDPRKeyRecord) deserializer.deserialize(topicName, db.get(keyBytes));
                } catch (RocksDBException | SerializationException e) {
                    throw new GDPRContextRepositoryException("Problem reading record with aggregateId " + aggregateId, e);
                }
            } else {
                return null;
            }
        }
    }

    private byte[] keyBytes(String aggregateId) {
        if (aggregateIdIsUUID) {
            UUID aggregateUUID = UUID.fromString(aggregateId);
            return ByteBuffer.wrap(new byte[16]).putLong(aggregateUUID.getMostSignificantBits()).putLong(aggregateUUID.getLeastSignificantBits()).array();
        } else
            return aggregateId.getBytes(StandardCharsets.UTF_8);
    }

    private void checkAggregateIdType(String aggregateId) {
        if (!aggregateIdTypeCheckDone) {
            try {
                UUID.fromString(aggregateId);
                aggregateIdIsUUID = true;
            } catch (IllegalArgumentException e) {
                aggregateIdIsUUID = false;
            }
            aggregateIdTypeCheckDone = true;
        }
    }

}
