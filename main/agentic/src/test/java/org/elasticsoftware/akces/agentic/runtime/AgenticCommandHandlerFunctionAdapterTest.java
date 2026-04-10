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

import com.embabel.agent.api.common.PlatformServices;
import com.embabel.agent.api.common.autonomy.AgentProcessExecution;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.api.common.autonomy.GoalChoiceApprover;
import com.embabel.agent.api.common.autonomy.ProcessExecutionException;
import com.embabel.agent.api.common.autonomy.ProcessExecutionFailedException;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.ProcessOptions;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
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
 * Unit tests for {@link AgenticCommandHandlerFunctionAdapter}, verifying the runtime
 * agent resolution by aggregate name and the {@link Autonomy} fallback when no matching
 * agent is found.
 */
@ExtendWith(MockitoExtension.class)
class AgenticCommandHandlerFunctionAdapterTest {

    @CommandInfo(type = "TestCommand", version = 1)
    record TestCommand(String id) implements Command {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    @DomainEventInfo(type = "TestEvent", version = 1)
    record TestEvent(String id) implements DomainEvent {
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

    @Mock
    private Blackboard blackboard;

    @Mock
    private PlatformServices platformServices;

    @Mock
    private Autonomy autonomy;

    private final CommandType<TestCommand> commandType =
            new CommandType<>("TestCommand", 1, TestCommand.class, false, false, false);

