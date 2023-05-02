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
