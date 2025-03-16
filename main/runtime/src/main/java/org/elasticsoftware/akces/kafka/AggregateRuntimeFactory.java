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

package org.elasticsoftware.akces.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

public class AggregateRuntimeFactory<S extends AggregateState> implements FactoryBean<AggregateRuntime>, ApplicationContextAware {
    private ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final KafkaSchemaRegistry schemaRegistry;
    private final Aggregate<S> aggregate;

    public AggregateRuntimeFactory(ObjectMapper objectMapper,
                                   KafkaSchemaRegistry schemaRegistry,
                                   Aggregate<S> aggregate) {
        this.objectMapper = objectMapper;
        this.schemaRegistry = schemaRegistry;
        this.aggregate = aggregate;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public AggregateRuntime getObject(){
        return createRuntime(aggregate);
    }

    @Override
    public Class<?> getObjectType() {
        return AggregateRuntime.class;
    }

    private KafkaAggregateRuntime createRuntime(Aggregate<S> aggregate) {
        KafkaAggregateRuntime.Builder runtimeBuilder = new KafkaAggregateRuntime.Builder();

        AggregateInfo aggregateInfo = aggregate.getClass().getAnnotation(AggregateInfo.class);
        // ensure aggregateInfo is not null
        if (aggregateInfo == null) {
            throw new IllegalStateException("Class implementing Aggregate must be annotated with @AggregateInfo");
        }
        AggregateStateInfo aggregateStateInfo = aggregateInfo.stateClass().getAnnotation(AggregateStateInfo.class);
        if (aggregateStateInfo == null) {
            throw new IllegalStateException("Aggregate state class " + aggregateInfo.stateClass().getName() +
                    " must be annotated with @AggregateStateInfo");
        }

        runtimeBuilder.setStateType(new AggregateStateType<>(
                aggregateInfo.value(),
                aggregateStateInfo.version(),
                aggregateInfo.stateClass(),
                aggregateInfo.generateGDPRKeyOnCreate(),
                aggregateInfo.indexed(),
                aggregateInfo.indexName(),
                hasPIIDataAnnotation(aggregateInfo.stateClass())))
                .setAggregateClass((Class<? extends Aggregate<?>>) aggregate.getClass())
                .setObjectMapper(objectMapper)
                .setGenerateGDPRKeyOnCreate(aggregateInfo.generateGDPRKeyOnCreate());

        applicationContext.getBeansOfType(CommandHandlerFunction.class).values().stream()
                // we only want the adapters for this Aggregate
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    CommandType<?> type = adapter.getCommandType();
                    if (adapter.isCreate()) {
                        runtimeBuilder
                                .setCommandCreateHandler(adapter)
                                .addCommand(type);
                    } else {
                        runtimeBuilder
                                .addCommandHandler(type, adapter)
                                .addCommand(type);
                    }
                    for (Object producedDomainEventType : adapter.getProducedDomainEventTypes()) {
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
                        runtimeBuilder
                                .setEventCreateHandler(adapter)
                                .addDomainEvent(type);
                    } else {
                        runtimeBuilder
                                .addExternalEventHandler(type, adapter)
                                .addDomainEvent(type);
                    }
                    for (Object producedDomainEventType : adapter.getProducedDomainEventTypes()) {
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
                        runtimeBuilder
                                .setEventSourcingCreateHandler(adapter)
                                .addDomainEvent(type);
                    } else {
                        runtimeBuilder
                                .addEventSourcingHandler(type, adapter)
                                .addDomainEvent(type);
                    }
                });
        // Add event bridge handlers
        applicationContext.getBeansOfType(EventBridgeHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    runtimeBuilder
                            .addEventBridgeHandler(type, adapter)
                            .addDomainEvent(type);
                });
        // Add state upcasting handlers
        applicationContext.getBeansOfType(UpcastingHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    if(adapter.getInputType() instanceof AggregateStateType<?> stateType) {
                        runtimeBuilder
                                .addStateUpcastingHandler(stateType, adapter);
                    } else if(adapter.getInputType() instanceof DomainEventType<?> eventType) {
                        runtimeBuilder
                                .addEventUpcastingHandler(eventType, adapter)
                                .addDomainEvent(eventType);
                    }
                });

        return runtimeBuilder.setSchemaRegistry(schemaRegistry).build();
    }
}
