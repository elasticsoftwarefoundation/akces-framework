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

import com.embabel.agent.core.Condition;
import com.embabel.agent.core.ComputedBooleanCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embabel {@link Condition} that evaluates to {@code true} when the current agent context
 * contains a command to process, as opposed to an external domain event.
 *
 * <p>This condition is used as a precondition for goals that handle command processing
 * (e.g., {@link ProcessCommandGoal}). It enables the GOAP planner to select different
 * goal plans depending on whether the agent was triggered by a command or an external event.
 *
 * <p>The condition reads the {@code "isCommandProcessing"} binding from the agent's
 * blackboard, which is set to {@code true} by the
 * {@link org.elasticsoftware.akces.agentic.runtime.AgenticCommandHandlerFunctionAdapter}
 * and to {@code false} by the
 * {@link org.elasticsoftware.akces.agentic.runtime.AgenticEventHandlerFunctionAdapter}.
 *
 * @see IsExternalEventCondition
 * @see ProcessCommandGoal
 */
@Configuration
public class IsCommandProcessingCondition {

    /**
     * The name of this condition as referenced by goal preconditions and action
     * pre/postconditions in the GOAP planning system.
     */
    public static final String CONDITION_NAME = "isCommandProcessing";

    /**
     * Creates the {@code isCommandProcessing} condition bean.
     *
     * <p>The condition reads the {@code "isCommandProcessing"} binding from the blackboard
     * and returns {@code true} if it is a {@link Boolean#TRUE} value. If the binding is
     * absent or not a {@code Boolean}, the condition evaluates to {@code false}.
     *
     * @return a {@link Condition} that evaluates to {@code true} during command processing
     */
    @Bean
    public Condition isCommandProcessingCondition() {
        return new ComputedBooleanCondition(CONDITION_NAME, 0.0, (context, condition) -> {
            Object value = context.get(CONDITION_NAME);
            return value instanceof Boolean b && b;
        });
    }
}
