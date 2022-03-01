package org.elasticsoftware.akces.aggregate;

public interface Aggregate<S extends AggregateState> {
    String getName();

    Class<S> getStateClass();

    AggregateBuilder<S> configure(AggregateBuilder<S> builder);
}
