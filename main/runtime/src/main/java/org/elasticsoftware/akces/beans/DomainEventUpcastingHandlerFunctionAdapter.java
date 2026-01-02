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
import org.elasticsoftware.akces.aggregate.UpcastingHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;

public class DomainEventUpcastingHandlerFunctionAdapter<T extends DomainEvent, R extends DomainEvent>
        implements UpcastingHandlerFunction<T, R, DomainEventType<T>, DomainEventType<R>> {
    private final Aggregate<? extends AggregateState> aggregate;
    private final String adapterMethodName;
    private final DomainEventType<T> inputEventType;
    private final DomainEventType<R> outputEventType;
    private MethodHandle methodHandle;

    public DomainEventUpcastingHandlerFunctionAdapter(Aggregate<? extends AggregateState> aggregate,
                                                      String adapterMethodName,
                                                      DomainEventType<T> inputEventType,
                                                      DomainEventType<R> outputEventType) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.inputEventType = inputEventType;
        this.outputEventType = outputEventType;
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            methodHandle = MethodHandles.lookup().findVirtual(
                    aggregate.getClass(),
                    adapterMethodName,
                    MethodType.methodType(outputEventType.typeClass(), inputEventType.typeClass()));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public R apply(@Nonnull T event) {
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