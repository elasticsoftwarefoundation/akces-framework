package org.elasticsoftware.akces.state;

import org.elasticsoftware.akces.protocol.AggregateStateRecord;

import java.util.HashMap;
import java.util.Map;

public class InMemoryAggregateStateRepository implements AggregateStateRepository {
    private final Map<String,AggregateStateRecord> stateRecordMap = new HashMap<>();
    private final Map<String,AggregateStateRecord> transactionStateRecordMap = new HashMap<>();
    private long offset = -1L;

    @Override
    public void prepare(AggregateStateRecord record) {
        transactionStateRecordMap.put(record.aggregateId(), record);
    }

    @Override
    public void commit() {
        stateRecordMap.putAll(transactionStateRecordMap);
        transactionStateRecordMap.clear();
    }

    @Override
    public void rollback() {
        transactionStateRecordMap.clear();
    }

    @Override
    public void add(AggregateStateRecord record, long offset) {
        stateRecordMap.put(record.aggregateId(), record);
        this.offset = offset;
    }

    @Override
    public void remove(String aggregateId, long offset) {
        stateRecordMap.remove(aggregateId);
        this.offset = offset;
    }

    @Override
    public AggregateStateRecord get(String aggregateId) {
        // if we have one in the transactional map, that one is the most recent
        return transactionStateRecordMap.getOrDefault(aggregateId, stateRecordMap.get(aggregateId));
    }

    @Override
    public long getOffset() {
        return offset;
    }
}
