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
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import jakarta.annotation.Nullable;
import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.agentic.events.AgentTaskFinishedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.kafka.KafkaAggregateRuntime;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Kafka-backed implementation of {@link AgenticAggregateRuntime}.
 *
 * <p>Wraps a {@link KafkaAggregateRuntime} delegate and adds the memory-aware
 * {@link #getMemories(AggregateStateRecord)} method as well as the auto-create
 * {@link #initializeState(Consumer, BiConsumer)} method. All other
 * {@link AggregateRuntime} operations are forwarded to the delegate.
 */
public class KafkaAgenticAggregateRuntime implements AgenticAggregateRuntime {

    private static final Logger logger =
            LoggerFactory.getLogger(KafkaAgenticAggregateRuntime.class);

    private final KafkaAggregateRuntime delegate;
    private final ObjectMapper objectMapper;
    private final Class<? extends AggregateState> stateClass;
    private final AgentPlatform agentPlatform;
    private final AgenticAggregate<?> aggregate;

    /** Round-robin counter for selecting the next agent task to resume. */
    private final AtomicInteger nextTaskIndex = new AtomicInteger(0);

    /**
     * Creates a new {@code KafkaAgenticAggregateRuntime}.
     *
     * @param delegate      the underlying aggregate runtime to delegate to
     * @param objectMapper  the Jackson {@link ObjectMapper} used for JSON serialization
     * @param stateClass    the aggregate state class
     * @param agentPlatform the Embabel {@link AgentPlatform} used for AI-assisted processing;
     *                      must not be {@code null}
     * @param aggregate     the agentic aggregate instance whose
     *                      {@link AgenticAggregate#getCreateDomainEvent()} method provides the
     *                      auto-create event
     */
    public KafkaAgenticAggregateRuntime(KafkaAggregateRuntime delegate,
                                        ObjectMapper objectMapper,
                                        Class<? extends AggregateState> stateClass,
                                        AgentPlatform agentPlatform,
                                        AgenticAggregate<?> aggregate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.stateClass = Objects.requireNonNull(stateClass, "stateClass must not be null");
        this.agentPlatform = Objects.requireNonNull(agentPlatform, "agentPlatform must not be null");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate must not be null");
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
     * <p>Materializes the state from the record via the delegate's
     * {@link AggregateRuntime#materializeState(AggregateStateRecord)} (which applies
     * upcasting when needed) and returns the memories when the state implements
     * {@link MemoryAwareState}; otherwise returns an empty list.
     */
    @Override
    public List<AgenticAggregateMemory> getMemories(AggregateStateRecord stateRecord) throws IOException {
        if (stateRecord == null) {
            return List.of();
        }
        AggregateState state = delegate.materializeState(stateRecord);
        if (state instanceof MemoryAwareState mas) {
            return mas.getMemories();
        }
        return List.of();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls {@link AgenticAggregate#getCreateDomainEvent()} on the aggregate instance and
     * delegates to the underlying {@link KafkaAggregateRuntime#handleAutoCreateDomainEvent}
     * to apply the event-sourcing create handler and produce the initial state and event
     * records.
     */
    @Override
    public void initializeState(Consumer<ProtocolRecord> protocolRecordConsumer,
                                BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer)
            throws IOException {
        DomainEvent createEvent = aggregate.getCreateDomainEvent();
        Objects.requireNonNull(createEvent,
                "AgenticAggregate.getCreateDomainEvent() must not return null");
        logger.info("Auto-creating initial state for {}Aggregate using {}",
                getName(), createEvent.getClass().getSimpleName());
        delegate.handleAutoCreateDomainEvent(createEvent, protocolRecordConsumer, domainEventIndexer);
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
    public void processDomainEvents(Stream<DomainEvent> events,
                                    String correlationId,
                                    Consumer<ProtocolRecord> protocolRecordConsumer,
                                    Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        delegate.processDomainEvents(events, correlationId, protocolRecordConsumer, stateRecordSupplier);
    }

    @Override
    public AggregateState materializeState(AggregateStateRecord stateRecord) throws IOException {
        return delegate.materializeState(stateRecord);
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
     * Built-in event-sourcing handler for {@link AgentTaskFinishedEvent}.
     *
     * <p>Removes the {@link AssignedTask} identified by
     * {@link AgentTaskFinishedEvent#agentProcessId()} from the state's assigned tasks list.
     * The state must implement {@link TaskAwareState}; otherwise an
     * {@link IllegalStateException} is thrown.
     *
     * @param event the {@code AgentTaskFinishedEvent} to apply
     * @param state the current aggregate state
     * @return a new state instance with the matching task removed
     * @throws IllegalStateException if {@code state} does not implement {@link TaskAwareState}
     */
    @SuppressWarnings("unchecked")
    public static AggregateState onAgentTaskFinished(AgentTaskFinishedEvent event, AggregateState state) {
        if (!(state instanceof TaskAwareState tas)) {
            throw new IllegalStateException(
                    "Aggregate state " + state.getClass().getName()
                            + " does not implement TaskAwareState");
        }
        return (AggregateState) tas.withoutAssignedTask(event.agentProcessId());
    }

    /**
     * Single-dispatch event-sourcing handler for all built-in agentic domain events.
     *
     * <p>Routes {@link MemoryStoredEvent}, {@link MemoryRevokedEvent},
     * {@link AgentTaskAssignedEvent}, and {@link AgentTaskFinishedEvent} to the
     * appropriate typed handler.
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
        } else if (event instanceof AgentTaskFinishedEvent finished) {
            return onAgentTaskFinished(finished, state);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported built-in event type: " + event.getClass().getName());
        }
    }

    // -------------------------------------------------------------------------
    // Resume agent tasks
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasActiveAgentTasks(Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        AggregateStateRecord stateRecord = stateRecordSupplier.get();
        if (stateRecord == null) {
            return false;
        }
        AggregateState state = delegate.materializeState(stateRecord);
        if (!(state instanceof TaskAwareState taskAwareState)) {
            return false;
        }
        return !taskAwareState.getAssignedTasks().isEmpty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implementation strategy:
     * <ol>
     *   <li>Loads and materializes the current aggregate state (with upcasting support).</li>
     *   <li>Checks whether the state implements {@link TaskAwareState}.</li>
     *   <li>Selects the next {@link AssignedTask} using a round-robin counter.</li>
     *   <li>Retrieves the existing {@link AgentProcess} from the {@link AgentPlatform}
     *       using the task's {@code agentProcessId}.</li>
     *   <li>Performs a single tick via {@link AgentProcessSingleTickRunner#tick}.</li>
     *   <li>After the tick, checks {@link AgentProcess#getFinished()}. If the process is
     *       finished, appends an {@link AgentTaskFinishedEvent} with the process's
     *       {@link AgentProcessStatusCode} so the built-in event-sourcing handler will
     *       remove the task from the state.</li>
     *   <li>Processes any resulting domain events through the delegate's
     *       {@link AggregateRuntime#processDomainEvents} method, using the agent process ID
     *       as correlation ID.</li>
     * </ol>
     */
    @Override
    public void resumeNextAgentTask(Consumer<ProtocolRecord> protocolRecordConsumer,
                                    Supplier<AggregateStateRecord> stateRecordSupplier,
                                    CommandBus commandBus) throws IOException {
        AggregateStateRecord stateRecord = stateRecordSupplier.get();
        if (stateRecord == null) {
            return;
        }

        AggregateState state = delegate.materializeState(stateRecord);
        if (!(state instanceof TaskAwareState taskAwareState)) {
            return;
        }

        List<AssignedTask> tasks = taskAwareState.getAssignedTasks();
        if (tasks.isEmpty()) {
            return;
        }

        // Round-robin selection: advance the counter and wrap around the task list size
        int index = Math.floorMod(
                nextTaskIndex.getAndUpdate(i -> Math.floorMod(i + 1, tasks.size())),
                tasks.size());
        AssignedTask task = tasks.get(index);

        logger.debug("Resuming agent task '{}' (processId={}) on aggregate {}",
                task.taskDescription(), task.agentProcessId(), getName());

        AgentProcess agentProcess = agentPlatform.getAgentProcess(task.agentProcessId());
        if (agentProcess == null) {
            logger.warn("No existing AgentProcess found for id={} on aggregate {}; skipping tick",
                    task.agentProcessId(), getName());
            return;
        }

        Stream<DomainEvent> tickEvents =
                AgentProcessSingleTickRunner.tick(agentProcess, delegate.getAllDomainEventTypes());

        // Check whether the agent process finished after this tick
        if (agentProcess.getFinished()) {
            AgentProcessStatusCode statusCode = agentProcess.getStatus();
            logger.info("Agent task '{}' (processId={}) on aggregate {} finished with status {}",
                    task.taskDescription(), task.agentProcessId(), getName(), statusCode);
            AgentTaskFinishedEvent finishedEvent = new AgentTaskFinishedEvent(
                    state.getAggregateId(),
                    task.agentProcessId(),
                    statusCode,
                    Instant.now());
            tickEvents = Stream.concat(tickEvents, Stream.of(finishedEvent));
        }

        delegate.processDomainEvents(tickEvents, task.agentProcessId(), protocolRecordConsumer, stateRecordSupplier);
    }
}
