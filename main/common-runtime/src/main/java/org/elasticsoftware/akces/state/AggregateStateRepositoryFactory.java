package org.elasticsoftware.akces.state;

import org.elasticsoftware.akces.aggregate.AggregateRuntime;

public interface AggregateStateRepositoryFactory {
    AggregateStateRepository create(AggregateRuntime aggregateRuntime, Integer partitionId);
}
