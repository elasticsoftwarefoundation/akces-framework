package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.commands.CreateAggregateCommandHandlerFunction;
import org.elasticsoftware.akces.events.CreateAggregateEventSourcingHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractAggregateBuilder<S extends AggregateState> implements AggregateBuilder<S> {
    protected final String name;
    protected final Class<S> stateClass;
    protected CreateAggregateCommandHandlerFunction<Command, DomainEvent> commandCreateHandler;
    protected CreateAggregateEventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler;
    protected Map<Class<?>,DomainEventType<?>> domainEvents = new HashMap<>();
    protected Map<String, List<CommandType<?>>> commandTypes = new HashMap<>();
    protected Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers = new HashMap<>();
    protected Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers = new HashMap<>();

    public AbstractAggregateBuilder(String name, Class<S> stateClass) {
        this.name = name;
        this.stateClass = stateClass;
    }

    @Override
    public <C extends Command> AbstractAggregateBuilder<S> withCommandHandler(String name,
                                                                              int version,
                                                                              Class<C> commandClass,
                                                                              CommandHandlerFunction<S, C, ?> handlerFunction) {
        CommandType<C> type = new CommandType<>(name, version, commandClass, false);
        commandHandlers.put(type, (CommandHandlerFunction<AggregateState, Command, DomainEvent>) handlerFunction);
        commandTypes.computeIfAbsent(name, k -> new ArrayList<>()).add(type);
        return this;
    }

    @Override
    public <C extends Command> AbstractAggregateBuilder<S> withCreateCommandHandler(String name,
                                                                                    int version,
                                                                                    Class<C> commandClass,
                                                                                    CreateAggregateCommandHandlerFunction<C, ?> handlerFunction) {
        CommandType<C> type = new CommandType<>(name, version, commandClass, true);
        commandCreateHandler = (CreateAggregateCommandHandlerFunction<Command, DomainEvent>) handlerFunction;
        commandTypes.computeIfAbsent(name, k -> new ArrayList<>()).add(type);
        return this;
    }

    @Override
    public <E extends DomainEvent> AbstractAggregateBuilder<S> withEventSourcedEventHandler(String name,
                                                                                            int version,
                                                                                            Class<E> eventClass,
                                                                                            EventSourcingHandlerFunction<S, E> handlerFunction) {
        DomainEventType<E> type = new DomainEventType<>(name, version, eventClass, false, false);
        eventSourcingHandlers.put(type, (EventSourcingHandlerFunction<AggregateState, DomainEvent>) handlerFunction);
        domainEvents.put(eventClass, type);
        return this;
    }

    @Override
    public <E extends DomainEvent> AbstractAggregateBuilder<S> withEventSourcedCreateEventHandler(String name,
                                                                                                  int version,
                                                                                                  Class<E> eventClass,
                                                                                                  CreateAggregateEventSourcingHandlerFunction<S, E> handlerFunction) {
        DomainEventType<E> type = new DomainEventType<>(name, version, eventClass, true, false);
        createStateHandler = (CreateAggregateEventSourcingHandlerFunction<AggregateState, DomainEvent>) handlerFunction;
        domainEvents.put(eventClass, type);
        return this;
    }

}