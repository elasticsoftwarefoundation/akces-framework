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

package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.annotations.AgenticAggregateInfo;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link AgenticAggregateInfo} annotation by verifying its effects on a test
 * fixture class that uses it. Per framework testing guidelines, annotations are not tested
 * directly — instead, their effect is verified through the components that use them.
 */
class AgenticAggregateInfoTest {

    /** Simple test state used as the stateClass for the test fixture. */
    record TestState(String id) implements AggregateState {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    /** Test aggregate annotated with default settings. */
    @AgenticAggregateInfo(value = "TestAgentic", stateClass = TestState.class)
    static class DefaultAgenticAggregate implements AgenticAggregate<TestState> {
        @Override
        public Class<TestState> getStateClass() {
            return TestState.class;
        }
    }

    /** Test aggregate annotated with a custom maxMemories value. */
    @AgenticAggregateInfo(value = "CustomAgentic", stateClass = TestState.class, maxMemories = 50)
    static class CustomMaxMemoriesAggregate implements AgenticAggregate<TestState> {
        @Override
        public Class<TestState> getStateClass() {
            return TestState.class;
        }
    }

    @Test
    void defaultMaxMemoriesShouldBe100() {
        AgenticAggregateInfo info = DefaultAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.maxMemories()).isEqualTo(100);
    }

    @Test
    void customMaxMemoriesShouldBeHonored() {
        AgenticAggregateInfo info = CustomMaxMemoriesAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.maxMemories()).isEqualTo(50);
    }

    @Test
    void valueShouldReturnAggregateName() {
        AgenticAggregateInfo info = DefaultAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.value()).isEqualTo("TestAgentic");
    }

    @Test
    void stateClassShouldReturnConfiguredClass() {
        AgenticAggregateInfo info = DefaultAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.stateClass()).isEqualTo(TestState.class);
    }

    @Test
    void descriptionShouldDefaultToEmpty() {
        AgenticAggregateInfo info = DefaultAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.description()).isEmpty();
    }

    @Test
    void componentMetaAnnotationShouldBePresent() {
        assertThat(AgenticAggregateInfo.class.isAnnotationPresent(Component.class)).isTrue();
    }

    @Test
    void shouldNotHaveGenerateGDPRKeyOnCreateAttribute() {
        Method[] methods = AgenticAggregateInfo.class.getDeclaredMethods();
        boolean hasGDPRKeyAttribute = Arrays.stream(methods)
                .anyMatch(m -> m.getName().equals("generateGDPRKeyOnCreate"));
        assertThat(hasGDPRKeyAttribute)
                .as("AgenticAggregateInfo should not have a generateGDPRKeyOnCreate attribute")
                .isFalse();
    }

    @Test
    void requiredAttributesShouldBePresent() {
        Method[] methods = AgenticAggregateInfo.class.getDeclaredMethods();
        boolean hasValue = Arrays.stream(methods).anyMatch(m -> m.getName().equals("value"));
        boolean hasStateClass = Arrays.stream(methods).anyMatch(m -> m.getName().equals("stateClass"));
        assertThat(hasValue).as("value() attribute must be present").isTrue();
        assertThat(hasStateClass).as("stateClass() attribute must be present").isTrue();
    }
}
