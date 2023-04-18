package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.CommandBus;

public interface Aggregate<S extends AggregateState> {
    default String getName() {
        return getClass().getSimpleName();
    }

    Class<S> getStateClass();

    default CommandBus getCommandBus() {
        return CommandBusHolder.getCommandBus(getClass());
    }
}
