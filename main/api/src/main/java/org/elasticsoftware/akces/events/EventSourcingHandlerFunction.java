package org.elasticsoftware.akces.events;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.DomainEventType;

@FunctionalInterface
public interface EventSourcingHandlerFunction<S extends AggregateState,E extends DomainEvent> {
    @NotNull S apply(@NotNull E event, S state);

    default DomainEventType<E> getEventType() {
        return null;
    }

    default Aggregate<S> getAggregate() {
        return null;
    }

    default boolean isCreate() {
        return false;
    }
}
