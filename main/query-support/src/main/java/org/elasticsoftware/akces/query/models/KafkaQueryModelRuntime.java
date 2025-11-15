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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelEventHandlerFunction;
import org.elasticsoftware.akces.query.QueryModelState;
import org.elasticsoftware.akces.query.QueryModelStateType;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KafkaQueryModelRuntime<S extends QueryModelState> implements QueryModelRuntime<S> {
    private static final Logger logger = LoggerFactory.getLogger(KafkaQueryModelRuntime.class);
    private final ObjectMapper objectMapper;
    private final QueryModelStateType<?> type;
    private final Class<? extends QueryModel<S>> queryModelClass;
    private final Map<Class<?>, DomainEventType<?>> domainEvents;
    private final QueryModelEventHandlerFunction<S, DomainEvent> createStateHandler;
    private final Map<DomainEventType<?>, QueryModelEventHandlerFunction<S, DomainEvent>> queryModelEventHandlers;
    private final boolean shouldHandlePIIData;

    private KafkaQueryModelRuntime(ObjectMapper objectMapper,
                                   QueryModelStateType<S> type,
                                   Class<? extends QueryModel<S>> queryModelClass,
                                   QueryModelEventHandlerFunction<S, DomainEvent> createStateHandler,
                                   Map<Class<?>, DomainEventType<?>> domainEvents,
                                   Map<DomainEventType<?>, QueryModelEventHandlerFunction<S, DomainEvent>> queryModelEventHandlers, boolean shouldHandlePIIData) {
        this.objectMapper = objectMapper;
        this.type = type;
        this.queryModelClass = queryModelClass;
        this.domainEvents = domainEvents;
        this.createStateHandler = createStateHandler;
        this.queryModelEventHandlers = queryModelEventHandlers;
        this.shouldHandlePIIData = shouldHandlePIIData;
    }

    @Override
    public String getName() {
        return type.typeName();
    }

    @Override
    public String getIndexName() {
        return type.indexName();
    }

    @Override
    public Class<? extends QueryModel<S>> getQueryModelClass() {
        return queryModelClass;
    }

    @Override
    public S apply(List<DomainEventRecord> eventRecords, S currentState) throws IOException {
        S state = currentState;
        for (DomainEventRecord eventRecord : eventRecords) {
            // determine the type to use for the external event
            DomainEventType<?> domainEventType = getDomainEventType(eventRecord);
            // with external domainevents we should look at the handler and not at the type of the external event
            if (domainEventType != null) {
                if (state == null && createStateHandler != null && createStateHandler.getEventType().equals(domainEventType)) {
                    state = createStateHandler.apply(materialize(domainEventType, eventRecord), null);
                } else if (state != null) { // we first need to see the create handler
                    // only process the event if we have a handler for it
                    QueryModelEventHandlerFunction<S, DomainEvent> eventHandler = queryModelEventHandlers.get(domainEventType);
                    if (eventHandler != null) {
                        state = eventHandler.apply(materialize(domainEventType, eventRecord), state);
                    }
                }
            } // ignore if we don't have an external domainevent registered
        }
        return state;
    }

    @Override
    public void validateDomainEventSchemas(SchemaRegistry schemaRegistry) {
        for (DomainEventType<?> domainEventType : domainEvents.values()) {
            schemaRegistry.validate(domainEventType);
        }
    }

    @Override
    public boolean shouldHandlePIIData() {
        return shouldHandlePIIData;
    }

    private DomainEvent materialize(DomainEventType<?> domainEventType, DomainEventRecord eventRecord) throws IOException {
        return objectMapper.readValue(eventRecord.payload(), domainEventType.typeClass());
    }

    private DomainEventType<?> getDomainEventType(DomainEventRecord eventRecord) {
        // because it is an external event we need to find the highest version that is smaller than the eventRecord version
        return domainEvents.entrySet().stream()
                .filter(entry -> entry.getValue().external())
                .filter(entry -> entry.getValue().typeName().equals(eventRecord.name()))
                .filter(entry -> entry.getValue().version() <= eventRecord.version())
                .max(Comparator.comparingInt(entry -> entry.getValue().version()))
                .map(Map.Entry::getValue).orElse(null);
    }

    public static class Builder<S extends QueryModelState> {
        private final Map<Class<?>, DomainEventType<?>> domainEvents = new HashMap<>();
        private final Map<DomainEventType<?>, QueryModelEventHandlerFunction<S, DomainEvent>> queryModelEventHandlers = new HashMap<>();
        private ObjectMapper objectMapper;
        private QueryModelStateType<S> stateType;
        private Class<? extends QueryModel<S>> queryModelClass;
        private QueryModelEventHandlerFunction<S, DomainEvent> createStateHandler;

        public Builder<S> setObjectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder<S> setStateType(QueryModelStateType<S> stateType) {
            this.stateType = stateType;
            return this;
        }

        public Builder<S> setQueryModelClass(Class<? extends QueryModel<S>> queryModelClass) {
            this.queryModelClass = queryModelClass;
            return this;
        }

        public Builder<S> setCreateHandler(QueryModelEventHandlerFunction<S, DomainEvent> createStateHandler) {
            this.createStateHandler = createStateHandler;
            return this;
        }

        public Builder<S> addDomainEvent(DomainEventType<?> domainEvent) {
            this.domainEvents.put(domainEvent.typeClass(), domainEvent);
            return this;
        }

        public Builder<S> addQueryModelEventHandler(DomainEventType<?> eventType,
                                                 QueryModelEventHandlerFunction<S, DomainEvent> eventSourcingHandler) {
            this.queryModelEventHandlers.put(eventType, eventSourcingHandler);
            return this;
        }

        public KafkaQueryModelRuntime<S> build() {
            final boolean shouldHandlePIIData = domainEvents.values().stream().map(DomainEventType::typeClass)
                    .anyMatch(GDPRAnnotationUtils::hasPIIDataAnnotation);
            return new KafkaQueryModelRuntime<>(
                    objectMapper,
                    stateType,
                    queryModelClass,
                    createStateHandler,
                    domainEvents,
                    queryModelEventHandlers,
                    shouldHandlePIIData);
        }
    }

    @Override
    public String toString() {
        return "KafkaQueryModelRuntime{"+getName()+"}";
    }
}
