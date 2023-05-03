package org.elasticsoftware.akces.state;

import org.elasticsoftware.akces.aggregate.AggregateRuntime;

public class InMemoryAggregateStateRepositoryFactory implements AggregateStateRepositoryFactory {
    @Override
    public AggregateStateRepository create(AggregateRuntime aggregateRuntime, Integer partitionId) {
        return new InMemoryAggregateStateRepository();
    }
}
