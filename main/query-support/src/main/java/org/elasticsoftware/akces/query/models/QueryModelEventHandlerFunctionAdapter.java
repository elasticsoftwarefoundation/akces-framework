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

package org.elasticsoftware.akces.query.models;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelEventHandlerFunction;
import org.elasticsoftware.akces.query.QueryModelState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class QueryModelEventHandlerFunctionAdapter<S extends QueryModelState, E extends DomainEvent>
        implements QueryModelEventHandlerFunction<S, E> {
    private final QueryModel<S> queryModel;
    private final String adapterMethodName;
    private final Class<E> domainEventClass;
    private final Class<S> stateClass;
    private Method adapterMethod;
    private final boolean create;
    private final DomainEventInfo domainEventInfo;

    public QueryModelEventHandlerFunctionAdapter(QueryModel<S> queryModel,
                                                 String adapterMethodName,
                                                 Class<E> domainEventClass,
                                                 Class<S> stateClass,
                                                 boolean create,
                                                 DomainEventInfo domainEventInfo) {
        this.queryModel = queryModel;
        this.adapterMethodName = adapterMethodName;
        this.domainEventClass = domainEventClass;
        this.stateClass = stateClass;
        this.create = create;
        this.domainEventInfo = domainEventInfo;
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            adapterMethod = queryModel.getClass().getMethod(adapterMethodName, domainEventClass, stateClass);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public @NotNull S apply(@NotNull E event, S state) {
        try {
            return (S) adapterMethod.invoke(queryModel, event, state);
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
    public DomainEventType<E> getEventType() {
        return new DomainEventType<>(domainEventInfo.type(), domainEventInfo.version(), domainEventClass, create, true,false);
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
