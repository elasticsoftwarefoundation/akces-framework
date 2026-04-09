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
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
 *   <li>Creates an {@link AgentProcess} via
 *       {@link AgentPlatform#createAgentProcess(Agent, ProcessOptions, Map)} using the
 *       provided {@link Agent} and the assembled bindings.</li>
 *   <li>Calls {@link AgentProcess#tick()} in a loop until the process reaches an end
 *       state (completed, failed, terminated, or killed).</li>
 *   <li>Collects {@link DomainEvent} objects placed on the agent's blackboard via
 *       {@link AgentProcessResultTranslator#collectEvents} and returns them as a
 *       {@link Stream}.</li>
 * </ol>
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
    private final CommandType<C> commandType;
    private final AgentPlatform agentPlatform;
    private final Agent agent;
    private final List<DomainEventType<E>> producedDomainEventTypes;
    private final List<DomainEventType<E>> errorEventTypes;
    private final Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier;

    /**
     * Creates a new {@code AgenticCommandHandlerFunctionAdapter}.
     *
     * @param aggregate                  the owning agentic aggregate instance
     * @param commandType                the command type this adapter handles
     * @param agentPlatform              the Embabel platform used to create agent processes
     * @param agent                      the deployed {@link Agent} for this aggregate,
     *                                   provided by the implementing application
     * @param producedDomainEventTypes   domain event types this adapter may produce
     * @param errorEventTypes            error event types this adapter may produce
     * @param aggregateServicesSupplier  supplier of all known {@link AggregateServiceRecord}s;
     *                                   used to populate the blackboard for service discovery
     */
    public AgenticCommandHandlerFunctionAdapter(
            AgenticAggregate<S> aggregate,
            CommandType<C> commandType,
            AgentPlatform agentPlatform,
            Agent agent,
            List<DomainEventType<E>> producedDomainEventTypes,
            List<DomainEventType<E>> errorEventTypes,
            Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier) {
        this.aggregate = aggregate;
        this.commandType = commandType;
        this.agentPlatform = agentPlatform;
        this.agent = agent;
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
     * @return a stream of domain events produced by the agent; may be empty
     */
    @Nonnull
    @Override
    @SuppressWarnings("unchecked")
    public Stream<E> apply(@Nonnull C command, S state) {
        logger.debug("Processing agent-handled command {} for aggregate {}",
                commandType.typeName(), aggregate.getClass().getSimpleName());

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

        AgentProcess agentProcess =
                agentPlatform.createAgentProcess(agent, ProcessOptions.DEFAULT, bindings);

        // Tick to completion with defensive limits so a stuck agent process cannot
        // block command handling indefinitely.
        final long maxTicks = 10_000L;
        final long timeoutNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        final long deadlineNanos = System.nanoTime() + timeoutNanos;
        long tickCount = 0L;

        while (!agentProcess.getFinished()
                && tickCount < maxTicks
                && System.nanoTime() < deadlineNanos) {
            agentProcess.tick();
            tickCount++;
        }

        if (!agentProcess.getFinished()) {
            logger.error(
                    "Agent process did not finish within safety limits for command {} on aggregate {}. " +
                            "aggregateId={}, tickCount={}, maxTicks={}, timeoutSeconds={}, status={}",
                    commandType.typeName(),
                    aggregate.getClass().getSimpleName(),
                    state.getAggregateId(),
                    tickCount,
                    maxTicks,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(timeoutNanos),
                    agentProcess.getStatus());
            throw new IllegalStateException(
                    "Agent process exceeded execution limits for command " + commandType.typeName()
                            + " on aggregate " + aggregate.getClass().getSimpleName());
        }

        logger.debug("Agent process completed with status {} for command {} on aggregate {}",
                agentProcess.getStatus(), commandType.typeName(), aggregate.getClass().getSimpleName());

        return (Stream<E>) AgentProcessResultTranslator
                .collectEvents(agentProcess.getBlackboard(), getAllRegisteredEventTypes())
                .stream();
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
     * Returns all domain event types this adapter may produce (both state-changing and error types).
     * Used by {@link AgentProcessResultTranslator} to filter out unknown {@link org.elasticsoftware.akces.events.ErrorEvent}
     * instances that are not registered with the runtime.
     *
     * @return combined list of produced and error domain event types
     */
    private List<DomainEventType<?>> getAllRegisteredEventTypes() {
        List<DomainEventType<?>> all = new ArrayList<>(producedDomainEventTypes.size() + errorEventTypes.size());
        all.addAll(producedDomainEventTypes);
        all.addAll(errorEventTypes);
        return all;
    }
}
