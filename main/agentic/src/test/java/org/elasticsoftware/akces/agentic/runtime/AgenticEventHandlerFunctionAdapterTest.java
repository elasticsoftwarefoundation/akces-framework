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
import org.elasticsoftware.akces.agentic.embabel.DefaultAgent;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AgenticEventHandlerFunctionAdapter}, verifying the runtime
 * agent resolution by aggregate name and the default agent fallback when no matching
 * agent is found.
 */
@ExtendWith(MockitoExtension.class)
class AgenticEventHandlerFunctionAdapterTest {

    @DomainEventInfo(type = "ExternalEvent", version = 1)
    record TestExternalEvent(String id) implements DomainEvent {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    record TestState(String id) implements AggregateState {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    @Mock
    private AgenticAggregate<TestState> aggregate;

    @Mock
    private AgentPlatform agentPlatform;

    @Mock
    private AgentProcess agentProcess;

    private final DomainEventType<TestExternalEvent> eventType =
            new DomainEventType<>("ExternalEvent", 1, TestExternalEvent.class, false, true, false, false);

    @BeforeEach
    void setUp() {
        // No blackboard setup needed — adapters no longer tick the process.
    }

    // -------------------------------------------------------------------------
    // Agent resolution by exact name
    // -------------------------------------------------------------------------

    @Test
    void shouldResolveAgentByExactNameMatch() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("TestAggregate");
        when(agentPlatform.agents()).thenReturn(List.of(agent));
        when(agentPlatform.createAgentProcess(eq(agent), eq(ProcessOptions.DEFAULT), any(Map.class)))
                .thenReturn(agentProcess);

        var adapter = new AgenticEventHandlerFunctionAdapter<>(
                aggregate, "TestAggregate", eventType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        Stream<DomainEvent> result = adapter.apply(
                new TestExternalEvent("agg-1"), new TestState("agg-1"));

        assertThat(result).isEmpty();
        verify(agentPlatform).createAgentProcess(eq(agent), eq(ProcessOptions.DEFAULT), any(Map.class));
    }

    // -------------------------------------------------------------------------
    // Agent resolution by suffix name
    // -------------------------------------------------------------------------

    @Test
    void shouldResolveAgentBySuffixNameMatch() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("TestAggregateAgent");
        when(agentPlatform.agents()).thenReturn(List.of(agent));
        when(agentPlatform.createAgentProcess(eq(agent), eq(ProcessOptions.DEFAULT), any(Map.class)))
                .thenReturn(agentProcess);

        var adapter = new AgenticEventHandlerFunctionAdapter<>(
                aggregate, "TestAggregate", eventType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        Stream<DomainEvent> result = adapter.apply(
                new TestExternalEvent("agg-1"), new TestState("agg-1"));

        assertThat(result).isEmpty();
        verify(agentPlatform).createAgentProcess(eq(agent), eq(ProcessOptions.DEFAULT), any(Map.class));
    }

    // -------------------------------------------------------------------------
    // Default agent fallback
    // -------------------------------------------------------------------------

    @Test
    void shouldUseDefaultAgentWhenNoNameMatch() {
        Agent defaultAgent = mock(Agent.class);
        when(defaultAgent.getName()).thenReturn(DefaultAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(defaultAgent));
        when(agentPlatform.createAgentProcess(eq(defaultAgent), eq(ProcessOptions.DEFAULT), any(Map.class)))
                .thenReturn(agentProcess);

        var adapter = new AgenticEventHandlerFunctionAdapter<>(
                aggregate, "UnknownAggregate", eventType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        Stream<DomainEvent> result = adapter.apply(
                new TestExternalEvent("agg-1"), new TestState("agg-1"));

        assertThat(result).isEmpty();
        verify(agentPlatform).createAgentProcess(eq(defaultAgent), eq(ProcessOptions.DEFAULT), any(Map.class));
    }

    @Test
    void shouldThrowWhenNoDefaultAgentDeployed() {
        Agent otherAgent = mock(Agent.class);
        when(otherAgent.getName()).thenReturn("SomeOtherAgent");
        when(agentPlatform.agents()).thenReturn(List.of(otherAgent));

        var adapter = new AgenticEventHandlerFunctionAdapter<>(
                aggregate, "UnknownAggregate", eventType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        assertThatThrownBy(() -> adapter.apply(
                new TestExternalEvent("agg-1"), new TestState("agg-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No DefaultAgent")
                .hasMessageContaining("UnknownAggregate");
    }

    @Test
    void shouldThrowWhenNoAgentsDeployed() {
        when(agentPlatform.agents()).thenReturn(List.of());

        var adapter = new AgenticEventHandlerFunctionAdapter<>(
                aggregate, "UnknownAggregate", eventType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        assertThatThrownBy(() -> adapter.apply(
                new TestExternalEvent("agg-1"), new TestState("agg-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No DefaultAgent")
                .hasMessageContaining("UnknownAggregate");
    }

    // -------------------------------------------------------------------------
    // Basic adapter properties
    // -------------------------------------------------------------------------

    @Test
    void isCreateShouldAlwaysReturnFalse() {
        var adapter = new AgenticEventHandlerFunctionAdapter<>(
                aggregate, "Test", eventType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        assertThat(adapter.isCreate()).isFalse();
    }

    @Test
    void getEventTypeShouldReturnConfiguredType() {
        var adapter = new AgenticEventHandlerFunctionAdapter<>(
                aggregate, "Test", eventType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        assertThat(adapter.getEventType()).isSameAs(eventType);
    }

    @Test
    void getAggregateShouldReturnConfiguredAggregate() {
        var adapter = new AgenticEventHandlerFunctionAdapter<>(
                aggregate, "Test", eventType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        assertThat(adapter.getAggregate()).isSameAs(aggregate);
    }
}
