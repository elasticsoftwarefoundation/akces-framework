package org.elasticsoftware.akces.commands;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.events.DomainEvent;

@FunctionalInterface
public interface CommandHandlerFunction<S extends AggregateState,C extends Command, E extends DomainEvent> {
    @NotNull E apply(@NotNull C command, S state);

    default boolean isCreate() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override isCreate()");
    }

    default CommandType<C> getCommandType() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getCommandType()");
    }

    default Aggregate<S> getAggregate() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getAggregate()");
    }
}
