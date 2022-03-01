package org.elasticsoftware.akces.kafka;

import org.elasticsoftware.akces.aggregate.*;

public class KafkaAggregateBuilder<S extends AggregateState> extends AbstractAggregateBuilder<S> {

    public KafkaAggregateBuilder(String name, Class<S> stateClass) {
        super(name, stateClass);
    }

    @Override
    public AggregateRuntime build() {
        return new KafkaAggregateRuntime(
                new AggregateStateType<>(name, 1, stateClass),
                commandCreateHandler,
                createStateHandler,
                domainEvents,
                commandTypes,
                commandHandlers,
                eventSourcingHandlers);
    }
}
