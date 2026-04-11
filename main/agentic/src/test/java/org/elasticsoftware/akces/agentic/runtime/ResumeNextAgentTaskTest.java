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

import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.Blackboard;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.kafka.KafkaAggregateRuntime;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KafkaAgenticAggregateRuntime#resumeNextAgentTask}.
 */
@ExtendWith(MockitoExtension.class)
class ResumeNextAgentTaskTest {

    /** State that implements TaskAwareState. */
    record TaskState(String id, List<AssignedTask> assignedTasks) implements AggregateState, TaskAwareState {
        @Override
        public String getAggregateId() {
            return id;
        }

        @Override
        public List<AssignedTask> getAssignedTasks() {
            return assignedTasks;
        }

        @Override
        public TaskAwareState withAssignedTask(AssignedTask task) {
            var newTasks = new ArrayList<>(assignedTasks);
            newTasks.add(task);
            return new TaskState(id, List.copyOf(newTasks));
        }

        @Override
        public TaskAwareState withoutAssignedTask(String agentProcessId) {
            return new TaskState(id, assignedTasks.stream()
                    .filter(t -> !t.agentProcessId().equals(agentProcessId))
                    .toList());
        }
    }

    /** State that does NOT implement TaskAwareState. */
    record SimpleState(String id) implements AggregateState {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    @Mock
    private KafkaAggregateRuntime delegate;

    @Mock
    private AgentPlatform agentPlatform;

    @Mock
    private AgentProcess agentProcess;

    @Mock
    private Blackboard blackboard;

    @Mock
    private CommandBus commandBus;

    @Mock
    private AgenticAggregate<?> aggregate;

    private ObjectMapper objectMapper;
    private KafkaAgenticAggregateRuntime runtime;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        runtime = new KafkaAgenticAggregateRuntime(delegate, objectMapper, TaskState.class, agentPlatform, aggregate);
    }

    @Test
    void resumeShouldDoNothingWhenStateIsNull() throws IOException {
        runtime.resumeNextAgentTask(pr -> {}, () -> null, commandBus);

        verifyNoInteractions(agentPlatform);
        verifyNoInteractions(delegate);
    }

