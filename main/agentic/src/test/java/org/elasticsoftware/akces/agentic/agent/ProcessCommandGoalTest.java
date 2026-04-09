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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProcessCommandGoal}.
 */
class ProcessCommandGoalTest {

    private final ProcessCommandGoal config = new ProcessCommandGoal();
    private final Goal goal = config.processCommandGoal();

    @Test
    void goalNameIsProcessCommand() {
        assertThat(goal.getName()).isEqualTo("ProcessCommand");
    }

    @Test
    void goalDescriptionIsSet() {
        assertThat(goal.getDescription()).isEqualTo(
                "Process an incoming command through AI-assisted reasoning and produce domain events");
    }

    @Test
    void goalHasIsCommandProcessingPrecondition() {
        assertThat(goal.getPre()).contains("isCommandProcessing");
    }

    @Test
    void goalPreconditionsMapContainsIsCommandProcessing() {
        assertThat(goal.getPreconditions()).containsKey("isCommandProcessing");
    }

    @Test
    void goalNameConstantMatchesBean() {
        assertThat(ProcessCommandGoal.GOAL_NAME).isEqualTo("ProcessCommand");
        assertThat(goal.getName()).isEqualTo(ProcessCommandGoal.GOAL_NAME);
    }

    @Test
    void goalDescriptionConstantMatchesBean() {
        assertThat(ProcessCommandGoal.GOAL_DESCRIPTION).isEqualTo(goal.getDescription());
    }
}
