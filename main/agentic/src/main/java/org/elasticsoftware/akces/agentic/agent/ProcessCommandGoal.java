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
 * Defines the {@code ProcessCommand} goal for agentic aggregates.
 *
 * <p>This is the top-level goal for command processing in an agentic aggregate. It replaces
 * the deterministic {@code @CommandHandler} flow with an AI-assisted reasoning loop for
 * commands declared in {@code @AgenticAggregateInfo.agentHandledCommands()}.
 *
 * <p><strong>Precondition:</strong> {@code isCommandProcessing} — the goal is only
 * applicable when the agent was triggered by an incoming command (as opposed to an
 * external domain event).
 *
 * <p><strong>Expected plan</strong> (determined by the GOAP planner based on available
 * actions and conditions):
 * <ol>
 *   <li>{@code RecallMemoriesAction} → load relevant prior knowledge</li>
 *   <li>{@code AnalyzeAggregateStateAction} → understand current state</li>
 *   <li>(LLM reasoning with MCP tools) → determine actions and produce domain events
 *       on the blackboard (including error events from {@code agentProducedErrors})</li>
 *   <li>{@code LearnFromProcessGoal} (sub-goal) → learn from experience</li>
 * </ol>
 *
 * <p>Domain events produced during the reasoning step are placed directly on the blackboard.
 * The {@link org.elasticsoftware.akces.agentic.runtime.AgentProcessResultTranslator}
 * collects all {@code DomainEvent} objects from the blackboard after each {@code tick()} —
 * no explicit "emit" action is needed.
 *
 * @see IsCommandProcessingCondition
 * @see AnalyzeAggregateStateAction
 * @see ReactToExternalEventGoal
 */
@Configuration
public class ProcessCommandGoal {

    /**
     * The name of this goal as used by the GOAP planner.
     */
    public static final String GOAL_NAME = "ProcessCommand";

    /**
     * A human-readable description of what this goal achieves.
     */
    public static final String GOAL_DESCRIPTION =
            "Process an incoming command through AI-assisted reasoning and produce domain events";

    /**
     * Creates the {@code ProcessCommand} goal bean.
     *
     * <p>The goal is created with the {@code isCommandProcessing} precondition, ensuring
     * that the GOAP planner only selects this goal when the agent was triggered by a
     * command. The planner determines the optimal sequence of actions to achieve this
     * goal based on the available actions and the current world state.
     *
     * @return a {@link Goal} representing the command processing objective
     */
    @Bean
    public Goal processCommandGoal() {
        return Goal.Companion.createInstance(
                GOAL_DESCRIPTION,
                Void.class,
                GOAL_NAME
        ).withPreconditions(IsCommandProcessingCondition.CONDITION_NAME);
    }
}
