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
import org.elasticsoftware.akces.aggregate.EventBridgeHandlerFunction;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

public class EventBridgeHandlerFunctionAdapter<S extends AggregateState, E extends DomainEvent> implements EventBridgeHandlerFunction<S, E> {
    private final Aggregate<S> aggregate;
    private final String adapterMethodName;
    private final Class<E> inputEventClass;
    private final DomainEventType<E> domainEventType;
    private Method adapterMethod;

    public EventBridgeHandlerFunctionAdapter(Aggregate<S> aggregate,
                                             String adapterMethodName,
                                             Class<E> inputEventClass,
                                             String typeName,
                                             int version) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.inputEventClass = inputEventClass;
        this.domainEventType = new DomainEventType<>(
                typeName,
                version,
                inputEventClass,
                false,
                true,
                ErrorEvent.class.isAssignableFrom(inputEventClass),
                hasPIIDataAnnotation(inputEventClass));
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            adapterMethod = aggregate.getClass().getMethod(adapterMethodName, inputEventClass, CommandBus.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void apply(@NotNull E event, CommandBus commandBus) {
        try {
            adapterMethod.invoke(aggregate, event, commandBus);
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

}
