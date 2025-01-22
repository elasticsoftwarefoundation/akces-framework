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
import org.apache.kafka.common.requests.ProduceResponse;
import org.elasticsoftware.akces.kafka.RecordAndMetadata;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

public class InMemoryAggregateStateRepository implements AggregateStateRepository {
    private static final Logger log = LoggerFactory.getLogger(InMemoryAggregateStateRepository.class);
    private final Map<String, AggregateStateRecord> stateRecordMap = new HashMap<>();
    private final Map<String, RecordAndMetadata<AggregateStateRecord>> transactionStateRecordMap = new HashMap<>();
    private long offset = -1L;

    @Override
    public void close() {
        stateRecordMap.clear();
        transactionStateRecordMap.clear();
    }

    @Override
    public void prepare(AggregateStateRecord record, Future<RecordMetadata> recordMetadataFuture) {
        transactionStateRecordMap.put(record.aggregateId(), new RecordAndMetadata(record, recordMetadataFuture));
    }

    @Override
    public void commit() {
        // commit is always called, even if there are no state updates
        if (!transactionStateRecordMap.isEmpty()) {
            // now we need to find the highest offset in this batch
            this.offset = transactionStateRecordMap.values().stream()
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
            log.trace("Committing {} records and offset {}", transactionStateRecordMap.size(), this.offset);
            transactionStateRecordMap.values().forEach(recordAndMetadata -> stateRecordMap.put(recordAndMetadata.record().aggregateId(), recordAndMetadata.record()));
            transactionStateRecordMap.clear();
        }
    }

    @Override
    public void rollback() {
        transactionStateRecordMap.clear();
    }

    @Override
    public void process(List<ConsumerRecord<String, ProtocolRecord>> consumerRecords) {
        for (ConsumerRecord<String, ProtocolRecord> consumerRecord : consumerRecords) {
            AggregateStateRecord record = (AggregateStateRecord) consumerRecord.value();
            if (record != null) {
                stateRecordMap.put(record.aggregateId(), record);
            } else {
                stateRecordMap.remove(consumerRecord.key());
            }
            this.offset = consumerRecord.offset();
        }
    }

    @Override
    public AggregateStateRecord get(String aggregateId) {
        // if we have one in the transactional map, that one is the most recent
        if (transactionStateRecordMap.containsKey(aggregateId)) {
            return transactionStateRecordMap.get(aggregateId).record();
        } else {
            return stateRecordMap.get(aggregateId);
        }
    }

    @Override
    public long getOffset() {
        return offset;
    }
}
