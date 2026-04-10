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

import com.embabel.agent.api.common.autonomy.AgentProcessExecution;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.api.common.autonomy.GoalChoiceApprover;
import com.embabel.agent.api.common.autonomy.ProcessExecutionException;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ProcessOptions;
import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An {@link EventHandlerFunction} implementation that routes an agent-handled external
 * domain event through the Embabel AI agent framework instead of a deterministic
 * {@code @EventHandler} method.
 *
 * <p>When {@link #apply(DomainEvent, AggregateState)} is called the adapter:
 * <ol>
 *   <li>Assembles a bindings {@link Map} containing the event, current aggregate state,
 *       memories, aggregate service records, and condition flags.</li>
 *   <li>Attempts to resolve an {@link Agent} from the {@link AgentPlatform} by matching
 *       the aggregate name against deployed agent names (exact match or
 *       {@code {aggregateName}Agent} suffix match).</li>
 *   <li>If a matching agent is found, creates an {@link AgentProcess} via
 *       {@link AgentPlatform#createAgentProcess(Agent, ProcessOptions, Map)} and calls
 *       {@link AgentProcess#tick()} in a loop until the process reaches an end state
 *       (completed, failed, terminated, or killed).</li>
 *   <li>If no matching agent is found, falls back to
 *       {@link Autonomy#chooseAndAccomplishGoal} to let the Embabel platform decide
 *       on the best agent and goal combination.</li>
 *   <li>Collects {@link DomainEvent} objects placed on the agent's blackboard via
 *       {@link AgentProcessResultTranslator#collectEvents} and returns them as a
 *       {@link Stream}.</li>
 * </ol>
 *
 * <p>{@link #isCreate()} always returns {@code false} — agent-handled events cannot
 * create aggregate state. Every agentic aggregate must have a separate deterministic
 * {@code @CommandHandler(create = true)} method.
 *
 * <p>The returned stream of events is fed back into the standard
 * {@code KafkaAggregateRuntime.handleEvent()} flow unchanged, where each event is
 * applied through the registered {@code @EventSourcingHandler} methods.
 *
 * @param <S>          the aggregate state type; must implement {@link AggregateState}
 * @param <InputEvent> the external domain event type handled by this adapter
 * @param <E>          the domain event type produced by this adapter
 */
public class AgenticEventHandlerFunctionAdapter<S extends AggregateState, InputEvent extends DomainEvent, E extends DomainEvent>
        implements EventHandlerFunction<S, InputEvent, E> {

    private static final Logger logger =
            LoggerFactory.getLogger(AgenticEventHandlerFunctionAdapter.class);

    private final AgenticAggregate<S> aggregate;
    private final String aggregateName;
    private final DomainEventType<InputEvent> eventType;
    private final AgentPlatform agentPlatform;
    private final List<DomainEventType<E>> producedDomainEventTypes;
    private final List<DomainEventType<E>> errorEventTypes;
    private final Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier;

    /**
     * Creates a new {@code AgenticEventHandlerFunctionAdapter}.
     *
     * @param aggregate                  the owning agentic aggregate instance
     * @param aggregateName              the aggregate name used to infer the matching
     *                                   {@link Agent} from the {@link AgentPlatform}
     * @param eventType                  the external domain event type this adapter handles
     * @param agentPlatform              the Embabel platform used to create agent processes
     * @param producedDomainEventTypes   domain event types this adapter may produce
     * @param errorEventTypes            error event types this adapter may produce
     * @param aggregateServicesSupplier  supplier of all known {@link AggregateServiceRecord}s;
     *                                   used to populate the blackboard for service discovery
     */
    public AgenticEventHandlerFunctionAdapter(
            AgenticAggregate<S> aggregate,
            String aggregateName,
            DomainEventType<InputEvent> eventType,
            AgentPlatform agentPlatform,
            List<DomainEventType<E>> producedDomainEventTypes,
            List<DomainEventType<E>> errorEventTypes,
            Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier) {
        this.aggregate = aggregate;
        this.aggregateName = aggregateName;
        this.eventType = eventType;
        this.agentPlatform = agentPlatform;
        this.producedDomainEventTypes = List.copyOf(producedDomainEventTypes);
        this.errorEventTypes = List.copyOf(errorEventTypes);
        this.aggregateServicesSupplier = aggregateServicesSupplier;
    }

    /**
     * Processes the external domain event through the Embabel AI agent framework.
     *
     * <p>Populates the agent blackboard with:
     * <ul>
     *   <li>{@code "event"} — the external domain event being processed</li>
     *   <li>{@code "state"} — the current aggregate state</li>
     *   <li>{@code "agenticAggregateId"} — the aggregate identifier</li>
     *   <li>{@code "memories"} — the list of current memories from state</li>
     *   <li>{@code "aggregateServices"} — all known aggregate service records</li>
     *   <li>{@code "isCommandProcessing"} (condition) — {@code false}</li>
     *   <li>{@code "isExternalEvent"} (condition) — {@code true}</li>
     *   <li>{@code "hasMemories"} (condition) — whether any memories are present</li>
     * </ul>
     *
     * @param event the external domain event to process; never {@code null}
     * @param state the current aggregate state
     * @return a stream of domain events produced by the agent; may be empty
     */
    @Nonnull
    @Override
    @SuppressWarnings("unchecked")
    public Stream<E> apply(@Nonnull InputEvent event, S state) {
        logger.debug("Processing agent-handled external event {} for aggregate {}",
                eventType.typeName(), aggregateName);

        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("event", event);
        bindings.put("state", state);
        bindings.put("agenticAggregateId", state.getAggregateId());
        List<AgenticAggregateMemory> memories = state instanceof MemoryAwareState mas
                ? mas.getMemories()
                : List.of();
        bindings.put("memories", memories);
        bindings.put("aggregateServices", aggregateServicesSupplier.get());
        bindings.put("isCommandProcessing", false);
        bindings.put("isExternalEvent", true);
        bindings.put("hasMemories", !memories.isEmpty());

        Optional<Agent> resolvedAgent =
                AgenticCommandHandlerFunctionAdapter.resolveAgentByName(agentPlatform, aggregateName);
        AgentProcess agentProcess;

        if (resolvedAgent.isPresent()) {
            logger.debug("Resolved agent '{}' for aggregate '{}'",
                    resolvedAgent.get().getName(), aggregateName);
            agentProcess = agentPlatform.createAgentProcess(
                    resolvedAgent.get(), ProcessOptions.DEFAULT, bindings);
            tickToCompletion(agentProcess);
        } else {
            logger.info("No agent found matching aggregate name '{}'; falling back to Autonomy",
                    aggregateName);
            Autonomy autonomy = agentPlatform.getPlatformServices().autonomy();
            try {
                AgentProcessExecution execution = autonomy.chooseAndAccomplishGoal(
                        GoalChoiceApprover.Companion.getAPPROVE_ALL(), agentPlatform, bindings);
                agentProcess = execution.getAgentProcess();
            } catch (ProcessExecutionException e) {
                logger.error("Autonomy fallback failed for event {} on aggregate {}",
                        eventType.typeName(), aggregateName, e);
                throw new IllegalStateException(
                        "Autonomy fallback failed for event " + eventType.typeName()
                                + " on aggregate " + aggregateName, e);
            }
        }

        logger.debug("Agent process completed with status {} for event {} on aggregate {}",
                agentProcess.getStatus(), eventType.typeName(), aggregateName);

        return (Stream<E>) AgentProcessResultTranslator
                .collectEvents(agentProcess.getBlackboard(), getAllRegisteredEventTypes())
                .stream();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns {@code false} — agent-handled external events cannot create
     * aggregate state.
     */
    @Override
    public boolean isCreate() {
        return false;
    }

    @Override
    public DomainEventType<InputEvent> getEventType() {
        return eventType;
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

    /**
     * Ticks an {@link AgentProcess} to completion with defensive limits so a stuck agent
     * process cannot block event handling indefinitely.
     *
     * @param agentProcess the agent process to drive to completion
     * @throws IllegalStateException if the process does not finish within the safety limits
     */
    private void tickToCompletion(AgentProcess agentProcess) {
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
                    "Agent process did not finish within safety limits for event {} on aggregate {}. " +
                            "tickCount={}, maxTicks={}, timeoutSeconds={}, status={}",
                    eventType.typeName(),
                    aggregateName,
                    tickCount,
                    maxTicks,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(timeoutNanos),
                    agentProcess.getStatus());
            throw new IllegalStateException(
                    "Agent process exceeded execution limits for event " + eventType.typeName()
                            + " on aggregate " + aggregateName);
        }
    }
}
