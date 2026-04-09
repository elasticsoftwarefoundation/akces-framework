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

package org.elasticsoftware.akces.agentic.agent;

import com.embabel.agent.core.Goal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defines the {@code ReactToExternalEvent} goal for agentic aggregates.
 *
 * <p>This is the top-level goal for handling external domain events in an agentic aggregate.
 * It replaces the deterministic {@code @EventHandler} flow with an AI-assisted reasoning
 * loop for events declared in {@code @AgenticAggregateInfo.agentHandledEvents()}.
 *
 * <p><strong>Precondition:</strong> {@code isExternalEvent} — the goal is only applicable
 * when the agent was triggered by an external domain event (as opposed to an incoming
 * command).
 *
 * <p><strong>Expected plan</strong> (determined by the GOAP planner based on available
 * actions and conditions):
 * <ol>
 *   <li>{@code RecallMemoriesAction} → load relevant prior knowledge</li>
 *   <li>{@code AnalyzeAggregateStateAction} → understand current state</li>
 *   <li>(LLM reasoning) → determine appropriate response and produce domain events
 *       on the blackboard (including error events from {@code agentProducedErrors})</li>
 *   <li>{@code SendCommandAction} → send commands to other aggregates if needed</li>
 *   <li>{@code LearnFromProcessGoal} (sub-goal) → learn from experience</li>
 * </ol>
 *
 * <p>Domain events produced during the reasoning step are placed directly on the blackboard.
 * The {@link org.elasticsoftware.akces.agentic.runtime.AgentProcessResultTranslator}
 * collects all {@code DomainEvent} objects from the blackboard after each {@code tick()} —
 * no explicit "emit" action is needed.
 *
 * @see IsExternalEventCondition
 * @see AnalyzeAggregateStateAction
 * @see SendCommandAction
 * @see ProcessCommandGoal
 */
@Configuration
public class ReactToExternalEventGoal {

    /**
     * The name of this goal as used by the GOAP planner.
     */
    public static final String GOAL_NAME = "ReactToExternalEvent";

    /**
     * A human-readable description of what this goal achieves.
     */
    public static final String GOAL_DESCRIPTION =
            "React to an external domain event through AI-assisted reasoning";

    /**
     * Creates the {@code ReactToExternalEvent} goal bean.
     *
     * <p>The goal is created with the {@code isExternalEvent} precondition, ensuring
     * that the GOAP planner only selects this goal when the agent was triggered by an
     * external domain event. The planner determines the optimal sequence of actions to
     * achieve this goal based on the available actions and the current world state.
     *
     * @return a {@link Goal} representing the external event processing objective
     */
    @Bean
    public Goal reactToExternalEventGoal() {
        return Goal.Companion.createInstance(
                GOAL_DESCRIPTION,
                Void.class,
                GOAL_NAME
        ).withPreconditions(IsExternalEventCondition.CONDITION_NAME);
    }
}
