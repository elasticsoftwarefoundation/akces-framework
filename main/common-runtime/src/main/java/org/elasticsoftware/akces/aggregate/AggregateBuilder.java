package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.commands.CreateAggregateCommandHandlerFunction;
import org.elasticsoftware.akces.events.CreateAggregateEventSourcingHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;

public class AggregateBuilder<S extends AggregateState> {
    private final String name;
    private final Class<S> stateClass;

    public AggregateBuilder(String name, Class<S> stateClass) {
        this.name = name;
        this.stateClass = stateClass;
    }

    public static <S extends AggregateState> AggregateBuilder<S> aggregate(String name, Class<S> stateClass) {
        return new AggregateBuilder<>(name, stateClass);
    }

    public <C extends Command> AggregateBuilder<S> withCommandHandler(String name,
                                                                      int version,
                                                                      Class<C> commandClass,
                                                                      CommandHandlerFunction<S,C,?> handlerFunction) {
        return this;
    }

    public <C extends Command> AggregateBuilder<S> withCreateCommandHandler(String name,
                                                            int version,
                                                            Class<C> commandClass,
                                                            CreateAggregateCommandHandlerFunction<C, ?> handlerFunction) {
        return this;
    }

    public <E extends DomainEvent> AggregateBuilder<S> withEventSourcedEventHandler(String name,
                                                                                    int version,
                                                                                    Class<E> eventClass,
                                                                                    EventSourcingHandlerFunction<S,E> handlerFunction) {
        return this;
    }

    public <E extends DomainEvent> AggregateBuilder<S> withEventSourcedCreateEventHandler(String name,
                                                                      int version,
                                                                      Class<E> eventClass,
                                                                      CreateAggregateEventSourcingHandlerFunction<S,E> handlerFunction) {
        return this;
    }

}