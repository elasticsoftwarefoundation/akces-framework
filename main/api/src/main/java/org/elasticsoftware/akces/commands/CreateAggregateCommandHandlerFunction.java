package org.elasticsoftware.akces.commands;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.events.DomainEvent;

@FunctionalInterface
public interface CreateAggregateCommandHandlerFunction<C extends Command, E extends DomainEvent> {
    @NotNull E apply(@NotNull C command);
}
