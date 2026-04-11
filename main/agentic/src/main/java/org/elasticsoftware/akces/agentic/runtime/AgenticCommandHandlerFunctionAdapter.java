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
import org.elasticsoftware.akces.agentic.embabel.DefaultAgent;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A {@link CommandHandlerFunction} implementation that routes an agent-handled command
 * through the Embabel AI agent framework instead of a deterministic
 * {@code @CommandHandler} method.
 *
 * <p>When {@link #apply(Command, AggregateState)} is called the adapter:
 * <ol>
 *   <li>Assembles a bindings {@link Map} containing the command, current aggregate state,
 *       memories, aggregate service records, and condition flags.</li>
 *   <li>Attempts to resolve an {@link Agent} from the {@link AgentPlatform} by matching
 *       the aggregate name against deployed agent names (exact match or
 *       {@code {aggregateName}Agent} suffix match). If no match is found, the
 *       {@link DefaultAgent} is used as a fallback.</li>
 *   <li>Creates an {@link AgentProcess} via
 *       {@link AgentPlatform#createAgentProcess(Agent, ProcessOptions, Map)}.</li>
 * </ol>
 *
 * <p>The adapter does <strong>not</strong> tick the newly created process. Instead, the
 * partition's idle-poll cycle ({@code resumeAgentTasks}) exclusively drives all
 * {@link AgentProcess} advancement.
 *
 * <p>{@link #isCreate()} always returns {@code false} — agent-handled commands cannot
 * create aggregate state. Every agentic aggregate must have a separate deterministic
 * {@code @CommandHandler(create = true)} method.
 *
 * <p>The returned stream of events is fed back into the standard
 * {@code KafkaAggregateRuntime.handleCommand()} flow unchanged, where each event is
 * applied through the registered {@code @EventSourcingHandler} methods.
 *
 * @param <S> the aggregate state type; must implement {@link AggregateState}
 * @param <C> the command type handled by this adapter
 * @param <E> the domain event type produced by this adapter
 */
public class AgenticCommandHandlerFunctionAdapter<S extends AggregateState, C extends Command, E extends DomainEvent>
        implements CommandHandlerFunction<S, C, E> {

    private static final Logger logger =
            LoggerFactory.getLogger(AgenticCommandHandlerFunctionAdapter.class);

    private final AgenticAggregate<S> aggregate;
    private final String aggregateName;
    private final CommandType<C> commandType;
    private final AgentPlatform agentPlatform;
    private final List<DomainEventType<E>> producedDomainEventTypes;
    private final List<DomainEventType<E>> errorEventTypes;
    private final Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier;

    /**
     * Creates a new {@code AgenticCommandHandlerFunctionAdapter}.
     *
     * @param aggregate                  the owning agentic aggregate instance
     * @param aggregateName              the aggregate name used to infer the matching
     *                                   {@link Agent} from the {@link AgentPlatform}
     * @param commandType                the command type this adapter handles
     * @param agentPlatform              the Embabel platform used to create agent processes
     * @param producedDomainEventTypes   domain event types this adapter may produce
     * @param errorEventTypes            error event types this adapter may produce
     * @param aggregateServicesSupplier  supplier of all known {@link AggregateServiceRecord}s;
     *                                   used to populate the blackboard for service discovery
     */
    public AgenticCommandHandlerFunctionAdapter(
            AgenticAggregate<S> aggregate,
            String aggregateName,
            CommandType<C> commandType,
            AgentPlatform agentPlatform,
            List<DomainEventType<E>> producedDomainEventTypes,
            List<DomainEventType<E>> errorEventTypes,
            Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier) {
        this.aggregate = aggregate;
        this.aggregateName = aggregateName;
        this.commandType = commandType;
        this.agentPlatform = agentPlatform;
        this.producedDomainEventTypes = List.copyOf(producedDomainEventTypes);
        this.errorEventTypes = List.copyOf(errorEventTypes);
        this.aggregateServicesSupplier = aggregateServicesSupplier;
    }

    /**
     * Processes the command through the Embabel AI agent framework.
     *
     * <p>Populates the agent blackboard with:
     * <ul>
     *   <li>{@code "command"} — the command being processed</li>
     *   <li>{@code "state"} — the current aggregate state</li>
     *   <li>{@code "agenticAggregateId"} — the aggregate identifier</li>
     *   <li>{@code "memories"} — the list of current memories from state</li>
     *   <li>{@code "aggregateServices"} — all known aggregate service records</li>
     *   <li>{@code "isCommandProcessing"} (condition) — {@code true}</li>
     *   <li>{@code "isExternalEvent"} (condition) — {@code false}</li>
     *   <li>{@code "hasMemories"} (condition) — whether any memories are present</li>
     * </ul>
     *
     * @param command the command to process; never {@code null}
     * @param state   the current aggregate state
     * @return an empty stream — the process is advanced by {@code resumeAgentTasks}
     */
    @Nonnull
    @Override
    @SuppressWarnings("unchecked")
    public Stream<E> apply(@Nonnull C command, S state) {
        logger.debug("Processing agent-handled command {} for aggregate {}",
                commandType.typeName(), aggregateName);

        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("command", command);
        bindings.put("state", state);
        bindings.put("agenticAggregateId", state.getAggregateId());
        List<AgenticAggregateMemory> memories = state instanceof MemoryAwareState mas
                ? mas.getMemories()
                : List.of();
        bindings.put("memories", memories);
        bindings.put("aggregateServices", aggregateServicesSupplier.get());
        bindings.put("isCommandProcessing", true);
        bindings.put("isExternalEvent", false);
        bindings.put("hasMemories", !memories.isEmpty());

        Agent resolvedAgent = resolveAgentByName(agentPlatform, aggregateName);
        logger.debug("Resolved agent '{}' for aggregate '{}'",
                resolvedAgent.getName(), aggregateName);
        AgentProcess agentProcess = agentPlatform.createAgentProcess(
                resolvedAgent, ProcessOptions.DEFAULT, bindings);

        logger.debug("Created AgentProcess with id={} for command {} on aggregate {}",
                agentProcess.getId(), commandType.typeName(), aggregateName);

        // The process is NOT ticked here. The partition's idle-poll cycle
        // (resumeAgentTasks) exclusively drives all AgentProcess advancement.
        return (Stream<E>) Stream.<DomainEvent>empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns {@code false} — agent-handled commands cannot create aggregate
     * state. A deterministic {@code @CommandHandler(create = true)} must exist on the
     * aggregate class to handle creation.
     */
    @Override
    public boolean isCreate() {
        return false;
    }

    @Override
    public CommandType<C> getCommandType() {
        return commandType;
    }

    @Override
    public Aggregate<S> getAggregate() {
        return aggregate;
    }

    @Override
    public List<DomainEventType<E>> getProducedDomainEventTypes() {
        return producedDomainEventTypes;
    }

    @Override
    public List<DomainEventType<E>> getErrorEventTypes() {
        return errorEventTypes;
    }

    /**
     * Resolves an {@link Agent} from the {@link AgentPlatform} by matching deployed agent
     * names against the given aggregate name. If no match is found, the
     * {@link DefaultAgent} is returned as a fallback.
     *
     * <p>Matching rules (checked in order):
     * <ol>
     *   <li>Exact match: {@code agent.getName().equals(aggregateName)}</li>
     *   <li>Agent-suffix match: {@code agent.getName().equals(aggregateName + "Agent")}</li>
     *   <li>Default: the {@link DefaultAgent} identified by {@link DefaultAgent#AGENT_NAME}</li>
     * </ol>
     *
     * @param platform      the agent platform containing deployed agents
     * @param aggregateName the aggregate name to match against
     * @return the matched or default agent; never {@code null}
     * @throws IllegalStateException if the {@link DefaultAgent} is not deployed on the platform
     */
    static Agent resolveAgentByName(AgentPlatform platform, String aggregateName) {
        String suffixName = aggregateName + "Agent";
        Collection<Agent> agents = platform.agents();
        Agent suffixMatch = null;
        Agent defaultAgentMatch = null;

        for (Agent agent : agents) {
            String agentName = agent.getName();
            if (agentName.equals(aggregateName)) {
                return agent;
            }
            if (suffixMatch == null && agentName.equals(suffixName)) {
                suffixMatch = agent;
            }
            if (defaultAgentMatch == null && agentName.equals(DefaultAgent.AGENT_NAME)) {
                defaultAgentMatch = agent;
            }
        }

        if (suffixMatch != null) {
            return suffixMatch;
        }

        // No name match — fall back to the DefaultAgent
        if (defaultAgentMatch != null) {
            logger.info("No agent found matching aggregate name '{}'; using default agent '{}'",
                    aggregateName, defaultAgentMatch.getName());
            return defaultAgentMatch;
        }

        throw new IllegalStateException(
                "No DefaultAgent ('" + DefaultAgent.AGENT_NAME + "') is deployed on the AgentPlatform. "
                        + "At least the DefaultAgent must be available to handle aggregate '"
                        + aggregateName + "'.");
    }
}
