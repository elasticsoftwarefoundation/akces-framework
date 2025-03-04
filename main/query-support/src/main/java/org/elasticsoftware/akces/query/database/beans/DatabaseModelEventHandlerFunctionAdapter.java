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

package org.elasticsoftware.akces.query.database.beans;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils;
import org.elasticsoftware.akces.query.DatabaseModel;
import org.elasticsoftware.akces.query.DatabaseModelEventHandlerFunction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class DatabaseModelEventHandlerFunctionAdapter<E extends DomainEvent>
        implements DatabaseModelEventHandlerFunction<E> {
    private final DatabaseModel databaseModel;
    private final String adapterMethodName;
    private final Class<E> domainEventClass;
    private final DomainEventType<E> domainEventType;
    private Method adapterMethod;

    public DatabaseModelEventHandlerFunctionAdapter(DatabaseModel databaseModel,
                                                    String adapterMethodName,
                                                    Class<E> domainEventClass,
                                                    String typeName,
                                                    int version) {
        this.databaseModel = databaseModel;
        this.adapterMethodName = adapterMethodName;
        this.domainEventClass = domainEventClass;
        this.domainEventType = new DomainEventType<>(
                typeName,
                version,
                domainEventClass,
                false,
                true,
                false,
                GDPRAnnotationUtils.hasPIIDataAnnotation(domainEventClass));
    }

    @SuppressWarnings("unused")
    public void init() {
        try {
            adapterMethod = databaseModel.getClass().getMethod(adapterMethodName, domainEventClass);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void accept(@NotNull E event) {
        try {
            adapterMethod.invoke(databaseModel, event);
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
    public DatabaseModel getDatabaseModel() {
        return databaseModel;
    }
}
