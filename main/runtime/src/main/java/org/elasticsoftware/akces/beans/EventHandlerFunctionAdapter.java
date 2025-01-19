/*
 * Copyright 2022 - 2025 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces.beans;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.aggregate.EventHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

public class EventHandlerFunctionAdapter<S extends AggregateState, InputEvent extends DomainEvent, E extends DomainEvent> implements EventHandlerFunction<S, InputEvent, E> {
    private final Aggregate<S> aggregate;
    private final String adapterMethodName;
    private final Class<InputEvent> inputEventClass;
    private final Class<S> stateClass;
    private final boolean create;
    private final List<DomainEventType<E>> producedDomainEventTypes;
    private final List<DomainEventType<E>> errorEventTypes;
    private final DomainEventType<InputEvent> domainEventType;
    private Method adapterMethod;

    public EventHandlerFunctionAdapter(Aggregate<S> aggregate,
                                       String adapterMethodName,
                                       Class<InputEvent> inputEventClass,
                                       Class<S> stateClass,
                                       boolean create,
                                       List<DomainEventType<E>> producedDomainEventTypes,
                                       List<DomainEventType<E>> errorEventTypes,
                                       String typeName,
                                       int version) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.inputEventClass = inputEventClass;
        this.stateClass = stateClass;
        this.create = create;
        this.producedDomainEventTypes = producedDomainEventTypes;
        this.errorEventTypes = errorEventTypes;
        this.domainEventType = new DomainEventType<>(
                typeName,
                version,
                inputEventClass,
                create,
                true,
                ErrorEvent.class.isAssignableFrom(inputEventClass));
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
    public Stream<E> apply(@NotNull InputEvent event, S state) {
        try {
            return (Stream<E>) adapterMethod.invoke(aggregate, event, state);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() != null) {
                if (e.getCause() instanceof RuntimeException) {
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
        return domainEventType;
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
    public List<DomainEventType<E>> getProducedDomainEventTypes() {
        return producedDomainEventTypes;
    }

    @Override
    public List<DomainEventType<E>> getErrorEventTypes() {
        return errorEventTypes;
    }
}
