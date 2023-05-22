package org.elasticsoftware.akces.beans;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.akces.events.EventHandlerFunction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

public class EventHandlerFunctionAdapter<S extends AggregateState,InputEvent extends DomainEvent, E extends DomainEvent> implements EventHandlerFunction<S,InputEvent,E> {
    private final Aggregate<S> aggregate;
    private final String adapterMethodName;
    private final Class<InputEvent> inputEventClass;
    private final Class<S> stateClass;
    private Method adapterMethod;
    private final boolean create;
    private final List<DomainEventType<?>> producedDomainEventTypes;
    private final List<DomainEventType<?>> errorEventTypes;
    private final DomainEventInfo domainEventInfo;

    public EventHandlerFunctionAdapter(Aggregate<S> aggregate,
                                       String adapterMethodName,
                                       Class<InputEvent> inputEventClass,
                                       Class<S> stateClass,
                                       boolean create,
                                       List<DomainEventType<?>> producedDomainEventTypes,
                                       List<DomainEventType<?>> errorEventTypes,
                                       DomainEventInfo domainEventInfo) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.inputEventClass = inputEventClass;
        this.stateClass = stateClass;
        this.create = create;
        this.producedDomainEventTypes = producedDomainEventTypes;
        this.errorEventTypes = errorEventTypes;
        this.domainEventInfo = domainEventInfo;
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            adapterMethod = aggregate.getClass().getMethod(adapterMethodName, inputEventClass, stateClass);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Stream<E> apply(InputEvent event, S state) {
        try {
            return (Stream<E>) adapterMethod.invoke(aggregate, event, state);
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
    public DomainEventType<InputEvent> getEventType() {
        return new DomainEventType<>(
                domainEventInfo.type(),
                domainEventInfo.version(),
                inputEventClass,
                create,
                true,
                ErrorEvent.class.isAssignableFrom(inputEventClass));
    }

    @Override
    public Aggregate<S> getAggregate() {
        return aggregate;
    }

    @Override
    public boolean isCreate() {
        return create;
    }

    @Override
    public List<DomainEventType<?>> getProducedDomainEventTypes() {
        return producedDomainEventTypes;
    }

    @Override
    public List<DomainEventType<?>> getErrorEventTypes() {
        return errorEventTypes;
    }
}
