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
import org.elasticsoftware.akces.aggregate.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class EventSourcingHandlerFunctionAdapter<S extends AggregateState, E extends DomainEvent> implements EventSourcingHandlerFunction<S, E> {
    private final Aggregate<S> aggregate;
    private final String adapterMethodName;
    private final Class<E> domainEventClass;
    private final Class<S> stateClass;
    private final boolean create;
    private final DomainEventType<E> domainEventType;
    private Method adapterMethod;

    public EventSourcingHandlerFunctionAdapter(Aggregate<S> aggregate,
                                               String adapterMethodName,
                                               Class<E> domainEventClass,
                                               Class<S> stateClass,
                                               boolean create,
                                               String typeName,
                                               int version) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.domainEventClass = domainEventClass;
        this.stateClass = stateClass;
        this.create = create;
        this.domainEventType = new DomainEventType<>(typeName, version, domainEventClass, create, false, false);
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
    public @NotNull S apply(@NotNull E event, S state) {
        try {
            return (S) adapterMethod.invoke(aggregate, event, state);
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
    public DomainEventType<E> getEventType() {
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
}