    @Test
    void resumeShouldDoNothingWhenStateDoesNotImplementTaskAwareState() throws IOException {
        var simpleRuntime = new KafkaAgenticAggregateRuntime(delegate, objectMapper, SimpleState.class, agentPlatform, aggregate);
        var state = new SimpleState("agg-1");
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "SimpleState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        simpleRuntime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verifyNoInteractions(agentPlatform);
        verify(delegate).materializeState(stateRecord);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void resumeShouldDoNothingWhenNoAssignedTasks() throws IOException {
        var state = new TaskState("agg-1", List.of());
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "TaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verifyNoInteractions(agentPlatform);
        verify(delegate).materializeState(stateRecord);
        verifyNoMoreInteractions(delegate);
    }

    @Test
    void resumeShouldTickExistingAgentProcess() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-42", "Analyze data", party, Map.of(), Instant.now());
        var state = new TaskState("agg-1", List.of(task));
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "TaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-42")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(agentPlatform).getAgentProcess("proc-42");
        verify(agentProcess).tick();
        verify(delegate).processDomainEvents(any(), eq("proc-42"), any(), any());
    }

    @Test
    void resumeShouldRoundRobinAcrossTasks() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task1 = new AssignedTask("proc-1", "Task 1", party, Map.of(), Instant.now());
        var task2 = new AssignedTask("proc-2", "Task 2", party, Map.of(), Instant.now());
        var state = new TaskState("agg-1", List.of(task1, task2));
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "TaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess(anyString())).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        // First call: should pick task at index 0 (proc-1)
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);
        verify(agentPlatform).getAgentProcess("proc-1");

        // Second call: should pick task at index 1 (proc-2)
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);
        verify(agentPlatform).getAgentProcess("proc-2");

        // Third call: should wrap around to index 0 (proc-1)
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);
        verify(agentPlatform, times(2)).getAgentProcess("proc-1");
    }

    @Test
    void resumeShouldSkipWhenAgentProcessIsNull() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-missing", "Ghost task", party, Map.of(), Instant.now());
        var state = new TaskState("agg-1", List.of(task));
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "TaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-missing")).thenReturn(null);

        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(agentPlatform).getAgentProcess("proc-missing");
        verify(delegate).materializeState(stateRecord);
    }

    // -------------------------------------------------------------------------
    // hasActiveAgentTasks
    // -------------------------------------------------------------------------

    @Test
    void hasActiveAgentTasksShouldReturnFalseWhenStateIsNull() throws IOException {
        assertThat(runtime.hasActiveAgentTasks(() -> null)).isFalse();
    }

    @Test
    void hasActiveAgentTasksShouldReturnFalseWhenNotTaskAware() throws IOException {
        var simpleRuntime = new KafkaAgenticAggregateRuntime(delegate, objectMapper, SimpleState.class, agentPlatform, aggregate);
        var state = new SimpleState("agg-1");
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "SimpleState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        assertThat(simpleRuntime.hasActiveAgentTasks(() -> stateRecord)).isFalse();
    }

    @Test
    void hasActiveAgentTasksShouldReturnFalseWhenNoTasks() throws IOException {
        var state = new TaskState("agg-1", List.of());
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "TaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        assertThat(runtime.hasActiveAgentTasks(() -> stateRecord)).isFalse();
    }

    @Test
    void hasActiveAgentTasksShouldReturnTrueWhenTasksExist() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Active task", party, Map.of(), Instant.now());
        var state = new TaskState("agg-1", List.of(task));
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "TaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        assertThat(runtime.hasActiveAgentTasks(() -> stateRecord)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Agent process finished detection
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void resumeShouldEmitAgentTaskFinishedEventWhenProcessIsFinished() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-42", "Analyze data", party, Map.of(), Instant.now());
        var state = new TaskState("agg-1", List.of(task));
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "TaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-42")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());
        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);

        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        // Verify that processDomainEvents was called with a stream that contains
        // the AgentTaskFinishedEvent
        ArgumentCaptor<Stream<DomainEvent>> streamCaptor = ArgumentCaptor.forClass(Stream.class);
        verify(delegate).processDomainEvents(streamCaptor.capture(), eq("proc-42"), any(), any());

        List<DomainEvent> events = streamCaptor.getValue().toList();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(org.elasticsoftware.akces.agentic.events.AgentTaskFinishedEvent.class);
        var finishedEvent = (org.elasticsoftware.akces.agentic.events.AgentTaskFinishedEvent) events.getFirst();
        assertThat(finishedEvent.agentProcessId()).isEqualTo("proc-42");
        assertThat(finishedEvent.status()).isEqualTo(AgentProcessStatusCode.COMPLETED);
        assertThat(finishedEvent.agenticAggregateId()).isEqualTo("agg-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeShouldNotEmitAgentTaskFinishedEventWhenProcessIsNotFinished() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-42", "Analyze data", party, Map.of(), Instant.now());
        var state = new TaskState("agg-1", List.of(task));
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "TaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-42")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());
        when(agentProcess.getFinished()).thenReturn(false);

        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        // Verify that processDomainEvents was called with a stream that does NOT
        // contain an AgentTaskFinishedEvent
        ArgumentCaptor<Stream<DomainEvent>> streamCaptor = ArgumentCaptor.forClass(Stream.class);
        verify(delegate).processDomainEvents(streamCaptor.capture(), eq("proc-42"), any(), any());

        List<DomainEvent> events = streamCaptor.getValue().toList();
        assertThat(events).noneMatch(e -> e instanceof org.elasticsoftware.akces.agentic.events.AgentTaskFinishedEvent);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeShouldEmitAgentTaskFinishedEventWithFailedStatus() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-99", "Failing task", party, Map.of(), Instant.now());
        var state = new TaskState("agg-1", List.of(task));
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "TaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-99")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());
        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.FAILED);

        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        ArgumentCaptor<Stream<DomainEvent>> streamCaptor = ArgumentCaptor.forClass(Stream.class);
        verify(delegate).processDomainEvents(streamCaptor.capture(), eq("proc-99"), any(), any());

        List<DomainEvent> events = streamCaptor.getValue().toList();
        assertThat(events).hasSize(1);
        var finishedEvent = (org.elasticsoftware.akces.agentic.events.AgentTaskFinishedEvent) events.getFirst();
        assertThat(finishedEvent.agentProcessId()).isEqualTo("proc-99");
        assertThat(finishedEvent.status()).isEqualTo(AgentProcessStatusCode.FAILED);
    }
}
