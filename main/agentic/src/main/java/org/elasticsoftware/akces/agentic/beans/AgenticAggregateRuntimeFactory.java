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

package org.elasticsoftware.akces.agentic.beans;

import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.elasticsoftware.akces.agentic.runtime.KafkaAgenticAggregateRuntime;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.aggregate.AgenticAggregate;
import org.elasticsoftware.akces.annotations.AgenticAggregateInfo;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.kafka.KafkaAggregateRuntime;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring {@link FactoryBean} that creates a {@link AgenticAggregateRuntime} for an
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate} annotated with
 * {@link AgenticAggregateInfo}.
 *
 * <p>Analogous to
 * {@link org.elasticsoftware.akces.kafka.AggregateRuntimeFactory} but adapted for
 * the agentic module: agentic aggregates are never GDPR-keyed, never indexed, and
 * never have PII-annotated state.</p>
 *
 * <p>Produces a {@link KafkaAgenticAggregateRuntime} wrapping the internally built
 * {@link KafkaAggregateRuntime}, adding memory-aware operations.</p>
 *
 * @param <S> the aggregate state type
 */
public class AgenticAggregateRuntimeFactory<S extends AggregateState> implements FactoryBean<AgenticAggregateRuntime>, ApplicationContextAware {

    private ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final AgenticAggregate<S> aggregate;

    /**
     * Creates a new factory.
     *
     * @param objectMapper the Jackson {@link ObjectMapper} used for JSON schema generation
     * @param aggregate    the agentic aggregate instance whose handlers will be discovered
     */
    public AgenticAggregateRuntimeFactory(ObjectMapper objectMapper, AgenticAggregate<S> aggregate) {
        this.objectMapper = objectMapper;
        this.aggregate = aggregate;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public AgenticAggregateRuntime getObject() {
        AgenticAggregateInfo agenticInfo = aggregate.getClass().getAnnotation(AgenticAggregateInfo.class);
        if (agenticInfo == null) {
            throw new IllegalStateException(
                    "Class implementing AgenticAggregate must be annotated with @AgenticAggregateInfo");
        }
        KafkaAggregateRuntime kafkaRuntime = createRuntime(aggregate);
        return new KafkaAgenticAggregateRuntime(kafkaRuntime, objectMapper, agenticInfo.stateClass());
    }

    @Override
    public Class<?> getObjectType() {
        return AgenticAggregateRuntime.class;
    }

    @SuppressWarnings("unchecked")
    private KafkaAggregateRuntime createRuntime(AgenticAggregate<S> aggregate) {
        KafkaAggregateRuntime.Builder runtimeBuilder = new KafkaAggregateRuntime.Builder();

        AgenticAggregateInfo agenticInfo = aggregate.getClass().getAnnotation(AgenticAggregateInfo.class);
        if (agenticInfo == null) {
            throw new IllegalStateException(
                    "Class implementing AgenticAggregate must be annotated with @AgenticAggregateInfo");
        }
        AggregateStateInfo stateInfo = agenticInfo.stateClass().getAnnotation(AggregateStateInfo.class);
        if (stateInfo == null) {
            throw new IllegalStateException(
                    "Agentic aggregate state class " + agenticInfo.stateClass().getName()
                            + " must be annotated with @AggregateStateInfo");
        }

        // Agentic aggregates: no GDPR, not indexed, no PIIData on state
        runtimeBuilder.setStateType(new AggregateStateType<>(
                        agenticInfo.value(),
                        stateInfo.version(),
                        agenticInfo.stateClass(),
                        false,   // generateGDPRKeyOnCreate – not applicable
                        false,   // indexed – singleton, not indexed
                        "",      // indexName – not applicable
                        false))  // shouldHandlePIIData – not applicable
                .setAggregateClass((Class<? extends Aggregate<?>>) aggregate.getClass())
                .setObjectMapper(objectMapper)
                .setGenerateGDPRKeyOnCreate(false);

        // CommandHandlerFunctions
        applicationContext.getBeansOfType(CommandHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    CommandType<?> type = adapter.getCommandType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setCommandCreateHandler(adapter).addCommand(type);
                    } else {
                        runtimeBuilder.addCommandHandler(type, adapter).addCommand(type);
                    }
                    for (Object produced : adapter.getProducedDomainEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) produced);
                    }
                    for (Object error : adapter.getErrorEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) error);
                    }
                });

        // EventHandlerFunctions (external domain events — no EventBridgeHandlers for agentic)
        applicationContext.getBeansOfType(EventHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setEventCreateHandler(adapter).addDomainEvent(type);
                    } else {
                        runtimeBuilder.addExternalEventHandler(type, adapter).addDomainEvent(type);
                    }
                    for (Object produced : adapter.getProducedDomainEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) produced);
                    }
                    for (Object error : adapter.getErrorEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) error);
                    }
                });

        // EventSourcingHandlerFunctions
        applicationContext.getBeansOfType(EventSourcingHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setEventSourcingCreateHandler(adapter).addDomainEvent(type);
                    } else {
                        runtimeBuilder.addEventSourcingHandler(type, adapter).addDomainEvent(type);
                    }
                });

        // UpcastingHandlerFunctions
        applicationContext.getBeansOfType(UpcastingHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    if (adapter.getInputType() instanceof AggregateStateType<?> stateType) {
                        runtimeBuilder.addStateUpcastingHandler(stateType, adapter);
                    } else if (adapter.getInputType() instanceof DomainEventType<?> eventType) {
                        runtimeBuilder.addEventUpcastingHandler(eventType, adapter).addDomainEvent(eventType);
                    }
                });

        // Register built-in event-sourcing handlers for memory management.
        // These handle the framework-owned MemoryStored and MemoryRevoked events,
        // which every AgenticAggregate state must process via MemoryAwareState.
        DomainEventType<MemoryStoredEvent> memoryStoredType = new DomainEventType<>(
                "MemoryStored", 1, MemoryStoredEvent.class, false, false, false, false);
        DomainEventType<MemoryRevokedEvent> memoryRevokedType = new DomainEventType<>(
                "MemoryRevoked", 1, MemoryRevokedEvent.class, false, false, false, false);

        runtimeBuilder
                .addEventSourcingHandler(
                        memoryStoredType,
                        (event, state) -> KafkaAgenticAggregateRuntime.onMemoryStored(
                                (MemoryStoredEvent) event, state))
                .addDomainEvent(memoryStoredType);
        runtimeBuilder
                .addEventSourcingHandler(
                        memoryRevokedType,
                        (event, state) -> KafkaAgenticAggregateRuntime.onMemoryRevoked(
                                (MemoryRevokedEvent) event, state))
                .addDomainEvent(memoryRevokedType);

        return runtimeBuilder.validateAndBuild();
    }
}
