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

import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils;
import org.elasticsoftware.akces.query.DatabaseModel;
import org.elasticsoftware.akces.query.DatabaseModelEventHandlerFunction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

 public class DatabaseModelEventHandlerFunctionAdapter<E extends DomainEvent>
         implements DatabaseModelEventHandlerFunction<E> {
     private final DatabaseModel databaseModel;
     private final String adapterMethodName;
     private final Class<E> domainEventClass;
     private final DomainEventType<E> domainEventType;
     private MethodHandle adapterMethodHandle;

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
             MethodHandles.Lookup lookup = MethodHandles.lookup();
             MethodType methodType = MethodType.methodType(void.class, domainEventClass);
             adapterMethodHandle = lookup.findVirtual(databaseModel.getClass(), adapterMethodName, methodType);
         } catch (NoSuchMethodException | IllegalAccessException e) {
             throw new RuntimeException("Failed to find method " + adapterMethodName + " on " +
                     databaseModel.getClass().getName(), e);
         }
     }

     @Override
     public void accept(@Nonnull E event) {
         try {
             adapterMethodHandle.invoke(databaseModel, event);
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
     public DatabaseModel getDatabaseModel() {
         return databaseModel;
     }
 }