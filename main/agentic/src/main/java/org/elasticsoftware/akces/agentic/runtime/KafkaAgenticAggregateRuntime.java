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

import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ProcessOptions;
import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.agentic.commands.AssignTaskCommand;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Kafka-backed implementation of {@link AgenticAggregateRuntime}.
 *
 * <p>Wraps a {@link AggregateRuntime} delegate (typically a {@code KafkaAggregateRuntime}) and
 * adds the memory-aware {@link #getMemories(AggregateStateRecord)} method. All other
 * {@link AggregateRuntime} operations are forwarded to the delegate.
 */
public class KafkaAgenticAggregateRuntime implements AgenticAggregateRuntime {

    private static final Logger logger =
            LoggerFactory.getLogger(KafkaAgenticAggregateRuntime.class);

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
    // Built-in EventSourcingHandler implementations
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
     * Built-in event-sourcing handler for {@link AgentTaskAssignedEvent}.
     *
     * <p>Creates an {@link AssignedTask} from the event and appends it to the state's
     * assigned tasks list. The state must implement {@link TaskAwareState}; otherwise an
     * {@link IllegalStateException} is thrown.
     *
     * @param event the {@code AgentTaskAssignedEvent} to apply
     * @param state the current aggregate state
     * @return a new state instance with the assigned task added
     * @throws IllegalStateException if {@code state} does not implement {@link TaskAwareState}
     */
    @SuppressWarnings("unchecked")
    public static AggregateState onAgentTaskAssigned(AgentTaskAssignedEvent event, AggregateState state) {
        if (!(state instanceof TaskAwareState tas)) {
            throw new IllegalStateException(
                    "Aggregate state " + state.getClass().getName()
                            + " does not implement TaskAwareState");
        }
        AssignedTask task = new AssignedTask(
                event.agentProcessId(),
                event.taskDescription(),
                event.requestingParty(),
                event.taskMetadata(),
                event.assignedAt());
        return (AggregateState) tas.withAssignedTask(task);
    }

    /**
     * Single-dispatch event-sourcing handler for all built-in agentic domain events.
     *
     * <p>Routes {@link MemoryStoredEvent}, {@link MemoryRevokedEvent}, and
     * {@link AgentTaskAssignedEvent} to the appropriate typed handler.
     *
     * <p>Intended to be used as a method reference
     * ({@code KafkaAgenticAggregateRuntime::handleBuiltInEvent}) so that no anonymous adapter
     * class is required at the registration site.
     *
     * @param event the built-in domain event to apply
     * @param state the current aggregate state
     * @return the updated aggregate state
     * @throws IllegalArgumentException if {@code event} is not a recognised built-in event type
     */
    public static AggregateState handleBuiltInEvent(DomainEvent event, AggregateState state) {
        if (event instanceof MemoryStoredEvent stored) {
            return onMemoryStored(stored, state);
        } else if (event instanceof MemoryRevokedEvent revoked) {
            return onMemoryRevoked(revoked, state);
        } else if (event instanceof AgentTaskAssignedEvent assigned) {
            return onAgentTaskAssigned(assigned, state);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported built-in event type: " + event.getClass().getName());
        }
    }

    // -------------------------------------------------------------------------
    // Built-in CommandHandler for task assignment
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link CommandHandlerFunction} for built-in agentic commands.
     *
     * <p>The returned function handles {@link AssignTaskCommand} by resolving the appropriate
     * {@link Agent} from the {@link AgentPlatform}, creating an {@link AgentProcess},
     * and emitting an {@link AgentTaskAssignedEvent} with the process ID.
     *
     * <p>This is a factory method rather than a static method reference because it needs
     * runtime access to the {@link AgentPlatform} and aggregate name for agent resolution.
     *
     * @param agentPlatform the Embabel platform used to create agent processes
     * @param aggregateName the name of the aggregate (used for agent resolution)
     * @return a command handler function for built-in agentic commands
     */
    @SuppressWarnings("unchecked")
    public static CommandHandlerFunction<AggregateState, Command, DomainEvent> builtInCommandHandler(
            AgentPlatform agentPlatform, String aggregateName) {
        return (command, state) -> {
            if (command instanceof AssignTaskCommand assignTask) {
                return (Stream<DomainEvent>) (Stream<?>) handleAssignTask(assignTask, state, agentPlatform, aggregateName);
            } else {
                throw new IllegalArgumentException(
                        "Unsupported built-in command type: " + command.getClass().getName());
            }
        };
    }

    /**
     * Handles the {@link AssignTaskCommand} by resolving the agent, creating an
     * {@link AgentProcess}, and emitting an {@link AgentTaskAssignedEvent}.
     */
    private static Stream<DomainEvent> handleAssignTask(AssignTaskCommand command, AggregateState state,
                                                         AgentPlatform agentPlatform, String aggregateName) {
        logger.debug("Processing AssignTask command for aggregate {}, taskDescription='{}'",
                aggregateName, command.taskDescription());

        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("command", command);
        bindings.put("state", state);
        bindings.put("agenticAggregateId", command.agenticAggregateId());
        bindings.put("taskDescription", command.taskDescription());
        bindings.put("requestingParty", command.requestingParty());
        if (command.taskMetadata() != null) {
            bindings.put("taskMetadata", command.taskMetadata());
        }

        Agent agent = resolveAgentByName(agentPlatform, aggregateName);

        AgentProcess agentProcess =
                agentPlatform.createAgentProcess(agent, ProcessOptions.DEFAULT, bindings);

        String processId = agentProcess.getId();

        logger.debug("Created AgentProcess with id={} for AssignTask on aggregate {}",
                processId, aggregateName);

        AgentTaskAssignedEvent event = new AgentTaskAssignedEvent(
                command.agenticAggregateId(),
                processId,
                command.taskDescription(),
                command.requestingParty(),
                command.taskMetadata(),
                Instant.now());

        return Stream.of(event);
    }

    /**
     * Resolves the {@link Agent} for the aggregate from the platform's registered agents.
     *
     * <p>Looks for an agent matching either the exact aggregate name or the
     * {@code {aggregateName}Agent} convention.
     *
     * @param agentPlatform the platform containing registered agents
     * @param aggregateName the aggregate name to resolve an agent for
     * @return the resolved {@link Agent}; never {@code null}
     * @throws IllegalStateException if no matching agent is found
     */
    private static Agent resolveAgentByName(AgentPlatform agentPlatform, String aggregateName) {
        String agentBeanName = aggregateName + "Agent";
        for (Agent candidate : agentPlatform.agents()) {
            String candidateName = candidate.getName();
            if (aggregateName.equals(candidateName) || agentBeanName.equals(candidateName)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No Agent found with name '" + aggregateName + "' or '" + agentBeanName
                        + "' in the AgentPlatform for built-in command handling. "
                        + "The implementing application must provide an Agent named '"
                        + agentBeanName + "'.");
    }
}
