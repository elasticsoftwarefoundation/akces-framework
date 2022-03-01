package org.elasticsoftware.akces.kafka;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateBuilder;
import org.elasticsoftware.akces.aggregate.AggregateBuilderFactory;
import org.elasticsoftware.akces.aggregate.AggregateState;

public final class KafkaAggregateBuilderFactory implements AggregateBuilderFactory {
    @Override
    public <S extends AggregateState> AggregateBuilder<S> create(Aggregate<S> aggregate) {
        return new KafkaAggregateBuilder<>(aggregate.getName(), aggregate.getStateClass());
    }
}
