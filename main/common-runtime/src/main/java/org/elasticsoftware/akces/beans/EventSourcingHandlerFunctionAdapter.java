package org.elasticsoftware.akces.beans;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class EventSourcingHandlerFunctionAdapter<S extends AggregateState, E extends DomainEvent> implements EventSourcingHandlerFunction<S, E> {
    private final Aggregate<S> aggregate;
    private final String adapterMethodName;
    private final Class<E> domainEventClass;
    private final Class<S> stateClass;
    private Method adapterMethod;
    private final boolean create;
    private final DomainEventInfo domainEventInfo;

    public EventSourcingHandlerFunctionAdapter(Aggregate<S> aggregate,
                                               String adapterMethodName,
                                               Class<E> domainEventClass,
                                               Class<S> stateClass,
                                               boolean create,
                                               DomainEventInfo domainEventInfo) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.domainEventClass = domainEventClass;
        this.stateClass = stateClass;
        this.create = create;
        this.domainEventInfo = domainEventInfo;
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            adapterMethod = aggregate.getClass().getMethod(adapterMethodName, domainEventClass, stateClass);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public S apply(E event, S state) {
        try {
            return (S) adapterMethod.invoke(aggregate, event, state);
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
    public DomainEventType<E> getEventType() {
        return new DomainEventType<>(domainEventInfo.type(), domainEventInfo.version(), domainEventClass, create, false);
    }

    @Override
    public Aggregate<S> getAggregate() {
        return aggregate;
    }

    @Override
    public boolean isCreate() {
        return create;
    }
}
