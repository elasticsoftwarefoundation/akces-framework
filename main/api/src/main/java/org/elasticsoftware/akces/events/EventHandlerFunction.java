package org.elasticsoftware.akces.events;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.commands.Command;

import java.util.stream.Stream;

@FunctionalInterface
public interface EventHandlerFunction<S extends AggregateState,InputEvent extends DomainEvent, E extends DomainEvent> {
    @NotNull E apply(@NotNull InputEvent event, S state);
}
