package org.elasticsoftware.akces.commands;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.events.DomainEvent;

@FunctionalInterface
public interface CommandHandlerFunction<S extends AggregateState,C extends Command, E extends DomainEvent> {
    @NotNull E apply(@NotNull C command, S state);
}
