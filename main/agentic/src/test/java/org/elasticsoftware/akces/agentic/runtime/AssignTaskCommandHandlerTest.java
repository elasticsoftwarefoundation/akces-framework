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
import org.elasticsoftware.akces.agentic.commands.AssignTaskCommand;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.events.DomainEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AssignTaskCommandHandler}, verifying that it creates an
 * Embabel {@link AgentProcess} and emits an {@link AgentTaskAssignedEvent} with
 * the correct process ID.
 */
@ExtendWith(MockitoExtension.class)
class AssignTaskCommandHandlerTest {

    /** Simple test state implementing AggregateState. */
    record TestState(String id) implements AggregateState {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    /** Test AgenticAggregate. */
    static class TestAggregate implements AgenticAggregate<TestState> {
        @Override
        public Class<TestState> getStateClass() {
            return TestState.class;
        }
    }

    @Mock
    private AgentPlatform agentPlatform;

    @Mock
    private AgentProcess agentProcess;

    @Mock
    private Agent agent;

    private AssignTaskCommandHandler<TestState> handler;

    @BeforeEach
    void setUp() {
        handler = new AssignTaskCommandHandler<>(new TestAggregate(), agentPlatform, "TestAggregate");
    }

    private void setUpAgentResolution() {
        when(agent.getName()).thenReturn("TestAggregateAgent");
        when(agentPlatform.agents()).thenReturn(List.of(agent));
    }

    @Test
    void applyShouldCreateAgentProcessAndEmitEvent() {
        setUpAgentResolution();
        var party = new HumanRequestingParty("user-1", "Alice", "analyst");
        var command = new AssignTaskCommand("agg-1", "Analyze data", party, Map.of("key", "value"));
        var state = new TestState("agg-1");

        when(agentPlatform.createAgentProcess(eq(agent), eq(ProcessOptions.DEFAULT), any()))
                .thenReturn(agentProcess);
        when(agentProcess.getId()).thenReturn("embabel-proc-42");

        Stream<DomainEvent> result = handler.apply(command, state);
        List<DomainEvent> events = result.toList();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(AgentTaskAssignedEvent.class);

        var event = (AgentTaskAssignedEvent) events.getFirst();
        assertThat(event.agenticAggregateId()).isEqualTo("agg-1");
        assertThat(event.agentProcessId()).isEqualTo("embabel-proc-42");
        assertThat(event.taskDescription()).isEqualTo("Analyze data");
        assertThat(event.requestingParty()).isEqualTo(party);
        assertThat(event.taskMetadata()).isEqualTo(Map.of("key", "value"));
        assertThat(event.assignedAt()).isNotNull();

        verify(agentPlatform).createAgentProcess(eq(agent), eq(ProcessOptions.DEFAULT), any());
        verify(agentProcess).getId();
    }

    @Test
    void applyShouldPropagateAgentRequestingParty() {
        setUpAgentResolution();
        var party = new AgentRequestingParty("agent-99", "Orchestrator", "supervisor");
        var command = new AssignTaskCommand("agg-1", "Process task", party, null);
        var state = new TestState("agg-1");

        when(agentPlatform.createAgentProcess(eq(agent), eq(ProcessOptions.DEFAULT), any()))
                .thenReturn(agentProcess);
        when(agentProcess.getId()).thenReturn("proc-abc");

        List<DomainEvent> events = handler.apply(command, state).toList();

        var event = (AgentTaskAssignedEvent) events.getFirst();
        assertThat(event.requestingParty()).isInstanceOf(AgentRequestingParty.class);
        assertThat(event.requestingParty().role()).isEqualTo("supervisor");
    }

    @Test
    void applyShouldHandleNullMetadata() {
        setUpAgentResolution();
        var party = new HumanRequestingParty("user-1", "Bob", "admin");
        var command = new AssignTaskCommand("agg-1", "Simple task", party, null);
        var state = new TestState("agg-1");

        when(agentPlatform.createAgentProcess(eq(agent), eq(ProcessOptions.DEFAULT), any()))
                .thenReturn(agentProcess);
        when(agentProcess.getId()).thenReturn("proc-xyz");

        List<DomainEvent> events = handler.apply(command, state).toList();

        var event = (AgentTaskAssignedEvent) events.getFirst();
        assertThat(event.taskMetadata()).isNull();
    }

    @Test
    void isCreateShouldReturnFalse() {
        assertThat(handler.isCreate()).isFalse();
    }

    @Test
    void getCommandTypeShouldReturnAssignTaskType() {
        assertThat(handler.getCommandType().typeName()).isEqualTo("AssignTask");
        assertThat(handler.getCommandType().version()).isEqualTo(1);
    }

    @Test
    void getProducedDomainEventTypesShouldContainAgentTaskAssigned() {
        assertThat(handler.getProducedDomainEventTypes()).hasSize(1);
        assertThat(handler.getProducedDomainEventTypes().getFirst().typeName())
                .isEqualTo("AgentTaskAssigned");
    }

    @Test
    void getErrorEventTypesShouldBeEmpty() {
        assertThat(handler.getErrorEventTypes()).isEmpty();
    }

    @Test
    void constructorShouldRejectNullAggregate() {
        assertThatThrownBy(() -> new AssignTaskCommandHandler<>(null, agentPlatform, "TestAggregate"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("aggregate");
    }

    @Test
    void constructorShouldRejectNullAgentPlatform() {
        assertThatThrownBy(() -> new AssignTaskCommandHandler<>(new TestAggregate(), null, "TestAggregate"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("agentPlatform");
    }

    @Test
    void constructorShouldRejectNullAggregateName() {
        assertThatThrownBy(() -> new AssignTaskCommandHandler<>(new TestAggregate(), agentPlatform, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("aggregateName");
    }

    @Test
    void applyShouldThrowWhenNoAgentFound() {
        // Agent platform has no matching agents
        when(agentPlatform.agents()).thenReturn(List.of());
        var noAgentHandler = new AssignTaskCommandHandler<>(new TestAggregate(), agentPlatform, "Unknown");
        var party = new HumanRequestingParty("user-1", "Alice", "analyst");
        var command = new AssignTaskCommand("agg-1", "task", party, null);
        var state = new TestState("agg-1");

        assertThatThrownBy(() -> noAgentHandler.apply(command, state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Agent found");
    }
}
