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
import org.elasticsoftware.akces.aggregate.AggregateStateType;
import org.elasticsoftware.akces.aggregate.UpcastingHandlerFunction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;

public class AggregateStateUpcastingHandlerFunctionAdapter<T extends AggregateState, R extends AggregateState>
        implements UpcastingHandlerFunction<T, R, AggregateStateType<T>, AggregateStateType<R>> {

    private final Aggregate<? extends AggregateState> aggregate;
    private final String adapterMethodName;
    private final AggregateStateType<T> inputStateType;
    private final AggregateStateType<R> outputStateType;
    private MethodHandle methodHandle;

    public AggregateStateUpcastingHandlerFunctionAdapter(
                                                      Aggregate<? extends AggregateState> aggregate,
                                                      String adapterMethodName,
                                                      AggregateStateType<T> inputStateType,
                                                      AggregateStateType<R> outputStateType) {
        this.aggregate = aggregate;
        this.adapterMethodName = adapterMethodName;
        this.inputStateType = inputStateType;
        this.outputStateType = outputStateType;
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            methodHandle = MethodHandles.lookup().findVirtual(
                    aggregate.getClass(),
                    adapterMethodName,
                    MethodType.methodType(outputStateType.typeClass(), inputStateType.typeClass()));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public R apply(@NotNull T state) {
        try {
            return (R) methodHandle.invoke(aggregate, state);
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
    public AggregateStateType<T> getInputType() {
        return inputStateType;
    }

    @Override
    public AggregateStateType<R> getOutputType() {
        return outputStateType;
    }

    @Override
    public Aggregate<? extends AggregateState> getAggregate() {
        return aggregate;
    }
}
