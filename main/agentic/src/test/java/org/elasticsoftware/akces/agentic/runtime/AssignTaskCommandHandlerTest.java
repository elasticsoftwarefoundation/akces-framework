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
import org.elasticsoftware.akces.agentic.embabel.DefaultAgent;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
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
 * Unit tests for {@link AssignTaskCommandHandlerFunction},
 * verifying that it creates an Embabel {@link AgentProcess} and emits an
 * {@link AgentTaskAssignedEvent} with the correct process ID.
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

    @Mock
    private AgentPlatform agentPlatform;

    @Mock
    private AgentProcess agentProcess;

    @Mock
    private Agent agent;

    @Mock
    private AgenticAggregate<?> aggregate;

    private CommandHandlerFunction<AggregateState, Command, DomainEvent> handler;

    @BeforeEach
    void setUp() {
        handler = new AssignTaskCommandHandlerFunction(
                aggregate,
                "TestAggregate",
                agentPlatform,
                List.of(),
                List.of(),
                Collections::emptyList);
    }

    private void setUpAgentResolution() {
        when(agent.getName()).thenReturn("TestAggregateAgent");
        when(agentPlatform.agents()).thenReturn(List.of(agent));
    }

    @Test
    void applyShouldCreateAgentProcessAndEmitEvent() {
        setUpAgentResolution();
        var party = new HumanRequestingParty("user-1", "analyst");
        var command = new AssignTaskCommand("agg-1", "Analyze data", party, Map.of("key", "value"));
        var state = new TestState("agg-1");

        when(agentPlatform.createAgentProcess(eq(agent), any(ProcessOptions.class), any()))
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

        verify(agentPlatform).createAgentProcess(eq(agent), any(ProcessOptions.class), any());
        verify(agentProcess).getId();
    }

    @Test
    void applyShouldPropagateAgentRequestingParty() {
        setUpAgentResolution();
        var party = new AgentRequestingParty("agent-99", "Orchestrator", "supervisor");
        var command = new AssignTaskCommand("agg-1", "Process task", party, null);
        var state = new TestState("agg-1");

        when(agentPlatform.createAgentProcess(eq(agent), any(ProcessOptions.class), any()))
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
        var party = new HumanRequestingParty("user-1", "admin");
        var command = new AssignTaskCommand("agg-1", "Simple task", party, null);
        var state = new TestState("agg-1");

        when(agentPlatform.createAgentProcess(eq(agent), any(ProcessOptions.class), any()))
                .thenReturn(agentProcess);
        when(agentProcess.getId()).thenReturn("proc-xyz");

        List<DomainEvent> events = handler.apply(command, state).toList();

        var event = (AgentTaskAssignedEvent) events.getFirst();
        assertThat(event.taskMetadata()).isNull();
    }

    @Test
    void applyShouldThrowWhenNoAgentFound() {
        when(agentPlatform.agents()).thenReturn(List.of());
        var party = new HumanRequestingParty("user-1", "analyst");
        var command = new AssignTaskCommand("agg-1", "task", party, null);
        var state = new TestState("agg-1");

        assertThatThrownBy(() -> handler.apply(command, state).toList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DefaultAgent.AGENT_NAME);
    }

    @Test
    void applyShouldFallBackToDefaultAgent() {
        Agent defaultAgent = mock(Agent.class);
        when(defaultAgent.getName()).thenReturn(DefaultAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(defaultAgent));
        when(agentPlatform.createAgentProcess(eq(defaultAgent), any(ProcessOptions.class), any()))
                .thenReturn(agentProcess);
        when(agentProcess.getId()).thenReturn("proc-default");

        var party = new HumanRequestingParty("user-1", "analyst");
        var command = new AssignTaskCommand("agg-1", "task", party, null);
        var state = new TestState("agg-1");

        List<DomainEvent> events = handler.apply(command, state).toList();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(AgentTaskAssignedEvent.class);
        verify(agentPlatform).createAgentProcess(eq(defaultAgent), any(ProcessOptions.class), any());
    }

    @Test
    void applyShouldThrowForNonAssignTaskCommand() {
        Command unknownCommand = new Command() {
            @Override
            public String getAggregateId() {
                return "agg-1";
            }
        };
        var state = new TestState("agg-1");

        assertThatThrownBy(() -> handler.apply(unknownCommand, state).toList())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AssignTaskCommandHandlerFunction only handles AssignTaskCommand");
    }
}
