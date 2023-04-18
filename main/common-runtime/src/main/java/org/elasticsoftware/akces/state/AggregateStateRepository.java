package org.elasticsoftware.akces.state;

import org.elasticsoftware.akces.protocol.AggregateStateRecord;

public interface AggregateStateRepository {
    default long getOffset() {
        return -1L;
    }

    void prepare(AggregateStateRecord record);

    void commit();

    void rollback();

    void add(AggregateStateRecord record, long offset);

    void remove(String aggregateId, long offset);

    AggregateStateRecord get(String aggregateId);
}
