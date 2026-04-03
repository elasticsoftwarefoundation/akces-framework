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

package org.elasticsoftware.akces.query.reflector.beans;

import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils;
import org.elasticsoftware.akces.query.Reflector;
import org.elasticsoftware.akces.query.ReflectorEventHandlerFunction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class ReflectorEventHandlerFunctionAdapter<E extends DomainEvent, R>
        implements ReflectorEventHandlerFunction<E, R> {
    private final Reflector<R> reflector;
    private final String adapterMethodName;
    private final Class<E> domainEventClass;
    private final Class<R> returnType;
    private final DomainEventType<E> domainEventType;
    private MethodHandle adapterMethodHandle;

    public ReflectorEventHandlerFunctionAdapter(Reflector<R> reflector,
                                                String adapterMethodName,
                                                Class<E> domainEventClass,
                                                Class<R> returnType,
                                                String typeName,
                                                int version) {
        this.reflector = reflector;
        this.adapterMethodName = adapterMethodName;
        this.domainEventClass = domainEventClass;
        this.returnType = returnType;
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
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType methodType = MethodType.methodType(returnType, domainEventClass);
            adapterMethodHandle = lookup.findVirtual(reflector.getClass(), adapterMethodName, methodType);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to find method " + adapterMethodName + " on " +
                    reflector.getClass().getName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public R accept(@Nonnull E event) throws Exception {
        try {
            return (R) adapterMethodHandle.invoke(reflector, event);
        } catch (Throwable t) {
            if (t instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException("Error invoking reflector event handler", t);
        }
    }

    @Override
    public DomainEventType<E> getEventType() {
        return domainEventType;
    }

    @Override
    public Reflector<R> getReflector() {
        return reflector;
    }
}
