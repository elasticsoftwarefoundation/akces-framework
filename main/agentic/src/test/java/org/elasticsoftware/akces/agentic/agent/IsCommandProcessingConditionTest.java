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
import com.embabel.agent.test.unit.FakeOperationContext;
import com.embabel.plan.common.condition.ConditionDetermination;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IsCommandProcessingCondition}.
 */
class IsCommandProcessingConditionTest {

    private final IsCommandProcessingCondition config = new IsCommandProcessingCondition();
    private final Condition condition = config.isCommandProcessingCondition();

    @Test
    void conditionNameIsCorrect() {
        assertThat(condition.getName()).isEqualTo("isCommandProcessing");
    }

    @Test
    void evaluatesTrueWhenCommandProcessingBindingIsTrue() {
        FakeOperationContext context = FakeOperationContext.Companion.create();
        context.bind("isCommandProcessing", true);

        ConditionDetermination result = condition.evaluate(context);

        assertThat(result).isEqualTo(ConditionDetermination.TRUE);
    }

    @Test
    void evaluatesFalseWhenCommandProcessingBindingIsFalse() {
        FakeOperationContext context = FakeOperationContext.Companion.create();
        context.bind("isCommandProcessing", false);

        ConditionDetermination result = condition.evaluate(context);

        assertThat(result).isEqualTo(ConditionDetermination.FALSE);
    }

    @Test
    void evaluatesFalseWhenBindingIsAbsent() {
        FakeOperationContext context = FakeOperationContext.Companion.create();

        ConditionDetermination result = condition.evaluate(context);

        assertThat(result).isEqualTo(ConditionDetermination.FALSE);
    }

    @Test
    void evaluatesFalseWhenBindingIsNonBoolean() {
        FakeOperationContext context = FakeOperationContext.Companion.create();
        context.bind("isCommandProcessing", "true");

        ConditionDetermination result = condition.evaluate(context);

        assertThat(result).isEqualTo(ConditionDetermination.FALSE);
    }

    @Test
    void conditionNameConstantMatchesBean() {
        assertThat(IsCommandProcessingCondition.CONDITION_NAME).isEqualTo("isCommandProcessing");
        assertThat(condition.getName()).isEqualTo(IsCommandProcessingCondition.CONDITION_NAME);
    }
}
