package org.elasticsoftware.akces.state;

import org.elasticsoftware.akces.protocol.AggregateStateRecord;

public interface AggregateStateRepository {
    default long getOffset() {
        return -1L;
    }

    void prepare(AggregateStateRecord record);

    void commit(AggregateStateRecord record, long offset);

    void add(AggregateStateRecord record, long offset);

    void remove(AggregateStateRecord record, long offset);

    AggregateStateRecord get(String aggregateId);
}
