package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.commands.CreateAggregateCommandHandlerFunction;
import org.elasticsoftware.akces.events.CreateAggregateEventSourcingHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;

public interface AggregateBuilder<S extends AggregateState> {
    <C extends Command> AggregateBuilder<S> withCommandHandler(String name,
                                                               int version,
                                                               Class<C> commandClass,
                                                               CommandHandlerFunction<S, C, ?> handlerFunction);

    <C extends Command> AggregateBuilder<S> withCreateCommandHandler(String name,
                                                                     int version,
                                                                     Class<C> commandClass,
                                                                     CreateAggregateCommandHandlerFunction<C, ?> handlerFunction);

    <E extends DomainEvent> AggregateBuilder<S> withEventSourcedEventHandler(String name,
                                                                             int version,
                                                                             Class<E> eventClass,
                                                                             EventSourcingHandlerFunction<S, E> handlerFunction);

    <E extends DomainEvent> AggregateBuilder<S> withEventSourcedCreateEventHandler(String name,
                                                                                   int version,
                                                                                   Class<E> eventClass,
                                                                                   CreateAggregateEventSourcingHandlerFunction<S, E> handlerFunction);

    AggregateRuntime build();
}
