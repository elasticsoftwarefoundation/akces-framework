package org.elasticsoftware.akces.aggregate;

public interface Aggregate<S extends AggregateState> {
    default String getName() {
        return getClass().getSimpleName();
    }

    Class<S> getStateClass();
}
