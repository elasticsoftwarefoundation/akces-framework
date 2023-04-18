package org.elasticsoftware.akces.events;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.DomainEventType;

@FunctionalInterface
public interface EventSourcingHandlerFunction<S extends AggregateState,E extends DomainEvent> {
    @NotNull S apply(@NotNull E event, S state);

    default DomainEventType<E> getEventType() {
        throw new UnsupportedOperationException("When implementing EventSourcingHandlerFunction directly, you must override getEventType()");
    }

    default Aggregate<S> getAggregate() {
        throw new UnsupportedOperationException("When implementing EventSourcingHandlerFunction directly, you must override getAggregate()");
    }

    default boolean isCreate() {
        throw new UnsupportedOperationException("When implementing EventSourcingHandlerFunction directly, you must override isCreate()");
    }
}
