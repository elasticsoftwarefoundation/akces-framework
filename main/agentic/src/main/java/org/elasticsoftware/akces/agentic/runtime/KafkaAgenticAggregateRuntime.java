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

package org.elasticsoftware.akces.agentic.runtime;

import com.embabel.agent.core.AgentPlatform;
import jakarta.annotation.Nullable;
import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Kafka-backed implementation of {@link AgenticAggregateRuntime}.
 *
 * <p>Wraps a {@link AggregateRuntime} delegate (typically a {@code KafkaAggregateRuntime}) and
 * adds the memory-aware {@link #getMemories(AggregateStateRecord)} method. All other
 * {@link AggregateRuntime} operations are forwarded to the delegate.
 */
public class KafkaAgenticAggregateRuntime implements AgenticAggregateRuntime {

    private final AggregateRuntime delegate;
    private final ObjectMapper objectMapper;
    private final Class<? extends AggregateState> stateClass;
    private final AgentPlatform agentPlatform;

    /**
     * Creates a new {@code KafkaAgenticAggregateRuntime}.
     *
     * @param delegate      the underlying aggregate runtime to delegate to
     * @param objectMapper  Jackson object mapper for state deserialization
     * @param stateClass    the concrete state class used by this aggregate
     * @param agentPlatform the Embabel {@link AgentPlatform} used for AI-assisted processing;
     *                      must not be {@code null}
     */
    public KafkaAgenticAggregateRuntime(AggregateRuntime delegate,
                                        ObjectMapper objectMapper,
                                        Class<? extends AggregateState> stateClass,
                                        AgentPlatform agentPlatform) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.stateClass = Objects.requireNonNull(stateClass, "stateClass must not be null");
        this.agentPlatform = Objects.requireNonNull(agentPlatform, "agentPlatform must not be null");
    }

    // -------------------------------------------------------------------------
    // AgenticAggregateRuntime extension
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public AgentPlatform getAgentPlatform() {
        return agentPlatform;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deserializes the state payload using the configured {@link ObjectMapper} and
     * returns the memories when the state implements {@link MemoryAwareState}; otherwise
     * returns an empty list.
     */
    @Override
    public List<AgenticAggregateMemory> getMemories(AggregateStateRecord stateRecord) throws IOException {
        if (stateRecord == null) {
            return List.of();
        }
        AggregateState state = objectMapper.readValue(stateRecord.payload(), stateClass);
        if (state instanceof MemoryAwareState mas) {
            return mas.getMemories();
        }
        return List.of();
    }

    // -------------------------------------------------------------------------
    // AggregateRuntime delegation
    // -------------------------------------------------------------------------

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    @Nullable
    public String getDescription() {
        return delegate.getDescription();
    }

    @Override
    public Class<? extends Aggregate<?>> getAggregateClass() {
        return delegate.getAggregateClass();
    }

    @Override
    public void handleCommandRecord(CommandRecord commandRecord,
                                    Consumer<ProtocolRecord> protocolRecordConsumer,
                                    BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                                    Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        delegate.handleCommandRecord(commandRecord, protocolRecordConsumer, domainEventIndexer, stateRecordSupplier);
    }

    @Override
    public void handleExternalDomainEventRecord(DomainEventRecord eventRecord,
                                                Consumer<ProtocolRecord> protocolRecordConsumer,
                                                BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                                                Supplier<AggregateStateRecord> stateRecordSupplier,
                                                CommandBus commandBus) throws IOException {
        delegate.handleExternalDomainEventRecord(eventRecord, protocolRecordConsumer, domainEventIndexer, stateRecordSupplier, commandBus);
    }

    @Override
    public Collection<DomainEventType<?>> getAllDomainEventTypes() {
        return delegate.getAllDomainEventTypes();
    }

    @Override
    public Collection<DomainEventType<?>> getProducedDomainEventTypes() {
        return delegate.getProducedDomainEventTypes();
    }

    @Override
    public Collection<DomainEventType<?>> getExternalDomainEventTypes() {
        return delegate.getExternalDomainEventTypes();
    }

    @Override
    public Collection<CommandType<?>> getAllCommandTypes() {
        return delegate.getAllCommandTypes();
    }

    @Override
    public Collection<CommandType<?>> getLocalCommandTypes() {
        return delegate.getLocalCommandTypes();
    }

    @Override
    public Collection<CommandType<?>> getExternalCommandTypes() {
        return delegate.getExternalCommandTypes();
    }

    @Override
    public CommandType<?> getLocalCommandType(String type, int version) {
        return delegate.getLocalCommandType(type, version);
    }

    @Override
    public void registerAndValidate(DomainEventType<?> domainEventType,
                                    SchemaRegistry schemaRegistry,
                                    boolean forceRegisterOnIncompatible) throws SchemaException {
        delegate.registerAndValidate(domainEventType, schemaRegistry, forceRegisterOnIncompatible);
    }

    @Override
    public void registerAndValidate(CommandType<?> commandType,
                                    SchemaRegistry schemaRegistry,
                                    boolean forceRegisterOnIncompatible) throws SchemaException {
        delegate.registerAndValidate(commandType, schemaRegistry, forceRegisterOnIncompatible);
    }

    @Override
    public Command materialize(CommandType<?> commandType, CommandRecord commandRecord) throws IOException {
        return delegate.materialize(commandType, commandRecord);
    }

    @Override
    public byte[] serialize(Command command) throws SerializationException {
        return delegate.serialize(command);
    }

    @Override
    public boolean shouldGenerateGDPRKey(CommandRecord commandRecord) {
        return delegate.shouldGenerateGDPRKey(commandRecord);
    }

    @Override
    public boolean shouldGenerateGDPRKey(DomainEventRecord eventRecord) {
        return delegate.shouldGenerateGDPRKey(eventRecord);
    }

    @Override
    public boolean requiresGDPRContext(DomainEventRecord eventRecord) {
        return delegate.requiresGDPRContext(eventRecord);
    }

    @Override
    public boolean requiresGDPRContext(CommandRecord commandRecord) {
        return delegate.requiresGDPRContext(commandRecord);
    }

    @Override
    public boolean shouldHandlePIIData() {
        return delegate.shouldHandlePIIData();
    }

    // -------------------------------------------------------------------------
    // Built-in EventSourcingHandler implementations for memory management
    // -------------------------------------------------------------------------

    /**
     * Built-in event-sourcing handler for {@link MemoryStoredEvent}.
     *
     * <p>Appends the new memory entry described by the event to the state's memory list.
     * The state must implement {@link MemoryAwareState}; otherwise an
     * {@link IllegalStateException} is thrown.
     *
     * @param event the {@code MemoryStoredEvent} to apply
     * @param state the current aggregate state
     * @return a new state instance with the memory added
     * @throws IllegalStateException if {@code state} does not implement {@link MemoryAwareState}
     */
    @SuppressWarnings("unchecked")
    public static AggregateState onMemoryStored(MemoryStoredEvent event, AggregateState state) {
        if (!(state instanceof MemoryAwareState mas)) {
            throw new IllegalStateException(
                    "Aggregate state " + state.getClass().getName()
                            + " does not implement MemoryAwareState");
        }
        AgenticAggregateMemory memory = new AgenticAggregateMemory(
                event.memoryId(),
                event.subject(),
                event.fact(),
                event.citations(),
                event.reason(),
                event.storedAt());
        return (AggregateState) mas.withMemory(memory);
    }

    /**
     * Built-in event-sourcing handler for {@link MemoryRevokedEvent}.
     *
     * <p>Removes the memory entry identified by {@link MemoryRevokedEvent#memoryId()} from
     * the state's memory list.  The state must implement {@link MemoryAwareState}; otherwise
     * an {@link IllegalStateException} is thrown.
     *
     * @param event the {@code MemoryRevokedEvent} to apply
     * @param state the current aggregate state
     * @return a new state instance with the matching memory removed
     * @throws IllegalStateException if {@code state} does not implement {@link MemoryAwareState}
     */
    @SuppressWarnings("unchecked")
    public static AggregateState onMemoryRevoked(MemoryRevokedEvent event, AggregateState state) {
        if (!(state instanceof MemoryAwareState mas)) {
            throw new IllegalStateException(
                    "Aggregate state " + state.getClass().getName()
                            + " does not implement MemoryAwareState");
        }
        return (AggregateState) mas.withoutMemory(event.memoryId());
    }

    /**
     * Single-dispatch event-sourcing handler that routes {@link MemoryStoredEvent} and
     * {@link MemoryRevokedEvent} to the appropriate typed handler.
     *
     * <p>Intended to be used as a method reference
     * ({@code KafkaAgenticAggregateRuntime::handleMemoryEvent}) so that no anonymous adapter
     * class is required at the registration site.
     *
     * @param event the memory domain event to apply; must be a {@code MemoryStoredEvent} or
     *              {@code MemoryRevokedEvent}
     * @param state the current aggregate state
     * @return the updated aggregate state
     * @throws IllegalArgumentException if {@code event} is not a recognised memory event type
     */
    public static AggregateState handleMemoryEvent(DomainEvent event, AggregateState state) {
        if (event instanceof MemoryStoredEvent stored) {
            return onMemoryStored(stored, state);
        } else if (event instanceof MemoryRevokedEvent revoked) {
            return onMemoryRevoked(revoked, state);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported memory event type: " + event.getClass().getName());
        }
    }
}
