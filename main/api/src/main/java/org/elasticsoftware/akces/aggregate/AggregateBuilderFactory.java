package org.elasticsoftware.akces.aggregate;

public interface AggregateBuilderFactory {
    <S extends AggregateState> AggregateBuilder<S> create(Aggregate<S> aggregate);
}
