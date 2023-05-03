package org.elasticsoftware.akces.state;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;

import java.util.concurrent.Future;

public record RecordAndMetadata(AggregateStateRecord record, Future<RecordMetadata> metadata) {
}
