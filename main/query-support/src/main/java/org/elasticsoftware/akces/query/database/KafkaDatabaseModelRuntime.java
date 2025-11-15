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

package org.elasticsoftware.akces.query.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.DatabaseModelInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.gdpr.GDPRContextHolder;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.query.DatabaseModel;
import org.elasticsoftware.akces.query.DatabaseModelEventHandlerFunction;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class KafkaDatabaseModelRuntime implements DatabaseModelRuntime {
    private static final Logger logger = LoggerFactory.getLogger(KafkaDatabaseModelRuntime.class);
    private final ObjectMapper objectMapper;
    private final Map<Class<?>, DomainEventType<?>> domainEvents;
    private final Map<DomainEventType<?>, DatabaseModelEventHandlerFunction<DomainEvent>> databaseModelEventHandlers;
    private final DatabaseModel databaseModel;
    private final String name;
    private final int version;
    private final boolean shouldHandlePIIData;

    private KafkaDatabaseModelRuntime(ObjectMapper objectMapper,
                                      Map<Class<?>, DomainEventType<?>> domainEvents,
                                      Map<DomainEventType<?>, DatabaseModelEventHandlerFunction<DomainEvent>> databaseModelEventHandlers,
                                      DatabaseModel databaseModel,
                                      String name,
                                      int version,
                                      boolean shouldHandlePIIData) {
        this.objectMapper = objectMapper;
        this.domainEvents = domainEvents;
        this.databaseModelEventHandlers = databaseModelEventHandlers;
        this.databaseModel = databaseModel;
        this.name = name;
        this.version = version;
        this.shouldHandlePIIData = shouldHandlePIIData;
    }

    @Override
    public String getName() {
        return name+"-v"+version;
    }

    @Override
    public Map<TopicPartition, Long> initializeOffsets(Collection<TopicPartition> topicPartitions) {
        Set<String> partitionIds = topicPartitions.stream()
                .map(TopicPartition::toString)
                .collect(Collectors.toSet());

        Map<String, Long> offsets = databaseModel.getOffsets(partitionIds);

        return topicPartitions.stream()
                .collect(Collectors.toMap(
                    tp -> tp,
                    tp -> offsets.getOrDefault(tp.toString(), 0L)
                ));
    }

    @Override
    public void apply(Map<TopicPartition, List<ConsumerRecord<String, ProtocolRecord>>> records,
                      Function<String, GDPRContext> gdprContextSupplier) throws IOException {
        final Object transactionMarker = databaseModel.startTransaction();
        // TODO: we probably need to order the records
        for (TopicPartition topicPartition : records.keySet()) {
            for (DomainEventRecord eventRecord : records.get(topicPartition).stream().map(consumerRecord -> (DomainEventRecord) consumerRecord.value()).toList()) {
                // determine the type to use for the external event
                DomainEventType<?> domainEventType = getDomainEventType(eventRecord);
                // with external domainevents we should look at the handler and not at the type of the external event
                if (domainEventType != null) {
                    // only process the event if we have a handler for it
                    DatabaseModelEventHandlerFunction<DomainEvent> eventHandler = databaseModelEventHandlers.get(domainEventType);
                    if (eventHandler != null) {
                        try {
                            if (Boolean.TRUE.equals(domainEventType.piiData())) {
                                GDPRContextHolder.setCurrentGDPRContext(gdprContextSupplier.apply(eventRecord.aggregateId()));
                            }
                            eventHandler.accept(materialize(domainEventType, eventRecord));
                        } finally {
                            GDPRContextHolder.resetCurrentGDPRContext();
                        }
                    }
                } // ignore if we don't have an external domainevent registered
            }
        }
        databaseModel.commitTransaction(transactionMarker, records.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toString(),
                        entry -> entry.getValue().stream()
                                .mapToLong(ConsumerRecord::offset)
                                .max()
                                .orElse(0L)
                )));
    }

    @Override
    public void validateDomainEventSchemas(SchemaRegistry schemaRegistry) {
        for (DomainEventType<?> domainEventType : domainEvents.values()) {
            schemaRegistry.validate(domainEventType);
        }
    }

    @Override
    public Collection<DomainEventType<?>> getDomainEventTypes() {
        return domainEvents.values();
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

    public static class Builder {
        private final Map<Class<?>, DomainEventType<?>> domainEvents = new HashMap<>();
        private final Map<DomainEventType<?>, DatabaseModelEventHandlerFunction<DomainEvent>> databaseModelEventHandlers = new HashMap<>();
        private ObjectMapper objectMapper;
        private DatabaseModelInfo databaseModelInfo;
        private DatabaseModel databaseModel;

        public Builder setObjectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder setDatabaseModelInfo(DatabaseModelInfo databaseModelInfo) {
            this.databaseModelInfo = databaseModelInfo;
            return this;
        }

        public Builder setDatabaseModel(DatabaseModel databaseModel) {
            this.databaseModel = databaseModel;
            return this;
        }

        public Builder addDomainEvent(DomainEventType<?> domainEvent) {
            this.domainEvents.put(domainEvent.typeClass(), domainEvent);
            return this;
        }

        public Builder addDatabaseModelEventHandler(DomainEventType<?> eventType,
                                                    DatabaseModelEventHandlerFunction<DomainEvent> databaseModelEventHandler) {
            this.databaseModelEventHandlers.put(eventType, databaseModelEventHandler);
            return this;
        }

        public KafkaDatabaseModelRuntime build() {
            final boolean shouldHandlePIIData = domainEvents.values().stream().map(DomainEventType::typeClass)
                    .anyMatch(GDPRAnnotationUtils::hasPIIDataAnnotation);
            return new KafkaDatabaseModelRuntime(
                    objectMapper,
                    domainEvents,
                    databaseModelEventHandlers,
                    databaseModel,
                    databaseModelInfo.value(),
                    databaseModelInfo.version(),
                    shouldHandlePIIData);
        }
    }

    @Override
    public String toString() {
        return "KafkaDatabaseModelRuntime{"+getName()+"}";
    }
}
