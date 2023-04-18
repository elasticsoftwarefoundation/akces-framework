package org.elasticsoftware.akces.events;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.DomainEventType;

@FunctionalInterface
public interface EventHandlerFunction<S extends AggregateState,InputEvent extends DomainEvent, E extends DomainEvent> {
    @NotNull E apply(@NotNull InputEvent event, S state);

    default DomainEventType<InputEvent> getEventType() {
        throw new UnsupportedOperationException("When implementing EventHandlerFunction directly, you must override getEventType()");
    }

    default Aggregate<S> getAggregate() {
        throw new UnsupportedOperationException("When implementing EventHandlerFunction directly, you must override getAggregate()");
    }

    default boolean isCreate() {
        throw new UnsupportedOperationException("When implementing EventHandlerFunction directly, you must override isCreate()");
    }
}
