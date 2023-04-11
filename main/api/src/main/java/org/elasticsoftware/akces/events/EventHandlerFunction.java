package org.elasticsoftware.akces.events;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.DomainEventType;

@FunctionalInterface
public interface EventHandlerFunction<S extends AggregateState,InputEvent extends DomainEvent, E extends DomainEvent> {
    @NotNull E apply(@NotNull InputEvent event, S state);

    default DomainEventType<InputEvent> getEventType() {
        return null;
    }

    default Aggregate<S> getAggregate() {
        return null;
    }

    default boolean isCreate() {
        return false;
    }
}
