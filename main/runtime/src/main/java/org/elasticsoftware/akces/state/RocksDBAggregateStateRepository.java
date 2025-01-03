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

import com.google.common.base.Charsets;
import com.google.common.primitives.Longs;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

public class RocksDBAggregateStateRepository implements AggregateStateRepository {
    private static final Logger log = LoggerFactory.getLogger(RocksDBAggregateStateRepository.class);
    private final TransactionDB db;
    private final File baseDir;
    // special key to store the kafka offset (long)
    private static final byte[] OFFSET = new byte[]{0x4f, 0x46, 0x46, 0x53, 0x45, 0x54};
    private final Map<String, RecordAndMetadata<AggregateStateRecord>> transactionStateRecordMap = new HashMap<>();
    private final String topicName;
    private final Serializer<ProtocolRecord> serializer;
    private final Deserializer<ProtocolRecord> deserializer;
    private boolean aggregateIdIsUUID = false;
    private boolean aggregateIdTypeCheckDone = false;
    private long lastOffset = ProduceResponse.INVALID_OFFSET;

    public RocksDBAggregateStateRepository(String baseDir,
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
        this.baseDir = new File(baseDir, partitionId);
        try {
            Files.createDirectories(this.baseDir.getParentFile().toPath());
            Files.createDirectories(this.baseDir.getAbsoluteFile().toPath());
            db = TransactionDB.open(options, transactionDBOptions, this.baseDir.getAbsolutePath());
            // see if we need to initialize the keyspaces
            // initialize the offset
            initializeOffset();
            log.info("RocksDB for partition {} initialized in folder {}", partitionId, this.baseDir.getAbsolutePath());
        } catch (IOException | RocksDBException e) {
            throw new AggregateStateRepositoryException("Error initializing RocksDB", e);
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
        } catch(RocksDBException e) {
            throw new AggregateStateRepositoryException("Error initializing offset", e);
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
    public void prepare(AggregateStateRecord record, Future<RecordMetadata> recordMetadataFuture) {
        checkAggregateIdType(record.aggregateId());
        transactionStateRecordMap.put(record.aggregateId(), new RecordAndMetadata(record, recordMetadataFuture));
    }

    @Override
    public void commit() {
        if(!transactionStateRecordMap.isEmpty()) {
            // start writing the transactions (no need to resolve the futures just yet)
            Transaction transaction = db.beginTransaction(new WriteOptions());
            try {
                for (RecordAndMetadata recordAndMetadata : transactionStateRecordMap.values()) {
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
                throw new AggregateStateRepositoryException("Error committing records", e);
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
        if(offset > lastOffset) {
            Transaction transaction = db.beginTransaction(new WriteOptions());
            try {
                for (ConsumerRecord<String, ProtocolRecord> consumerRecord : consumerRecords) {
                    transaction.put(keyBytes(consumerRecord.key()), serializer.serialize(topicName, consumerRecord.value()));
                }
                transaction.put(OFFSET, Longs.toByteArray(offset));
                transaction.commit();
                transaction.close();
                updateOffset(offset);
            } catch (RocksDBException e) {
                throw new AggregateStateRepositoryException("Error processing records", e);
            }
        }
    }

    @Override
    public AggregateStateRecord get(String aggregateId) {
        checkAggregateIdType(aggregateId);
        // return from the transaction map if present
        if(transactionStateRecordMap.containsKey(aggregateId)) {
            return transactionStateRecordMap.get(aggregateId).record();
        } else {
            try {
                return (AggregateStateRecord) deserializer.deserialize(topicName, db.get(keyBytes(aggregateId)));
            } catch (RocksDBException | SerializationException e) {
                throw new AggregateStateRepositoryException("Problem reading record with aggregateId " + aggregateId, e);
            }
        }
    }

    private byte[] keyBytes(String aggregateId) {
        if (aggregateIdIsUUID) {
            UUID aggregateUUID = UUID.fromString(aggregateId);
            return ByteBuffer.wrap(new byte[16]).putLong(aggregateUUID.getMostSignificantBits()).putLong(aggregateUUID.getLeastSignificantBits()).array();
        } else
            return aggregateId.getBytes(Charsets.UTF_8);
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
