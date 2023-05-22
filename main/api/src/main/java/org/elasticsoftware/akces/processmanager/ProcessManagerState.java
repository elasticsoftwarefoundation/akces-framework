package org.elasticsoftware.akces.processmanager;

import org.elasticsoftware.akces.aggregate.AggregateState;

public interface ProcessManagerState<P extends AkcesProcess> extends AggregateState {
    P getAkcesProcess(String processId) throws UnknownAkcesProcessException;
}
