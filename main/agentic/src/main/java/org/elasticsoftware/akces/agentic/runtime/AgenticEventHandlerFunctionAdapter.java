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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final DomainEventType<InputEvent> eventType;
    private final AgentPlatform agentPlatform;
    private final Agent agent;
    private final List<DomainEventType<E>> producedDomainEventTypes;
    private final List<DomainEventType<E>> errorEventTypes;
    private final Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier;

    /**
     * Creates a new {@code AgenticEventHandlerFunctionAdapter}.
     *
     * @param aggregate                  the owning agentic aggregate instance
     * @param eventType                  the external domain event type this adapter handles
     * @param agentPlatform              the Embabel platform used to create agent processes
     * @param agent                      the deployed {@link Agent} for this aggregate,
     *                                   provided by the implementing application
     * @param producedDomainEventTypes   domain event types this adapter may produce
     * @param errorEventTypes            error event types this adapter may produce
     * @param aggregateServicesSupplier  supplier of all known {@link AggregateServiceRecord}s;
     *                                   used to populate the blackboard for service discovery
     */
    public AgenticEventHandlerFunctionAdapter(
            AgenticAggregate<S> aggregate,
            DomainEventType<InputEvent> eventType,
            AgentPlatform agentPlatform,
            Agent agent,
            List<DomainEventType<E>> producedDomainEventTypes,
            List<DomainEventType<E>> errorEventTypes,
            Supplier<Collection<AggregateServiceRecord>> aggregateServicesSupplier) {
        this.aggregate = aggregate;
        this.eventType = eventType;
        this.agentPlatform = agentPlatform;
        this.agent = agent;
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
                eventType.typeName(), aggregate.getClass().getSimpleName());

        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("event", event);
        bindings.put("state", state);
        bindings.put("agenticAggregateId", state.getAggregateId());
        bindings.put("memories", state.getMemories());
        bindings.put("aggregateServices", aggregateServicesSupplier.get());
        bindings.put("isCommandProcessing", false);
        bindings.put("isExternalEvent", true);
        bindings.put("hasMemories", !state.getMemories().isEmpty());

        AgentProcess agentProcess =
                agentPlatform.createAgentProcess(agent, ProcessOptions.DEFAULT, bindings);

        // Tick to completion with defensive limits so a stuck agent process cannot
        // block external event handling indefinitely.
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
                            "aggregateId={}, tickCount={}, maxTicks={}, timeoutSeconds={}, status={}",
                    eventType.typeName(),
                    aggregate.getClass().getSimpleName(),
                    state.getAggregateId(),
                    tickCount,
                    maxTicks,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(timeoutNanos),
                    agentProcess.getStatus());
            throw new IllegalStateException(
                    "Agent process exceeded execution limits for event " + eventType.typeName()
                            + " on aggregate " + aggregate.getClass().getSimpleName());
        }
        logger.debug("Agent process completed with status {} for event {} on aggregate {}",
                agentProcess.getStatus(), eventType.typeName(), aggregate.getClass().getSimpleName());

        return (Stream<E>) AgentProcessResultTranslator
                .collectEvents(agentProcess.getBlackboard())
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
}
