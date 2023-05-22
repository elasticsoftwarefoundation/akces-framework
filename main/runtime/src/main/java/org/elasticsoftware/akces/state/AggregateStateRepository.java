/*
 * Copyright 2022 - 2023 The Original Authors
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
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.Future;

public interface AggregateStateRepository extends Closeable {
    default long getOffset() {
        return ProduceResponse.INVALID_OFFSET;
    }

    void prepare(AggregateStateRecord record, Future<RecordMetadata> recordMetadataFuture);

    void commit();

    void rollback();

    void process(List<ConsumerRecord<String, ProtocolRecord>> consumerRecords);

    AggregateStateRecord get(String aggregateId);
}
