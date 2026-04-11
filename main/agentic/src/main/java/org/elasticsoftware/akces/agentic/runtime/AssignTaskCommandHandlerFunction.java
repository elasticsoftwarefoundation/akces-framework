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
import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.agentic.commands.AssignTaskCommand;
import org.elasticsoftware.akces.agentic.embabel.DefaultAgent;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A {@link CommandHandlerFunction} implementation for the built-in
 * {@link AssignTaskCommand}.
 *
 * <p>When {@link #apply(Command, AggregateState)} is called the handler:
 * <ol>
 *   <li>Assembles a bindings {@link Map} containing the command, current aggregate state,
 *       memories, aggregate service records, and condition flags (following the same
 *       pattern as {@link AgenticCommandHandlerFunctionAdapter}).</li>
 *   <li>Resolves an {@link Agent} from the {@link AgentPlatform} by matching
 *       the aggregate name against deployed agent names (exact match,
 *       {@code {aggregateName}Agent} suffix, or {@link DefaultAgent} fallback).</li>
 *   <li>Creates an {@link AgentProcess} via
 *       {@link AgentPlatform#createAgentProcess(Agent, ProcessOptions, Map)}.</li>
 *   <li>Emits an {@link AgentTaskAssignedEvent} containing the process ID so that
 *       the event-sourcing handler can record the task in
 *       {@link TaskAwareState}.</li>
 * </ol>
 *
 * <p>The handler does <strong>not</strong> tick the newly created process. Instead, the
 * partition's idle-poll cycle ({@code resumeAgentTasks}) exclusively drives all
 * {@link AgentProcess} advancement.
 *
 * <p>This handler is always registered as a built-in command handler on every agentic
 * aggregate. Unlike {@link AgenticCommandHandlerFunctionAdapter} (which delegates
 * entirely to the agent), this handler also performs framework-level bookkeeping by
 * emitting the {@link AgentTaskAssignedEvent}.
 *
 * @see AgenticCommandHandlerFunctionAdapter
 */
public class AssignTaskCommandHandlerFunction
        implements CommandHandlerFunction<AggregateState, Command, DomainEvent> {

    private static final Logger logger =
            LoggerFactory.getLogger(AssignTaskCommandHandlerFunction.class);

    private final AgenticAggregate<?> aggregate;
    private final String aggregateName;
    private final AgentPlatform agentPlatform;
    private final List<DomainEventType<?>> producedEventTypes;
    private final List<DomainEventType<?>> errorEventTypes;
    private final Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier;

    /**
     * Creates a new {@code AssignTaskCommandHandlerFunction}.
     *
     * @param aggregate                  the owning agentic aggregate instance
     * @param aggregateName              the aggregate name used for agent resolution
     * @param agentPlatform              the Embabel platform used to create agent processes
     * @param producedEventTypes         domain event types this handler may produce
     * @param errorEventTypes            error event types this handler may produce
     * @param aggregateServicesSupplier  supplier of all known {@link AggregateServiceRecord}s;
     *                                   used to populate the blackboard for service discovery
     */
    public AssignTaskCommandHandlerFunction(
            AgenticAggregate<?> aggregate,
            String aggregateName,
            AgentPlatform agentPlatform,
            List<DomainEventType<?>> producedEventTypes,
            List<DomainEventType<?>> errorEventTypes,
            Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier) {
        this.aggregate = aggregate;
        this.aggregateName = aggregateName;
        this.agentPlatform = agentPlatform;
        this.producedEventTypes = List.copyOf(producedEventTypes);
        this.errorEventTypes = List.copyOf(errorEventTypes);
        this.aggregateServicesSupplier = aggregateServicesSupplier;
    }

    /**
     * Processes the {@link AssignTaskCommand} through the Embabel AI agent framework.
     *
     * <p>Populates the agent blackboard with:
     * <ul>
     *   <li>{@code "command"} — the command being processed</li>
     *   <li>{@code "state"} — the current aggregate state</li>
     *   <li>{@code "agenticAggregateId"} — the aggregate identifier</li>
     *   <li>{@code "taskDescription"} — the task description from the command</li>
     *   <li>{@code "requestingParty"} — the requesting party from the command</li>
     *   <li>{@code "taskMetadata"} — optional task metadata (when present)</li>
     *   <li>{@code "memories"} — the list of current memories from state</li>
     *   <li>{@code "aggregateServices"} — all known aggregate service records</li>
     *   <li>{@code "isCommandProcessing"} (condition) — {@code true}</li>
     *   <li>{@code "isExternalEvent"} (condition) — {@code false}</li>
     *   <li>{@code "isTaskAssignment"} (condition) — {@code true}</li>
     *   <li>{@code "hasMemories"} (condition) — whether any memories are present</li>
     * </ul>
     *
     * @param command the command to process; must be an {@link AssignTaskCommand}
     * @param state   the current aggregate state
     * @return a stream containing the {@link AgentTaskAssignedEvent}
     */
    @Nonnull
    @Override
    public Stream<DomainEvent> apply(@Nonnull Command command, AggregateState state) {
        if (!(command instanceof AssignTaskCommand assignTask)) {
            throw new IllegalArgumentException(
                    "AssignTaskCommandHandlerFunction only handles AssignTaskCommand, got: "
                            + command.getClass().getName());
        }

        logger.debug("Processing AssignTask command for aggregate {}, taskDescription='{}'",
                aggregateName, assignTask.taskDescription());

        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("command", assignTask);
        bindings.put("state", state);
        bindings.put("agenticAggregateId", assignTask.agenticAggregateId());
        bindings.put("taskDescription", assignTask.taskDescription());
        bindings.put("requestingParty", assignTask.requestingParty());
        if (assignTask.taskMetadata() != null) {
            bindings.put("taskMetadata", assignTask.taskMetadata());
        }
        List<AgenticAggregateMemory> memories = state instanceof MemoryAwareState mas
                ? mas.getMemories()
                : List.of();
        bindings.put("memories", memories);
        bindings.put("aggregateServices", aggregateServicesSupplier.get());
        bindings.put("isCommandProcessing", true);
        bindings.put("isExternalEvent", false);
        bindings.put("isTaskAssignment", true);
        bindings.put("hasMemories", !memories.isEmpty());

        Agent resolvedAgent = AgenticCommandHandlerFunctionAdapter.resolveAgentByName(
                agentPlatform, aggregateName);
        logger.debug("Resolved agent '{}' for aggregate '{}'",
                resolvedAgent.getName(), aggregateName);

        AgentProcess agentProcess = agentPlatform.createAgentProcess(
                resolvedAgent, ProcessOptions.DEFAULT, bindings);

        String processId = agentProcess.getId();
        logger.debug("Created AgentProcess with id={} for AssignTask on aggregate {}",
                processId, aggregateName);

        AgentTaskAssignedEvent assignedEvent = new AgentTaskAssignedEvent(
                assignTask.agenticAggregateId(),
                processId,
                assignTask.taskDescription(),
                assignTask.requestingParty(),
                assignTask.taskMetadata(),
                Instant.now());

        // The process is NOT ticked here. The partition's idle-poll cycle
        // (resumeAgentTasks) exclusively drives all AgentProcess advancement.
        return Stream.of(assignedEvent);
    }

    @Override
    public boolean isCreate() {
        return false;
    }

    @SuppressWarnings("unchecked")
    @Override
    public CommandType<Command> getCommandType() {
        return (CommandType<Command>) (CommandType<?>) AgenticAggregateRuntime.ASSIGN_TASK_COMMAND_TYPE;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Aggregate<AggregateState> getAggregate() {
        return (Aggregate<AggregateState>) (Aggregate<?>) aggregate;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<DomainEventType<DomainEvent>> getProducedDomainEventTypes() {
        return producedEventTypes.stream()
                .map(t -> (DomainEventType<DomainEvent>) (DomainEventType<?>) t)
                .toList();
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<DomainEventType<DomainEvent>> getErrorEventTypes() {
        return errorEventTypes.stream()
                .map(t -> (DomainEventType<DomainEvent>) (DomainEventType<?>) t)
                .toList();
    }
}
