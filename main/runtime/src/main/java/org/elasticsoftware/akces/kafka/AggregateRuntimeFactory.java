/*
 * Copyright 2022 - 2023 The Original Authors
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

package org.elasticsoftware.akces.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.aggregate.CommandHandlerFunction;
import org.elasticsoftware.akces.aggregate.EventHandlerFunction;
import org.elasticsoftware.akces.aggregate.EventSourcingHandlerFunction;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.ListableBeanFactory;

public class AggregateRuntimeFactory<S extends AggregateState> implements FactoryBean<AggregateRuntime> {
    private final ListableBeanFactory applicationContext;
    private final ObjectMapper objectMapper;
    private final SchemaRegistryClient schemaRegistryClient;
    private final Aggregate<S> aggregate;

    public AggregateRuntimeFactory(ListableBeanFactory applicationContext,
                                   ObjectMapper objectMapper,
                                   SchemaRegistryClient schemaRegistryClient,
                                   Aggregate<S> aggregate) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.schemaRegistryClient = schemaRegistryClient;
        this.aggregate = aggregate;
    }

    @Override
    public AggregateRuntime getObject() throws Exception {
        return createRuntime(aggregate);
    }

    @Override
    public Class<?> getObjectType() {
        return AggregateRuntime.class;
    }

    private KafkaAggregateRuntime createRuntime(Aggregate<S> aggregate) {
        KafkaAggregateRuntime.Builder runtimeBuilder = new KafkaAggregateRuntime.Builder();

        AggregateInfo aggregateInfo = aggregate.getClass().getAnnotation(AggregateInfo.class);

        if (aggregateInfo != null) {
            runtimeBuilder.setStateType(new AggregateStateType<>(
                    aggregateInfo.value(),
                    aggregateInfo.version(),
                    aggregate.getStateClass()
            ));
        } else {
            throw new IllegalStateException("Class implementing Aggregate must be annotated with @AggregateInfo");
        }
        runtimeBuilder.setAggregateClass(aggregate.getClass());
        runtimeBuilder.setObjectMapper(objectMapper);
        runtimeBuilder.setGenerateGDPRKeyOnCreate(aggregateInfo.generateGDPRKeyOnCreate());

        applicationContext.getBeansOfType(CommandHandlerFunction.class).values().stream()
                // we only want the adapters for this Aggregate
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    CommandType<?> type = adapter.getCommandType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setCommandCreateHandler(adapter);
                        runtimeBuilder.addCommand(type);
                    } else {
                        runtimeBuilder.addCommandHandler(type, adapter);
                        runtimeBuilder.addCommand(type);
                    }
                    for(Object producedDomainEventType : adapter.getProducedDomainEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) producedDomainEventType);
                    }
                    for (Object errorEventType : adapter.getErrorEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) errorEventType);
                    }
                });
        // get ExternalEventHandlers for this Aggregate
        applicationContext.getBeansOfType(EventHandlerFunction.class).values().stream()
                // we only want the adapters for this Aggregate
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setEventCreateHandler(adapter);
                        runtimeBuilder.addDomainEvent(type);
                    } else {
                        runtimeBuilder.addExternalEventHandler(type, adapter);
                        runtimeBuilder.addDomainEvent(type);
                    }
                    for(Object producedDomainEventType : adapter.getProducedDomainEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) producedDomainEventType);
                    }
                    for (Object errorEventType : adapter.getErrorEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) errorEventType);
                    }
                });
        // EventSourcingHandlers
        applicationContext.getBeansOfType(EventSourcingHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setEventSourcingCreateHandler(adapter);
                        runtimeBuilder.addDomainEvent(type);
                    } else {
                        runtimeBuilder.addEventSourcingHandler(type, adapter);
                        runtimeBuilder.addDomainEvent(type);
                    }
                });

        return runtimeBuilder.setSchemaRegistryClient(schemaRegistryClient).build();
    }
}
