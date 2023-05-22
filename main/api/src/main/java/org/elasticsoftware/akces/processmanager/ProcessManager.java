package org.elasticsoftware.akces.processmanager;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;

public interface ProcessManager<S extends AggregateState, P extends AkcesProcess> extends Aggregate<S> {
}
