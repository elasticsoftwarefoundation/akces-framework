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
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import jakarta.annotation.Nullable;
import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.agentic.embabel.MemoryDistillationInput;
import org.elasticsoftware.akces.agentic.embabel.MemoryDistillationResult;
import org.elasticsoftware.akces.agentic.embabel.MemoryDistillerAgent;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.agentic.events.AgentTaskFinishedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryDistillationFailedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryDistillationFinishedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryDistillationStartedEvent;
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
import java.util.*;
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
    private final int maxTotalMemories;
    private final int maxMemoriesAdded;

    /** Round-robin counter for selecting the next agent task to resume. */
    private final AtomicInteger nextTaskIndex = new AtomicInteger(0);

    /**
     * Creates a new {@code KafkaAgenticAggregateRuntime}.
     *
     * @param delegate         the underlying aggregate runtime to delegate to
     * @param objectMapper     the Jackson {@link ObjectMapper} used for JSON serialization
     * @param stateClass       the aggregate state class
     * @param agentPlatform    the Embabel {@link AgentPlatform} used for AI-assisted processing;
     *                         must not be {@code null}
     * @param aggregate        the agentic aggregate instance whose
     *                         {@link AgenticAggregate#getCreateDomainEvent()} method provides the
     *                         auto-create event
     * @param maxTotalMemories the total memory capacity for this aggregate
     * @param maxMemoriesAdded the per-distillation budget for net new memories
     */
    public KafkaAgenticAggregateRuntime(KafkaAggregateRuntime delegate,
                                        ObjectMapper objectMapper,
                                        Class<? extends AggregateState> stateClass,
                                        AgentPlatform agentPlatform,
                                        AgenticAggregate<?> aggregate,
                                        int maxTotalMemories,
                                        int maxMemoriesAdded) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.stateClass = Objects.requireNonNull(stateClass, "stateClass must not be null");
        this.agentPlatform = Objects.requireNonNull(agentPlatform, "agentPlatform must not be null");
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate must not be null");
        if (maxTotalMemories < 0) {
            throw new IllegalArgumentException("maxTotalMemories must be >= 0");
        }
        if (maxMemoriesAdded < 0) {
            throw new IllegalArgumentException("maxMemoriesAdded must be >= 0");
        }
        this.maxTotalMemories = maxTotalMemories;
        this.maxMemoriesAdded = maxMemoriesAdded;
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
     * Built-in event-sourcing handler for {@link MemoryDistillationStartedEvent}.
     *
     * <p>Records the active {@link MemoryDistillation} in the aggregate state. The state
     * must implement {@link MemoryAwareState}; otherwise an
     * {@link IllegalStateException} is thrown.
     *
     * @param event the {@code MemoryDistillationStartedEvent} to apply
     * @param state the current aggregate state
     * @return a new state instance with the distillation recorded
     * @throws IllegalStateException if {@code state} does not implement
     *         {@link MemoryAwareState}
     */
    @SuppressWarnings("unchecked")
    public static AggregateState onMemoryDistillationStarted(MemoryDistillationStartedEvent event,
                                                              AggregateState state) {
        if (!(state instanceof MemoryAwareState mas)) {
            throw new IllegalStateException(
                    "Aggregate state " + state.getClass().getName()
                            + " does not implement MemoryAwareState");
        }
        MemoryDistillation distillation = new MemoryDistillation(
                event.agentProcessId(),
                event.startedAt());
        return (AggregateState) mas.withMemoryDistillation(distillation);
    }

    /**
     * Built-in event-sourcing handler for {@link MemoryDistillationFinishedEvent}.
     *
     * <p>Removes the {@link MemoryDistillation} identified by
     * {@link MemoryDistillationFinishedEvent#agentProcessId()} from the aggregate state.
     * The state must implement {@link MemoryAwareState}; otherwise an
     * {@link IllegalStateException} is thrown.
     *
     * @param event the {@code MemoryDistillationFinishedEvent} to apply
     * @param state the current aggregate state
     * @return a new state instance with the matching distillation removed
     * @throws IllegalStateException if {@code state} does not implement
     *         {@link MemoryAwareState}
     */
    @SuppressWarnings("unchecked")
    public static AggregateState onMemoryDistillationFinished(MemoryDistillationFinishedEvent event,
                                                               AggregateState state) {
        if (!(state instanceof MemoryAwareState mas)) {
            throw new IllegalStateException(
                    "Aggregate state " + state.getClass().getName()
                            + " does not implement MemoryAwareState");
        }
        return (AggregateState) mas.withoutMemoryDistillation(event.agentProcessId());
    }

    /**
     * Built-in event-sourcing handler for {@link MemoryDistillationFailedEvent}.
     *
     * <p>Removes the {@link MemoryDistillation} identified by
     * {@link MemoryDistillationFailedEvent#agentProcessId()} from the aggregate state.
     * The state must implement {@link MemoryAwareState}; otherwise an
     * {@link IllegalStateException} is thrown.
     *
     * @param event the {@code MemoryDistillationFailedEvent} to apply
     * @param state the current aggregate state
     * @return a new state instance with the matching distillation removed
     * @throws IllegalStateException if {@code state} does not implement
     *         {@link MemoryAwareState}
     */
    @SuppressWarnings("unchecked")
    public static AggregateState onMemoryDistillationFailed(MemoryDistillationFailedEvent event,
                                                             AggregateState state) {
        if (!(state instanceof MemoryAwareState mas)) {
            throw new IllegalStateException(
                    "Aggregate state " + state.getClass().getName()
                            + " does not implement MemoryAwareState");
        }
        return (AggregateState) mas.withoutMemoryDistillation(event.agentProcessId());
    }

    /**
     * Single-dispatch event-sourcing handler for all built-in agentic domain events.
     *
     * <p>Routes {@link MemoryStoredEvent}, {@link MemoryRevokedEvent},
     * {@link AgentTaskAssignedEvent}, {@link AgentTaskFinishedEvent},
     * {@link MemoryDistillationStartedEvent}, {@link MemoryDistillationFinishedEvent},
     * and {@link MemoryDistillationFailedEvent} to the appropriate typed handler.
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
        } else if (event instanceof MemoryDistillationStartedEvent started) {
            return onMemoryDistillationStarted(started, state);
        } else if (event instanceof MemoryDistillationFinishedEvent finished) {
            return onMemoryDistillationFinished(finished, state);
        } else if (event instanceof MemoryDistillationFailedEvent failed) {
            return onMemoryDistillationFailed(failed, state);
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
        // Check for active assigned tasks
        if (state instanceof TaskAwareState taskAwareState && !taskAwareState.getAssignedTasks().isEmpty()) {
            return true;
        }
        // Check for active memory distillation processes
        if (state instanceof MemoryAwareState mas && !mas.getMemoryDistillations().isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implementation strategy:
     * <ol>
     *   <li>Loads and materializes the current aggregate state (with upcasting support).</li>
     *   <li>Collects all active {@link AgentProcessAware} items (assigned tasks and any
     *       active memory distillation) into a unified list.</li>
     *   <li>Selects the next item using a round-robin counter.</li>
     *   <li>Retrieves the existing {@link AgentProcess} from the {@link AgentPlatform}
     *       using the item's {@code getAgentProcessId()}.</li>
     *   <li>Performs a single tick via {@link AgentProcessSingleTickRunner#tick}.</li>
     *   <li>If the item is an {@link AssignedTask} and the process finishes, appends an
     *       {@link AgentTaskFinishedEvent}. If the task completed successfully and a
     *       {@link MemoryDistillerAgent} is available, creates a distillation process
     *       and emits a {@link MemoryDistillationStartedEvent}.</li>
     *   <li>If the item is a {@link MemoryDistillation} and the process finishes,
     *       collects memory events and emits a {@link MemoryDistillationFinishedEvent}.</li>
     *   <li>Processes any resulting domain events through the delegate's
     *       {@link AggregateRuntime#processDomainEvents} method.</li>
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

        // Build a unified list of all AgentProcessAware items
        List<AgentProcessAware> activeItems = new ArrayList<>();
        if (state instanceof TaskAwareState taskAwareState) {
            activeItems.addAll(taskAwareState.getAssignedTasks());
        }
        if (state instanceof MemoryAwareState mas) {
            activeItems.addAll(mas.getMemoryDistillations());
        }

        if (activeItems.isEmpty()) {
            return;
        }

        // Round-robin selection: advance the counter and wrap around the list size
        int index = Math.floorMod(
                nextTaskIndex.getAndUpdate(i -> Math.floorMod(i + 1, activeItems.size())),
                activeItems.size());
        AgentProcessAware item = activeItems.get(index);

        logger.debug("Resuming agent process (processId={}) on aggregate {}",
                item.getAgentProcessId(), getName());

        AgentProcess agentProcess = agentPlatform.getAgentProcess(item.getAgentProcessId());
        if (agentProcess == null) {
            logger.warn("No existing AgentProcess found for id={} on aggregate {}; emitting failure event",
                    item.getAgentProcessId(), getName());
            handleMissingAgentProcess(item, state, protocolRecordConsumer, stateRecordSupplier);
            return;
        }

        if (item instanceof AssignedTask task) {
            resumeAssignedTask(task, agentProcess, state, protocolRecordConsumer, stateRecordSupplier);
        } else if (item instanceof MemoryDistillation distillation) {
            resumeMemoryDistillation(distillation, agentProcess, state, protocolRecordConsumer, stateRecordSupplier);
        }
    }

    /**
     * Handles the case where an {@link AgentProcess} can no longer be found on the
     * {@link AgentPlatform}, typically after a restart or crash.
     *
     * <p>For an {@link AssignedTask}, emits an {@link AgentTaskFinishedEvent} with
     * {@link AgentProcessStatusCode#FAILED} to clear the task from state.
     * For a {@link MemoryDistillation}, emits a {@link MemoryDistillationFailedEvent}
     * to clear the distillation from state.
     */
    private void handleMissingAgentProcess(AgentProcessAware item,
                                            AggregateState state,
                                            Consumer<ProtocolRecord> protocolRecordConsumer,
                                            Supplier<AggregateStateRecord> stateRecordSupplier)
            throws IOException {
        Stream<DomainEvent> events;
        if (item instanceof AssignedTask task) {
            logger.info("Emitting AgentTaskFinished(FAILED) for orphaned task '{}' (processId={}) on aggregate {}",
                    task.taskDescription(), task.agentProcessId(), getName());
            events = Stream.of(new AgentTaskFinishedEvent(
                    state.getAggregateId(),
                    task.agentProcessId(),
                    AgentProcessStatusCode.FAILED,
                    Instant.now()));
        } else if (item instanceof MemoryDistillation distillation) {
            logger.info("Emitting MemoryDistillationFailed for orphaned distillation (processId={}) on aggregate {}",
                    distillation.agentProcessId(), getName());
            events = Stream.of(new MemoryDistillationFailedEvent(
                    state.getAggregateId(),
                    distillation.agentProcessId(),
                    Instant.now()));
        } else {
            logger.warn("Unknown AgentProcessAware type: {}; cannot emit failure event",
                    item.getClass().getName());
            return;
        }
        delegate.processDomainEvents(events, item.getAgentProcessId(),
                protocolRecordConsumer, stateRecordSupplier);
    }

    /**
     * Resumes a single tick for an {@link AssignedTask}.
     *
     * <p>Ticks the agent process and, if finished, emits an {@link AgentTaskFinishedEvent}.
     * For successfully completed tasks, starts a memory distillation process (if a
     * {@link MemoryDistillerAgent} is available) and emits a
     * {@link MemoryDistillationStartedEvent}.
     */
    private void resumeAssignedTask(AssignedTask task,
                                     AgentProcess agentProcess,
                                     AggregateState state,
                                     Consumer<ProtocolRecord> protocolRecordConsumer,
                                     Supplier<AggregateStateRecord> stateRecordSupplier)
            throws IOException {
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

            // Start memory distillation for successfully completed processes
            if (statusCode == AgentProcessStatusCode.COMPLETED) {
                List<DomainEvent> distillationEvents = startMemoryDistillation(agentProcess, state, task);
                if (!distillationEvents.isEmpty()) {
                    tickEvents = Stream.concat(tickEvents, distillationEvents.stream());
                }
            }
        }

        delegate.processDomainEvents(tickEvents, task.agentProcessId(), protocolRecordConsumer, stateRecordSupplier);
    }

    /**
     * Resumes a single tick for an active {@link MemoryDistillation} process.
     *
     * <p>Ticks the distillation agent process and, if finished, translates the result
     * into memory events and emits a {@link MemoryDistillationFinishedEvent}.
     */
    private void resumeMemoryDistillation(MemoryDistillation distillation,
                                           AgentProcess agentProcess,
                                           AggregateState state,
                                           Consumer<ProtocolRecord> protocolRecordConsumer,
                                           Supplier<AggregateStateRecord> stateRecordSupplier)
            throws IOException {
        Stream<DomainEvent> tickEvents =
                AgentProcessSingleTickRunner.tick(agentProcess, delegate.getAllDomainEventTypes());

        if (agentProcess.getFinished()) {
            logger.info("Memory distillation (processId={}) on aggregate {} finished",
                    distillation.agentProcessId(), getName());

            // Collect memory events from the finished distillation
            List<DomainEvent> memoryEvents = collectDistillationResult(agentProcess, state);
            if (!memoryEvents.isEmpty()) {
                tickEvents = Stream.concat(tickEvents, memoryEvents.stream());
            }

            // Emit finished event to clear the distillation from state
            MemoryDistillationFinishedEvent finishedEvent = new MemoryDistillationFinishedEvent(
                    state.getAggregateId(),
                    distillation.agentProcessId(),
                    Instant.now());
            tickEvents = Stream.concat(tickEvents, Stream.of(finishedEvent));
        }

        delegate.processDomainEvents(tickEvents, distillation.agentProcessId(),
                protocolRecordConsumer, stateRecordSupplier);
    }

    // -------------------------------------------------------------------------
    // Memory distillation
    // -------------------------------------------------------------------------

    /**
     * Starts a memory distillation process for a successfully completed {@link AgentProcess}.
     *
     * <p>Creates a separate agent process for the {@link MemoryDistillerAgent} and populates
     * its blackboard with a single {@link MemoryDistillationInput}. Instead of running the
     * process to completion, it emits a {@link MemoryDistillationStartedEvent} so that the
     * distillation is tracked in the aggregate state and advanced via the tick mechanism.
     *
     * @param completedProcess the agent process that has completed successfully
     * @param state            the current aggregate state
     * @param task             the assigned task that triggered the agent process
     * @return a list containing a {@link MemoryDistillationStartedEvent}, or an empty list
     *         if the {@link MemoryDistillerAgent} is not available
     */
    private List<DomainEvent> startMemoryDistillation(AgentProcess completedProcess, AggregateState state,
                                                       AssignedTask task) {
        if (!(state instanceof MemoryAwareState mas)) {
            logger.debug("Aggregate state does not implement MemoryAwareState; skipping memory distillation");
            return List.of();
        }

        Agent memoryDistillerAgent = resolveMemoryDistillerAgent();
        if (memoryDistillerAgent == null) {
            logger.warn("MemoryDistillerAgent not deployed on the platform; skipping memory distillation");
            return List.of();
        }

        List<AgenticAggregateMemory> currentMemories = mas.getMemories();

        MemoryDistillationInput distillationInput = new MemoryDistillationInput(
                task,
                completedProcess.getHistory(),
                completedProcess.getBlackboard().getObjects(),
                currentMemories,
                maxTotalMemories,
                maxMemoriesAdded);

        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("input", distillationInput);

        try {
            AgentProcess distillerProcess = agentPlatform.createAgentProcess(
                    memoryDistillerAgent, ProcessOptions.DEFAULT, bindings);

            MemoryDistillationStartedEvent startedEvent = new MemoryDistillationStartedEvent(
                    state.getAggregateId(),
                    distillerProcess.getId(),
                    Instant.now());

            logger.info("Started memory distillation (processId={}) for aggregate {}",
                    distillerProcess.getId(), getName());

            return List.of(startedEvent);
        } catch (Exception e) {
            logger.warn("Failed to start memory distillation for aggregate {}; proceeding without memory updates",
                    getName(), e);
            return List.of();
        }
    }

    /**
     * Collects the memory distillation result from a finished distillation process
     * and translates it into memory domain events.
     *
     * @param distillerProcess the finished distiller agent process
     * @param state            the current aggregate state
     * @return a list of {@link MemoryStoredEvent} and {@link MemoryRevokedEvent} instances;
     *         may be empty if no memories need to be changed
     */
    private List<DomainEvent> collectDistillationResult(AgentProcess distillerProcess,
                                                         AggregateState state) {
        try {
            MemoryDistillationResult result = distillerProcess.getBlackboard()
                    .last(MemoryDistillationResult.class);

            if (result == null) {
                logger.debug("MemoryDistillerAgent produced no result for aggregate {}", getName());
                return List.of();
            }

            List<AgenticAggregateMemory> currentMemories = state instanceof MemoryAwareState mas
                    ? mas.getMemories()
                    : List.of();

            int capacityLeft = Math.max(0, maxTotalMemories - currentMemories.size());
            int effectiveLimit = Math.min(capacityLeft, maxMemoriesAdded);
            return translateDistillationResult(result, currentMemories, effectiveLimit);
        } catch (Exception e) {
            logger.warn("Failed to collect memory distillation result for aggregate {}; " +
                            "proceeding without memory updates", getName(), e);
            return List.of();
        }
    }

    /**
     * Resolves the {@link MemoryDistillerAgent} from the {@link AgentPlatform}.
     *
     * @return the resolved agent, or {@code null} if not deployed
     */
    private Agent resolveMemoryDistillerAgent() {
        for (Agent agent : agentPlatform.agents()) {
            if (MemoryDistillerAgent.AGENT_NAME.equals(agent.getName())) {
                return agent;
            }
        }
        return null;
    }

    /**
     * Translates a {@link MemoryDistillationResult} into a list of domain events,
     * enforcing the net memory limit.
     *
     * <p>The constraint {@code stored.size() - revoked.size() <= maxNewMemories} is
     * enforced by truncating stored memories when the limit would be exceeded.
     *
     * @param result           the distillation result from the agent
     * @param currentMemories  the current memories from the aggregate state
     * @param maxNewMemories   the maximum number of net new memories allowed
     * @return an unmodifiable list of domain events
     */
    private List<DomainEvent> translateDistillationResult(MemoryDistillationResult result,
                                                          List<AgenticAggregateMemory> currentMemories,
                                                          int maxNewMemories) {
        List<DomainEvent> events = new ArrayList<>();

        // Collect valid revocations first (only for memories that actually exist),
        // de-duplicated by memoryId so capacity calculations match effective revocations.
        Set<String> currentMemoryIds = new HashSet<>();
        for (AgenticAggregateMemory mem : currentMemories) {
            currentMemoryIds.add(mem.memoryId());
        }

        List<MemoryRevokedEvent> validRevocations = new ArrayList<>();
        Set<String> revokedMemoryIds = new HashSet<>();
        if (result.revoked() != null) {
            for (MemoryRevokedEvent revoked : result.revoked()) {
                String memoryId = revoked.memoryId();
                if (memoryId == null || !currentMemoryIds.contains(memoryId)) {
                    logger.debug("Skipping revocation of non-existent memory '{}' for aggregate {}",
                            memoryId, getName());
                    continue;
                }
                if (!revokedMemoryIds.add(memoryId)) {
                    logger.debug("Skipping duplicate revocation of memory '{}' for aggregate {}",
                            memoryId, getName());
                    continue;
                }
                validRevocations.add(revoked);
            }
        }

        // Calculate how many stored memories we can accept
        int revokedCount = revokedMemoryIds.size();
        int maxStoredCount = maxNewMemories + revokedCount;

        // Add revocation events
        events.addAll(validRevocations);

        // Add stored events (respecting the limit)
        if (result.stored() != null) {
            int storedCount = 0;
            for (MemoryStoredEvent stored : result.stored()) {
                if (storedCount >= maxStoredCount) {
                    logger.debug("Memory distillation limit reached ({} stored, {} revoked, max {}); "
                                    + "truncating remaining stored memories for aggregate {}",
                            storedCount, revokedCount, maxStoredCount, getName());
                    break;
                }
                events.add(stored);
                storedCount++;
            }
        }

        logger.debug("Memory distillation for aggregate {} produced {} stored and {} revoked events",
                getName(), events.size() - validRevocations.size(), validRevocations.size());

        return List.copyOf(events);
    }
}