    @BeforeEach
    void setUp() {
        lenient().when(agentProcess.getBlackboard()).thenReturn(blackboard);
        lenient().when(blackboard.get(any(String.class))).thenReturn(null);
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
        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(null);

        var adapter = new AgenticCommandHandlerFunctionAdapter<>(
                aggregate, "TestAggregate", commandType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        Stream<DomainEvent> result = adapter.apply(new TestCommand("agg-1"), new TestState("agg-1"));

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
        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(null);

        var adapter = new AgenticCommandHandlerFunctionAdapter<>(
                aggregate, "TestAggregate", commandType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        Stream<DomainEvent> result = adapter.apply(new TestCommand("agg-1"), new TestState("agg-1"));

        assertThat(result).isEmpty();
        verify(agentPlatform).createAgentProcess(eq(agent), eq(ProcessOptions.DEFAULT), any(Map.class));
    }

    // -------------------------------------------------------------------------
    // Autonomy fallback
    // -------------------------------------------------------------------------

    @Test
    void shouldFallBackToAutonomyWhenNoAgentFound() throws ProcessExecutionException {
        when(agentPlatform.agents()).thenReturn(List.of());
        when(agentPlatform.getPlatformServices()).thenReturn(platformServices);
        when(platformServices.autonomy()).thenReturn(autonomy);

        AgentProcessExecution execution = mock(AgentProcessExecution.class);
        when(execution.getAgentProcess()).thenReturn(agentProcess);
        when(autonomy.chooseAndAccomplishGoal(
                any(GoalChoiceApprover.class), eq(agentPlatform), any(Map.class)))
                .thenReturn(execution);
        when(agentProcess.getStatus()).thenReturn(null);

        var adapter = new AgenticCommandHandlerFunctionAdapter<>(
                aggregate, "UnknownAggregate", commandType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        Stream<DomainEvent> result = adapter.apply(new TestCommand("agg-1"), new TestState("agg-1"));

        assertThat(result).isEmpty();
        verify(autonomy).chooseAndAccomplishGoal(
                any(GoalChoiceApprover.class), eq(agentPlatform), any(Map.class));
        verify(agentPlatform, never()).createAgentProcess(any(), any(), any(Map.class));
    }

    @Test
    void shouldWrapAutonomyExceptionInIllegalStateException() throws ProcessExecutionException {
        when(agentPlatform.agents()).thenReturn(List.of());
        when(agentPlatform.getPlatformServices()).thenReturn(platformServices);
        when(platformServices.autonomy()).thenReturn(autonomy);

        AgentProcess failedProcess = mock(AgentProcess.class);
        doThrow(new ProcessExecutionFailedException(failedProcess, "Autonomy failed"))
                .when(autonomy).chooseAndAccomplishGoal(
                        any(GoalChoiceApprover.class), eq(agentPlatform), any(Map.class));

        var adapter = new AgenticCommandHandlerFunctionAdapter<>(
                aggregate, "UnknownAggregate", commandType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        assertThatThrownBy(() -> adapter.apply(new TestCommand("agg-1"), new TestState("agg-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Autonomy fallback failed")
                .hasMessageContaining("TestCommand")
                .hasMessageContaining("UnknownAggregate");
    }

    // -------------------------------------------------------------------------
    // Exact match takes precedence over suffix match
    // -------------------------------------------------------------------------

    @Test
    void shouldPreferExactMatchOverSuffixMatch() {
        Agent exactAgent = mock(Agent.class);
        when(exactAgent.getName()).thenReturn("MyAggregate");

        Agent suffixAgent = mock(Agent.class);
        lenient().when(suffixAgent.getName()).thenReturn("MyAggregateAgent");

        when(agentPlatform.agents()).thenReturn(List.of(suffixAgent, exactAgent));
        when(agentPlatform.createAgentProcess(eq(exactAgent), eq(ProcessOptions.DEFAULT), any(Map.class)))
                .thenReturn(agentProcess);
        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(null);

        var adapter = new AgenticCommandHandlerFunctionAdapter<>(
                aggregate, "MyAggregate", commandType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        adapter.apply(new TestCommand("agg-1"), new TestState("agg-1"));

        verify(agentPlatform).createAgentProcess(eq(exactAgent), eq(ProcessOptions.DEFAULT), any(Map.class));
    }

    // -------------------------------------------------------------------------
    // resolveAgentByName static helper
    // -------------------------------------------------------------------------

    @Test
    void resolveAgentByNameShouldReturnEmptyWhenNoAgentsDeployed() {
        when(agentPlatform.agents()).thenReturn(List.of());

        assertThat(AgenticCommandHandlerFunctionAdapter.resolveAgentByName(agentPlatform, "Test"))
                .isEmpty();
    }

    @Test
    void resolveAgentByNameShouldReturnEmptyWhenNoNameMatches() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("SomeOtherAgent");
        when(agentPlatform.agents()).thenReturn(List.of(agent));

        assertThat(AgenticCommandHandlerFunctionAdapter.resolveAgentByName(agentPlatform, "Test"))
                .isEmpty();
    }

    @Test
    void resolveAgentByNameShouldFindExactMatch() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("Wallet");
        when(agentPlatform.agents()).thenReturn(List.of(agent));

        assertThat(AgenticCommandHandlerFunctionAdapter.resolveAgentByName(agentPlatform, "Wallet"))
                .contains(agent);
    }

    @Test
    void resolveAgentByNameShouldFindSuffixMatch() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("WalletAgent");
        when(agentPlatform.agents()).thenReturn(List.of(agent));

        assertThat(AgenticCommandHandlerFunctionAdapter.resolveAgentByName(agentPlatform, "Wallet"))
                .contains(agent);
    }

    // -------------------------------------------------------------------------
    // Basic adapter properties
    // -------------------------------------------------------------------------

    @Test
    void isCreateShouldAlwaysReturnFalse() {
        var adapter = new AgenticCommandHandlerFunctionAdapter<>(
                aggregate, "Test", commandType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        assertThat(adapter.isCreate()).isFalse();
    }

    @Test
    void getCommandTypeShouldReturnConfiguredType() {
        var adapter = new AgenticCommandHandlerFunctionAdapter<>(
                aggregate, "Test", commandType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        assertThat(adapter.getCommandType()).isSameAs(commandType);
    }

    @Test
    void getAggregateShouldReturnConfiguredAggregate() {
        var adapter = new AgenticCommandHandlerFunctionAdapter<>(
                aggregate, "Test", commandType, agentPlatform,
                List.of(), List.of(), Collections::emptyList);

        assertThat(adapter.getAggregate()).isSameAs(aggregate);
    }
}
