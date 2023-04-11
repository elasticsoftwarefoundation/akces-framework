package org.elasticsoftware.akces.beans;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class CommandHandlerFunctionAdapter<S extends AggregateState,C extends Command, E extends DomainEvent>
        implements CommandHandlerFunction<S, C, E> {
    private final Aggregate<S> aggregate;
    private final String adapterMethodName;
    private final Class<C> commandClass;
    private final Class<S> stateClass;
    private Method adapterMethod;
    private final boolean create;
    private final CommandInfo commandInfo;

    public CommandHandlerFunctionAdapter(Aggregate<S> aggregate,
                                         String adapterMethodName,
                                         Class<C> commandClass,
                                         Class<S> stateClass,
                                         boolean create,
                                         CommandInfo commandInfo) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.commandClass = commandClass;
        this.stateClass = stateClass;
        this.create = create;
        this.commandInfo = commandInfo;
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            adapterMethod = aggregate.getClass().getMethod(adapterMethodName, commandClass, stateClass);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public E apply(C command, S state) {
        try {
            return (E) adapterMethod.invoke(aggregate, command, state);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            if(e.getCause() != null) {
                if(e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                } else {
                    throw new RuntimeException(e.getCause());
                }
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public boolean isCreate() {
        return create;
    }

    @Override
    public CommandType<C> getCommandType() {
        return new CommandType<>(commandInfo.type(), commandInfo.version(), commandClass, create);
    }

    @Override
    public Aggregate<S> getAggregate() {
        return aggregate;
    }
}
