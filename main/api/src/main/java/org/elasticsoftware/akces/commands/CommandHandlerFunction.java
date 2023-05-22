package org.elasticsoftware.akces.commands;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.events.DomainEvent;

import java.util.List;
import java.util.stream.Stream;

@FunctionalInterface
public interface CommandHandlerFunction<S extends AggregateState,C extends Command, E extends DomainEvent> {
    @NotNull Stream<E> apply(@NotNull C command, S state);

    default boolean isCreate() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override isCreate()");
    }

    default CommandType<C> getCommandType() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getCommandType()");
    }

    default Aggregate<S> getAggregate() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getAggregate()");
    }

    default List<DomainEventType<?>> getProducedDomainEventTypes() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getProducedDomainEventTypes()");
    }

    default List<DomainEventType<?>> getErrorEventTypes() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getErrorEventTypes()");
    }

    default CommandBus getCommandBus() {
        return CommandBusHolder.getCommandBus(getAggregate().getClass());
    }
}
