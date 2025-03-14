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
import org.elasticsoftware.akces.aggregate.UpcastingHandlerFunction;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

public class DomainEventUpcastingHandlerFunctionAdapter<T extends DomainEvent, R extends DomainEvent>
        implements UpcastingHandlerFunction<T, R, DomainEventType<T>, DomainEventType<R>> {
    private final Aggregate<? extends AggregateState> aggregate;
    private final String adapterMethodName;
    private final Class<T> inputEventClass;
    private final Class<R> outputEventClass;
    private final DomainEventType<T> inputEventType;
    private final DomainEventType<R> outputEventType;
    private MethodHandle methodHandle;

    public DomainEventUpcastingHandlerFunctionAdapter(Aggregate<? extends AggregateState> aggregate,
                                                      String adapterMethodName,
                                                      Class<T> inputEventClass,
                                                      Class<R> outputEventClass,
                                                      boolean external) {
        DomainEventInfo inputEventInfo = inputEventClass.getAnnotation(DomainEventInfo.class);
        if (inputEventInfo == null) {
            throw new IllegalArgumentException("Input event class " + inputEventClass.getName() +
                    " must be annotated with @DomainEventInfo");
        }

        DomainEventInfo outputEventInfo = outputEventClass.getAnnotation(DomainEventInfo.class);
        if (outputEventInfo == null) {
            throw new IllegalArgumentException("Output event class " + outputEventClass.getName() +
                    " must be annotated with @DomainEventInfo");
        }

        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.inputEventClass = inputEventClass;
        this.inputEventType = new DomainEventType<>(
                inputEventInfo.type(),
                inputEventInfo.version(),
                inputEventClass,
                false,
                external,
                ErrorEvent.class.isAssignableFrom(inputEventClass),
                hasPIIDataAnnotation(inputEventClass));
        this.outputEventClass = outputEventClass;
        this.outputEventType = new DomainEventType<>(
                outputEventInfo.type(),
                outputEventInfo.version(),
                outputEventClass,
                false,
                external,
                ErrorEvent.class.isAssignableFrom(outputEventClass),
                hasPIIDataAnnotation(outputEventClass));
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            methodHandle = MethodHandles.lookup().findVirtual(
                    aggregate.getClass(),
                    adapterMethodName,
                    MethodType.methodType(outputEventClass, inputEventClass));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public R apply(@NotNull T event) {
        try {
            return (R) methodHandle.invoke(aggregate, event);
        } catch (WrongMethodTypeException | ClassCastException e) {
            throw e;
        } catch (Throwable e) {
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e);
        }
    }

    public DomainEventType<T> getInputType() {
        return inputEventType;
    }

    public DomainEventType<R> getOutputType() {
        return outputEventType;
    }

    @Override
    public Aggregate<? extends AggregateState> getAggregate() {
        return aggregate;
    }
}