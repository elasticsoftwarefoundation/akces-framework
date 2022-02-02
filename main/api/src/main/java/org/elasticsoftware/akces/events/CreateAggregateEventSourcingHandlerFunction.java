package org.elasticsoftware.akces.events;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.AggregateState;

@FunctionalInterface
public interface CreateAggregateEventSourcingHandlerFunction<S extends AggregateState,E extends DomainEvent> {
    @NotNull S apply(@NotNull E event);
}
