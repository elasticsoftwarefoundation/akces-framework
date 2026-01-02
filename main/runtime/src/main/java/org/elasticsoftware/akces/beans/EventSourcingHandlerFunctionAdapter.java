/*
 * Copyright 2022 - 2026 The Original Authors
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
import org.elasticsoftware.akces.aggregate.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;

public class EventSourcingHandlerFunctionAdapter<S extends AggregateState, E extends DomainEvent> implements EventSourcingHandlerFunction<S, E> {
    private final Aggregate<S> aggregate;
    private final String adapterMethodName;
    private final Class<S> stateClass;
    private final DomainEventType<E> domainEventType;
    private MethodHandle methodHandle;

    public EventSourcingHandlerFunctionAdapter(Aggregate<S> aggregate,
                                               String adapterMethodName,
                                               DomainEventType<E> domainEventType,
                                               Class<S> stateClass) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.stateClass = stateClass;
        this.domainEventType = domainEventType;
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            methodHandle = MethodHandles.lookup().findVirtual(
                    aggregate.getClass(),
                    adapterMethodName,
                    MethodType.methodType(stateClass, domainEventType.typeClass(), stateClass));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nonnull S apply(@Nonnull E event, S state) {
        try {
            return (S) methodHandle.invoke(aggregate, event, state);
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
    public DomainEventType<E> getEventType() {
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
}