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
 * Unit tests for {@link ReactToExternalEventGoal}.
 */
class ReactToExternalEventGoalTest {

    private final ReactToExternalEventGoal config = new ReactToExternalEventGoal();
    private final Goal goal = config.reactToExternalEventGoal();

    @Test
    void goalNameIsReactToExternalEvent() {
        assertThat(goal.getName()).isEqualTo("ReactToExternalEvent");
    }

    @Test
    void goalDescriptionIsSet() {
        assertThat(goal.getDescription()).isEqualTo(
                "React to an external domain event through AI-assisted reasoning");
    }

    @Test
    void goalHasIsExternalEventPrecondition() {
        assertThat(goal.getPre()).contains("isExternalEvent");
    }

    @Test
    void goalPreconditionsMapContainsIsExternalEvent() {
        assertThat(goal.getPreconditions()).containsKey("isExternalEvent");
    }

    @Test
    void goalNameConstantMatchesBean() {
        assertThat(ReactToExternalEventGoal.GOAL_NAME).isEqualTo("ReactToExternalEvent");
        assertThat(goal.getName()).isEqualTo(ReactToExternalEventGoal.GOAL_NAME);
    }

    @Test
    void goalDescriptionConstantMatchesBean() {
        assertThat(ReactToExternalEventGoal.GOAL_DESCRIPTION).isEqualTo(goal.getDescription());
    }
}
