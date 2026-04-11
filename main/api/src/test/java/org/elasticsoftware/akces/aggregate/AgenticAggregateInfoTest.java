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

import org.elasticsoftware.akces.annotations.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
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

    /** Stub create event used to satisfy the {@link AgenticAggregate#getCreateDomainEvent()} contract. */
    record StubCreateEvent(String aggregateId) implements DomainEvent {
        @Override
        public String getAggregateId() {
            return aggregateId;
        }
    }

    /** Test aggregate annotated with default settings. */
    @AgenticAggregateInfo(value = "TestAgentic", stateClass = TestState.class)
    static class DefaultAgenticAggregate implements AgenticAggregate<TestState> {
        @Override
        public Class<TestState> getStateClass() {
            return TestState.class;
        }

        @Override
        public DomainEvent getCreateDomainEvent() {
            return new StubCreateEvent(getName());
        }
    }

    /** Test aggregate annotated with custom maxTotalMemories and maxMemoriesAdded values. */
    @AgenticAggregateInfo(value = "CustomAgentic", stateClass = TestState.class, maxTotalMemories = 50, maxMemoriesAdded = 5)
    static class CustomMaxMemoriesAggregate implements AgenticAggregate<TestState> {
        @Override
        public Class<TestState> getStateClass() {
            return TestState.class;
        }

        @Override
        public DomainEvent getCreateDomainEvent() {
            return new StubCreateEvent(getName());
        }
    }

    @Test
    void defaultMaxMemoriesShouldBe50() {
        AgenticAggregateInfo info = DefaultAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.maxTotalMemories()).isEqualTo(50);
    }

    @Test
    void defaultMaxMemoriesAddedShouldBe5() {
        AgenticAggregateInfo info = DefaultAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.maxMemoriesAdded()).isEqualTo(5);
    }

    @Test
    void customMaxMemoriesShouldBeHonored() {
        AgenticAggregateInfo info = CustomMaxMemoriesAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.maxTotalMemories()).isEqualTo(50);
    }

    @Test
    void customMaxMemoriesAddedShouldBeHonored() {
        AgenticAggregateInfo info = CustomMaxMemoriesAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.maxMemoriesAdded()).isEqualTo(5);
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

    // -------------------------------------------------------------------------
    // Test fixtures for new agentic annotation properties
    // -------------------------------------------------------------------------

    /** Stub command for agent-handled command tests. */
    @CommandInfo(type = "TestAgentCommand", version = 1)
    record TestAgentCommand(@AggregateIdentifier String id) implements Command {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    /** Stub external event for agent-handled event tests. */
    @DomainEventInfo(type = "TestExternalEvent", version = 1)
    record TestExternalEvent(@AggregateIdentifier String id) implements DomainEvent {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    /** Stub error event for agent-produced error tests. */
    @DomainEventInfo(type = "TestAgentError", version = 1)
    record TestAgentError(@AggregateIdentifier String id) implements ErrorEvent {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    /** Aggregate with all three new annotation properties populated. */
    @AgenticAggregateInfo(
            value = "FullAgentic",
            stateClass = TestState.class,
            agentHandledCommands = {TestAgentCommand.class},
            agentHandledEvents = {TestExternalEvent.class},
            agentProducedErrors = {TestAgentError.class}
    )
    static class FullAgenticAggregate implements AgenticAggregate<TestState> {
        @Override
        public Class<TestState> getStateClass() {
            return TestState.class;
        }

        @Override
        public DomainEvent getCreateDomainEvent() {
            return new StubCreateEvent(getName());
        }
    }

    // -------------------------------------------------------------------------
    // Tests for agentHandledCommands
    // -------------------------------------------------------------------------

    @Test
    void agentHandledCommandsShouldDefaultToEmpty() {
        AgenticAggregateInfo info = DefaultAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.agentHandledCommands()).isEmpty();
    }

    @Test
    void agentHandledCommandsShouldReturnConfiguredClasses() {
        AgenticAggregateInfo info = FullAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.agentHandledCommands()).containsExactly(TestAgentCommand.class);
    }

    @Test
    void agentHandledCommandsShouldOnlyAcceptCommandImplementations() {
        // Verify that the declared Command class actually implements Command
        AgenticAggregateInfo info = FullAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        for (Class<?> commandClass : info.agentHandledCommands()) {
            assertThat(Command.class.isAssignableFrom(commandClass))
                    .as("Agent-handled command %s must implement Command", commandClass.getName())
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Tests for agentHandledEvents
    // -------------------------------------------------------------------------

    @Test
    void agentHandledEventsShouldDefaultToEmpty() {
        AgenticAggregateInfo info = DefaultAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.agentHandledEvents()).isEmpty();
    }

    @Test
    void agentHandledEventsShouldReturnConfiguredClasses() {
        AgenticAggregateInfo info = FullAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.agentHandledEvents()).containsExactly(TestExternalEvent.class);
    }

    @Test
    void agentHandledEventsShouldOnlyAcceptDomainEventImplementations() {
        AgenticAggregateInfo info = FullAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        for (Class<?> eventClass : info.agentHandledEvents()) {
            assertThat(DomainEvent.class.isAssignableFrom(eventClass))
                    .as("Agent-handled event %s must implement DomainEvent", eventClass.getName())
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Tests for agentProducedErrors
    // -------------------------------------------------------------------------

    @Test
    void agentProducedErrorsShouldDefaultToEmpty() {
        AgenticAggregateInfo info = DefaultAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.agentProducedErrors()).isEmpty();
    }

    @Test
    void agentProducedErrorsShouldReturnConfiguredClasses() {
        AgenticAggregateInfo info = FullAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        assertThat(info.agentProducedErrors()).containsExactly(TestAgentError.class);
    }

    @Test
    void agentProducedErrorsShouldOnlyAcceptErrorEventImplementations() {
        AgenticAggregateInfo info = FullAgenticAggregate.class.getAnnotation(AgenticAggregateInfo.class);
        assertThat(info).isNotNull();
        for (Class<?> errorClass : info.agentProducedErrors()) {
            assertThat(ErrorEvent.class.isAssignableFrom(errorClass))
                    .as("Agent-produced error %s must implement ErrorEvent", errorClass.getName())
                    .isTrue();
        }
    }
}
