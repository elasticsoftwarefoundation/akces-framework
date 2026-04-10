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

import com.embabel.agent.core.AgentProcess;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;

import java.util.Collection;
import java.util.stream.Stream;

/**
 * Utility class that runs a single {@link AgentProcess#tick()} and afterwards collects
 * the {@link DomainEvent}s that were added to the agent's
 * {@link com.embabel.agent.core.Blackboard Blackboard}, returning them as a {@link Stream}.
 *
 * <p>After each tick, events are drained from the blackboard via
 * {@link AgentProcessResultTranslator#collectEvents} and marked as hidden so that
 * subsequent calls do not return the same events again.
 *
 * <p>This utility is the building block for:
 * <ul>
 *   <li>Single-tick event processing in {@link AgenticCommandHandlerFunctionAdapter} and
 *       {@link AgenticEventHandlerFunctionAdapter}</li>
 *   <li>Single-tick processing in {@link AssignTaskCommandHandlerFunction} and
 *       {@link KafkaAgenticAggregateRuntime#resumeNextAgentTask resumeNextAgentTask}</li>
 * </ul>
 */
public final class AgentProcessSingleTickRunner {

    private AgentProcessSingleTickRunner() {
        // utility class
    }

    /**
     * Advances the given {@link AgentProcess} by a single tick and collects any
     * {@link DomainEvent}s that were placed on its blackboard during that tick.
     *
     * @param agentProcess         the agent process to tick; must not be {@code null}
     * @param registeredEventTypes the set of registered {@link DomainEventType}s used
     *                             to filter unknown {@link org.elasticsoftware.akces.events.ErrorEvent}s
     * @return a stream of domain events produced during the tick; may be empty
     */
    public static Stream<DomainEvent> tick(AgentProcess agentProcess,
                                           Collection<DomainEventType<?>> registeredEventTypes) {
        agentProcess.tick();
        return AgentProcessResultTranslator
                .collectEvents(agentProcess.getBlackboard(), registeredEventTypes)
                .stream();
    }
}
