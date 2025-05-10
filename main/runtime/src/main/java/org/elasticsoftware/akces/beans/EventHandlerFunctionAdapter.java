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

import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.aggregate.EventHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.util.List;
import java.util.stream.Stream;

public class EventHandlerFunctionAdapter<S extends AggregateState, InputEvent extends DomainEvent, E extends DomainEvent> implements EventHandlerFunction<S, InputEvent, E> {
    private final Aggregate<S> aggregate;
    private final String adapterMethodName;
    private final Class<S> stateClass;
    private final List<DomainEventType<E>> producedDomainEventTypes;
    private final List<DomainEventType<E>> errorEventTypes;
    private final DomainEventType<InputEvent> domainEventType;
    private MethodHandle methodHandle;

    public EventHandlerFunctionAdapter(Aggregate<S> aggregate,
                                       String adapterMethodName,
                                       DomainEventType<InputEvent> inputEventType,
                                       Class<S> stateClass,
                                       List<DomainEventType<E>> producedDomainEventTypes,
                                       List<DomainEventType<E>> errorEventTypes) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.stateClass = stateClass;
        this.producedDomainEventTypes = producedDomainEventTypes;
        this.errorEventTypes = errorEventTypes;
        this.domainEventType = inputEventType;
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            methodHandle = MethodHandles.lookup().findVirtual(
                    aggregate.getClass(),
                    adapterMethodName,
                    MethodType.methodType(Stream.class, domainEventType.typeClass(), stateClass));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<E> apply(@Nonnull InputEvent event, S state) {
        try {
            return (Stream<E>) methodHandle.invoke(aggregate, event, state);
        } catch (WrongMethodTypeException | ClassCastException e) {
            throw e;
        } catch (Throwable e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e);
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
        return domainEventType.create();
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