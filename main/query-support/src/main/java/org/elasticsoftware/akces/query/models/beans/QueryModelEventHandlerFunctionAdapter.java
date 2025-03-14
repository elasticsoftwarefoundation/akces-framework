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

package org.elasticsoftware.akces.query.models.beans;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelEventHandlerFunction;
import org.elasticsoftware.akces.query.QueryModelState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

public class QueryModelEventHandlerFunctionAdapter<S extends QueryModelState, E extends DomainEvent>
        implements QueryModelEventHandlerFunction<S, E> {
    private final QueryModel<S> queryModel;
    private final String adapterMethodName;
    private final Class<E> domainEventClass;
    private final Class<S> stateClass;
    private final boolean create;
    private final DomainEventType<E> domainEventType;
    private MethodHandle adapterMethodHandle;

    public QueryModelEventHandlerFunctionAdapter(QueryModel<S> queryModel,
                                                 String adapterMethodName,
                                                 Class<E> domainEventClass,
                                                 Class<S> stateClass,
                                                 boolean create,
                                                 String typeName,
                                                 int version) {
        this.queryModel = queryModel;
        this.adapterMethodName = adapterMethodName;
        this.domainEventClass = domainEventClass;
        this.stateClass = stateClass;
        this.create = create;
        this.domainEventType = new DomainEventType<>(
                typeName,
                version,
                domainEventClass,
                create,
                true,
                false,
                hasPIIDataAnnotation(domainEventClass));
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType methodType = MethodType.methodType(stateClass, domainEventClass, stateClass);
            adapterMethodHandle = lookup.findVirtual(queryModel.getClass(), adapterMethodName, methodType);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to find method " + adapterMethodName + " on " +
                    queryModel.getClass().getName(), e);
        }
    }

    @Override
    public @NotNull S apply(@NotNull E event, S state) {
        try {
            return (S) adapterMethodHandle.invoke(queryModel, event, state);
        } catch (Throwable e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else {
                throw new RuntimeException("Error invoking event handler", e);
            }
        }
    }

    @Override
    public DomainEventType<E> getEventType() {
        return domainEventType;
    }

    @Override
    public QueryModel<S> getQueryModel() {
        return queryModel;
    }

    @Override
    public boolean isCreate() {
        return create;
    }
}