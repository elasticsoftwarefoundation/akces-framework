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
import org.elasticsoftware.akces.agentic.commands.AssignTaskCommand;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.elasticsoftware.akces.agentic.AgenticAggregateRuntime.AGENT_TASK_ASSIGNED_TYPE;
import static org.elasticsoftware.akces.agentic.AgenticAggregateRuntime.ASSIGN_TASK_COMMAND_TYPE;

/**
 * Built-in {@link CommandHandlerFunction} that processes
 * {@link AssignTaskCommand} by creating an Embabel {@link AgentProcess} and
 * emitting an {@link AgentTaskAssignedEvent}.
 *
 * <p>This handler needs runtime access to the Embabel {@link AgentPlatform} and
 * {@link Agent}, so it cannot use the simple static method reference pattern used
 * by the memory event-sourcing handlers. Instead, it is a dedicated class that
 * captures these dependencies at construction time.
 *
 * <p>The handler populates the agent's blackboard with the task description,
 * requesting party, and current aggregate state, then creates an agent process.
 * The process ID from {@link AgentProcess#getId()} is included in the emitted event,
 * linking the Akces domain to the Embabel runtime.
 *
 * @param <S> the aggregate state type; must implement {@link AggregateState}
 */
public class AssignTaskCommandHandler<S extends AggregateState>
        implements CommandHandlerFunction<S, AssignTaskCommand, DomainEvent> {

    private static final Logger logger =
            LoggerFactory.getLogger(AssignTaskCommandHandler.class);

    private final AgenticAggregate<S> aggregate;
    private final AgentPlatform agentPlatform;
    private final String aggregateName;

    /**
     * Creates a new {@code AssignTaskCommandHandler}.
     *
     * @param aggregate     the owning agentic aggregate instance
     * @param agentPlatform the Embabel platform used to create agent processes
     * @param aggregateName the name of the aggregate (used for agent resolution)
     */
    public AssignTaskCommandHandler(
            AgenticAggregate<S> aggregate,
            AgentPlatform agentPlatform,
            String aggregateName) {
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate must not be null");
        this.agentPlatform = Objects.requireNonNull(agentPlatform, "agentPlatform must not be null");
        this.aggregateName = Objects.requireNonNull(aggregateName, "aggregateName must not be null");
    }

    /**
     * Processes the {@link AssignTaskCommand} by creating an Embabel {@link AgentProcess}
     * and emitting an {@link AgentTaskAssignedEvent}.
     *
     * <p>The agent process is created with bindings containing the task description,
     * requesting party information, and the current aggregate state. The handler resolves
     * the appropriate {@link Agent} by name from the platform.
     *
     * @param command the assign-task command; never {@code null}
     * @param state   the current aggregate state
     * @return a stream containing a single {@link AgentTaskAssignedEvent}
     */
    @Nonnull
    @Override
    public Stream<DomainEvent> apply(@Nonnull AssignTaskCommand command, S state) {
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

        // Resolve the agent for this aggregate
        Agent agent = resolveAgent();

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
     * {@inheritDoc}
     *
     * <p>Always returns {@code false} — built-in commands cannot create aggregate state.
     */
    @Override
    public boolean isCreate() {
        return false;
    }

    @Override
    public CommandType<AssignTaskCommand> getCommandType() {
        return ASSIGN_TASK_COMMAND_TYPE;
    }

    @Override
    public Aggregate<S> getAggregate() {
        return aggregate;
    }

    @Override
    public List<DomainEventType<DomainEvent>> getProducedDomainEventTypes() {
        @SuppressWarnings("unchecked")
        DomainEventType<DomainEvent> type = (DomainEventType<DomainEvent>) (DomainEventType<?>) AGENT_TASK_ASSIGNED_TYPE;
        return List.of(type);
    }

    @Override
    public List<DomainEventType<DomainEvent>> getErrorEventTypes() {
        return List.of();
    }

    /**
     * Resolves the {@link Agent} for the aggregate from the platform's registered agents.
     *
     * <p>Looks for an agent matching either the exact aggregate name or the
     * {@code {aggregateName}Agent} convention.
     *
     * @return the resolved {@link Agent}; never {@code null}
     * @throws IllegalStateException if no matching agent is found
     */
    private Agent resolveAgent() {
        String agentBeanName = aggregateName + "Agent";
        for (Agent candidate : agentPlatform.agents()) {
            String candidateName = candidate.getName();
            if (aggregateName.equals(candidateName) || agentBeanName.equals(candidateName)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No Agent found with name '" + aggregateName + "' or '" + agentBeanName
                        + "' in the AgentPlatform for AssignTask handling. "
                        + "The implementing application must provide an Agent named '"
                        + agentBeanName + "'.");
    }
}
