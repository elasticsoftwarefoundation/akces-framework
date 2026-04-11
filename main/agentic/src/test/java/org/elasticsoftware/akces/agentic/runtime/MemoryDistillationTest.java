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
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.ProcessOptions;
import org.elasticsoftware.akces.agentic.embabel.MemoryDistillationInput;
import org.elasticsoftware.akces.agentic.embabel.MemoryDistillationResult;
import org.elasticsoftware.akces.agentic.embabel.MemoryDistillerAgent;
import org.elasticsoftware.akces.agentic.events.AgentTaskFinishedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the memory distillation integration in
 * {@link KafkaAgenticAggregateRuntime#resumeNextAgentTask}.
 */
@ExtendWith(MockitoExtension.class)
class MemoryDistillationTest {

    /** State that implements both TaskAwareState and MemoryAwareState. */
    record MemoryTaskState(
            String id,
            List<AssignedTask> assignedTasks,
            List<AgenticAggregateMemory> memories
    ) implements AggregateState, TaskAwareState, MemoryAwareState {

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
            return new MemoryTaskState(id, List.copyOf(newTasks), memories);
        }

        @Override
        public TaskAwareState withoutAssignedTask(String agentProcessId) {
            return new MemoryTaskState(id, assignedTasks.stream()
                    .filter(t -> !t.agentProcessId().equals(agentProcessId))
                    .toList(), memories);
        }

        @Override
        public List<AgenticAggregateMemory> getMemories() {
            return memories;
        }

        @Override
        public MemoryAwareState withMemory(AgenticAggregateMemory memory) {
            var updated = new ArrayList<>(memories);
            updated.add(memory);
            return new MemoryTaskState(id, assignedTasks, List.copyOf(updated));
        }

        @Override
        public MemoryAwareState withoutMemory(String memoryId) {
            return new MemoryTaskState(id, assignedTasks, memories.stream()
                    .filter(m -> !m.memoryId().equals(memoryId))
                    .toList());
        }
    }

    private static final Instant NOW = Instant.now();

    @Mock
    private KafkaAggregateRuntime delegate;

    @Mock
    private AgentPlatform agentPlatform;

    @Mock
    private AgentProcess agentProcess;

    @Mock
    private AgentProcess distillerProcess;

    @Mock
    private Blackboard blackboard;

    @Mock
    private Blackboard distillerBlackboard;

    @Mock
    private CommandBus commandBus;

    @Mock
    private AgenticAggregate<?> aggregate;

    @Mock
    private Agent memoryDistillerAgent;

    private ObjectMapper objectMapper;
    private KafkaAgenticAggregateRuntime runtime;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        runtime = new KafkaAgenticAggregateRuntime(
                delegate, objectMapper, MemoryTaskState.class, agentPlatform, aggregate, 100, 10);
    }

    // -------------------------------------------------------------------------
    // Memory distillation on COMPLETED process
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void resumeShouldDistillMemoriesOnCompletedProcess() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var state = new MemoryTaskState("agg-1", List.of(task), List.of());
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        // Process finishes with COMPLETED status
        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        when(agentProcess.getHistory()).thenReturn(List.of());

        // Set up MemoryDistillerAgent resolution
        when(memoryDistillerAgent.getName()).thenReturn(MemoryDistillerAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(memoryDistillerAgent));

        // Set up distiller process
        var distillationResult = new MemoryDistillationResult(
                List.of(new MemoryStoredEvent("agg-1", "mem-new-1", "testing", "Use JUnit 5",
                        "src/test", "best practice", NOW)),
                List.of());
        when(agentPlatform.createAgentProcess(eq(memoryDistillerAgent), eq(ProcessOptions.DEFAULT), anyMap()))
                .thenReturn(distillerProcess);
        when(distillerProcess.getBlackboard()).thenReturn(distillerBlackboard);
        when(distillerBlackboard.last(MemoryDistillationResult.class)).thenReturn(distillationResult);

        // Capture the events stream
        ArgumentCaptor<Stream<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(Stream.class);
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(delegate).processDomainEvents(eventsCaptor.capture(), eq("proc-1"), any(), any());
        List<DomainEvent> events = eventsCaptor.getValue().toList();

        // Should have: tick events (empty) + AgentTaskFinishedEvent + MemoryStoredEvent
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(AgentTaskFinishedEvent.class);
        assertThat(events.get(1)).isInstanceOf(MemoryStoredEvent.class);

        MemoryStoredEvent storedEvent = (MemoryStoredEvent) events.get(1);
        assertThat(storedEvent.agenticAggregateId()).isEqualTo("agg-1");
        assertThat(storedEvent.subject()).isEqualTo("testing");
        assertThat(storedEvent.fact()).isEqualTo("Use JUnit 5");

        // Verify distiller process was run to completion
        verify(distillerProcess).run();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeShouldNotDistillMemoriesOnFailedProcess() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var state = new MemoryTaskState("agg-1", List.of(task), List.of());
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        // Process finishes with FAILED status
        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.FAILED);

        ArgumentCaptor<Stream<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(Stream.class);
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(delegate).processDomainEvents(eventsCaptor.capture(), eq("proc-1"), any(), any());
        List<DomainEvent> events = eventsCaptor.getValue().toList();

        // Should only have AgentTaskFinishedEvent, no memory distillation
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(AgentTaskFinishedEvent.class);

        // Should NOT create a distiller process
        verify(agentPlatform, never()).createAgentProcess(
                argThat(a -> MemoryDistillerAgent.AGENT_NAME.equals(a.getName())),
                any(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeShouldSkipDistillationWhenDistillerAgentNotDeployed() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var state = new MemoryTaskState("agg-1", List.of(task), List.of());
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);

        // No MemoryDistillerAgent deployed
        when(agentPlatform.agents()).thenReturn(List.of());

        ArgumentCaptor<Stream<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(Stream.class);
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(delegate).processDomainEvents(eventsCaptor.capture(), eq("proc-1"), any(), any());
        List<DomainEvent> events = eventsCaptor.getValue().toList();

        // Should only have AgentTaskFinishedEvent
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(AgentTaskFinishedEvent.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeShouldHandleDistillationFailureGracefully() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var state = new MemoryTaskState("agg-1", List.of(task), List.of());
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        when(agentProcess.getHistory()).thenReturn(List.of());

        when(memoryDistillerAgent.getName()).thenReturn(MemoryDistillerAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(memoryDistillerAgent));

        // Distiller throws an exception
        when(agentPlatform.createAgentProcess(eq(memoryDistillerAgent), eq(ProcessOptions.DEFAULT), anyMap()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        ArgumentCaptor<Stream<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(Stream.class);
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(delegate).processDomainEvents(eventsCaptor.capture(), eq("proc-1"), any(), any());
        List<DomainEvent> events = eventsCaptor.getValue().toList();

        // Should still emit AgentTaskFinishedEvent despite distillation failure
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(AgentTaskFinishedEvent.class);
    }

    // -------------------------------------------------------------------------
    // Memory limit enforcement
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void distillationShouldEnforceMaxMemoriesLimit() throws IOException {
        // maxTotalMemories = 5, maxMemoriesAdded = 10 — capacity is the binding constraint
        runtime = new KafkaAgenticAggregateRuntime(
                delegate, objectMapper, MemoryTaskState.class, agentPlatform, aggregate, 5, 10);

        // State already has 3 memories, so capacityLeft = 2, effectiveLimit = min(2, 10) = 2
        var existingMemories = List.of(
                new AgenticAggregateMemory("mem-1", "s1", "f1", "c1", "r1", Instant.now()),
                new AgenticAggregateMemory("mem-2", "s2", "f2", "c2", "r2", Instant.now()),
                new AgenticAggregateMemory("mem-3", "s3", "f3", "c3", "r3", Instant.now())
        );
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var state = new MemoryTaskState("agg-1", List.of(task), existingMemories);
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        when(agentProcess.getHistory()).thenReturn(List.of());

        when(memoryDistillerAgent.getName()).thenReturn(MemoryDistillerAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(memoryDistillerAgent));

        // Agent tries to store 5 memories but only 2 should be allowed
        var distillationResult = new MemoryDistillationResult(
                List.of(
                        new MemoryStoredEvent("agg-1", "n1", "s1", "fact1", "c1", "r1", NOW),
                        new MemoryStoredEvent("agg-1", "n2", "s2", "fact2", "c2", "r2", NOW),
                        new MemoryStoredEvent("agg-1", "n3", "s3", "fact3", "c3", "r3", NOW),
                        new MemoryStoredEvent("agg-1", "n4", "s4", "fact4", "c4", "r4", NOW),
                        new MemoryStoredEvent("agg-1", "n5", "s5", "fact5", "c5", "r5", NOW)
                ),
                List.of());
        when(agentPlatform.createAgentProcess(eq(memoryDistillerAgent), eq(ProcessOptions.DEFAULT), anyMap()))
                .thenReturn(distillerProcess);
        when(distillerProcess.getBlackboard()).thenReturn(distillerBlackboard);
        when(distillerBlackboard.last(MemoryDistillationResult.class)).thenReturn(distillationResult);

        ArgumentCaptor<Stream<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(Stream.class);
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(delegate).processDomainEvents(eventsCaptor.capture(), eq("proc-1"), any(), any());
        List<DomainEvent> events = eventsCaptor.getValue().toList();

        // 1 finished + 2 stored (truncated from 5)
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOf(AgentTaskFinishedEvent.class);
        long storedCount = events.stream().filter(e -> e instanceof MemoryStoredEvent).count();
        assertThat(storedCount).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void distillationShouldEnforceMaxMemoriesAddedLimit() throws IOException {
        // maxTotalMemories = 100, maxMemoriesAdded = 2 — per-distillation budget is the constraint
        runtime = new KafkaAgenticAggregateRuntime(
                delegate, objectMapper, MemoryTaskState.class, agentPlatform, aggregate, 100, 2);

        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var state = new MemoryTaskState("agg-1", List.of(task), List.of());
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        when(agentProcess.getHistory()).thenReturn(List.of());

        when(memoryDistillerAgent.getName()).thenReturn(MemoryDistillerAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(memoryDistillerAgent));

        // Agent tries to store 5 memories but only 2 should be allowed (maxMemoriesAdded=2)
        var distillationResult = new MemoryDistillationResult(
                List.of(
                        new MemoryStoredEvent("agg-1", "n1", "s1", "fact1", "c1", "r1", NOW),
                        new MemoryStoredEvent("agg-1", "n2", "s2", "fact2", "c2", "r2", NOW),
                        new MemoryStoredEvent("agg-1", "n3", "s3", "fact3", "c3", "r3", NOW),
                        new MemoryStoredEvent("agg-1", "n4", "s4", "fact4", "c4", "r4", NOW),
                        new MemoryStoredEvent("agg-1", "n5", "s5", "fact5", "c5", "r5", NOW)
                ),
                List.of());
        when(agentPlatform.createAgentProcess(eq(memoryDistillerAgent), eq(ProcessOptions.DEFAULT), anyMap()))
                .thenReturn(distillerProcess);
        when(distillerProcess.getBlackboard()).thenReturn(distillerBlackboard);
        when(distillerBlackboard.last(MemoryDistillationResult.class)).thenReturn(distillationResult);

        ArgumentCaptor<Stream<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(Stream.class);
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(delegate).processDomainEvents(eventsCaptor.capture(), eq("proc-1"), any(), any());
        List<DomainEvent> events = eventsCaptor.getValue().toList();

        // 1 finished + 2 stored (truncated from 5 by maxMemoriesAdded)
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOf(AgentTaskFinishedEvent.class);
        long storedCount = events.stream().filter(e -> e instanceof MemoryStoredEvent).count();
        assertThat(storedCount).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void distillationShouldAllowMoreStoredWhenMemoriesAreRevoked() throws IOException {
        // maxTotalMemories = 5, maxMemoriesAdded = 10; existing = 4, so capacityLeft = 1
        // But we also revoke 2, so we can store up to 3 (1 + 2)
        runtime = new KafkaAgenticAggregateRuntime(
                delegate, objectMapper, MemoryTaskState.class, agentPlatform, aggregate, 5, 10);

        var existingMemories = List.of(
                new AgenticAggregateMemory("mem-1", "s1", "f1", "c1", "r1", Instant.now()),
                new AgenticAggregateMemory("mem-2", "s2", "f2", "c2", "r2", Instant.now()),
                new AgenticAggregateMemory("mem-3", "s3", "f3", "c3", "r3", Instant.now()),
                new AgenticAggregateMemory("mem-4", "s4", "f4", "c4", "r4", Instant.now())
        );
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var state = new MemoryTaskState("agg-1", List.of(task), existingMemories);
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        when(agentProcess.getHistory()).thenReturn(List.of());

        when(memoryDistillerAgent.getName()).thenReturn(MemoryDistillerAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(memoryDistillerAgent));

        // Revoke 2, store 3
        var distillationResult = new MemoryDistillationResult(
                List.of(
                        new MemoryStoredEvent("agg-1", "n1", "s1", "new-fact1", "c1", "r1", NOW),
                        new MemoryStoredEvent("agg-1", "n2", "s2", "new-fact2", "c2", "r2", NOW),
                        new MemoryStoredEvent("agg-1", "n3", "s3", "new-fact3", "c3", "r3", NOW)
                ),
                List.of(
                        new MemoryRevokedEvent("agg-1", "mem-1", "outdated", NOW),
                        new MemoryRevokedEvent("agg-1", "mem-2", "superseded", NOW)
                ));
        when(agentPlatform.createAgentProcess(eq(memoryDistillerAgent), eq(ProcessOptions.DEFAULT), anyMap()))
                .thenReturn(distillerProcess);
        when(distillerProcess.getBlackboard()).thenReturn(distillerBlackboard);
        when(distillerBlackboard.last(MemoryDistillationResult.class)).thenReturn(distillationResult);

        ArgumentCaptor<Stream<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(Stream.class);
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(delegate).processDomainEvents(eventsCaptor.capture(), eq("proc-1"), any(), any());
        List<DomainEvent> events = eventsCaptor.getValue().toList();

        // 1 finished + 2 revoked + 3 stored = 6
        assertThat(events).hasSize(6);
        assertThat(events.get(0)).isInstanceOf(AgentTaskFinishedEvent.class);

        long revokedCount = events.stream().filter(e -> e instanceof MemoryRevokedEvent).count();
        long storedCount = events.stream().filter(e -> e instanceof MemoryStoredEvent).count();
        assertThat(revokedCount).isEqualTo(2);
        assertThat(storedCount).isEqualTo(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void distillationShouldSkipRevocationOfNonExistentMemories() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var existingMemories = List.of(
                new AgenticAggregateMemory("mem-1", "s1", "f1", "c1", "r1", Instant.now())
        );
        var state = new MemoryTaskState("agg-1", List.of(task), existingMemories);
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        when(agentProcess.getHistory()).thenReturn(List.of());

        when(memoryDistillerAgent.getName()).thenReturn(MemoryDistillerAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(memoryDistillerAgent));

        // Try to revoke a memory that doesn't exist
        var distillationResult = new MemoryDistillationResult(
                List.of(),
                List.of(
                        new MemoryRevokedEvent("agg-1", "mem-1", "outdated", NOW),
                        new MemoryRevokedEvent("agg-1", "non-existent", "cleanup", NOW)
                ));
        when(agentPlatform.createAgentProcess(eq(memoryDistillerAgent), eq(ProcessOptions.DEFAULT), anyMap()))
                .thenReturn(distillerProcess);
        when(distillerProcess.getBlackboard()).thenReturn(distillerBlackboard);
        when(distillerBlackboard.last(MemoryDistillationResult.class)).thenReturn(distillationResult);

        ArgumentCaptor<Stream<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(Stream.class);
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(delegate).processDomainEvents(eventsCaptor.capture(), eq("proc-1"), any(), any());
        List<DomainEvent> events = eventsCaptor.getValue().toList();

        // 1 finished + 1 revoked (only mem-1, not non-existent)
        long revokedCount = events.stream().filter(e -> e instanceof MemoryRevokedEvent).count();
        assertThat(revokedCount).isEqualTo(1);

        MemoryRevokedEvent revokedEvent = events.stream()
                .filter(e -> e instanceof MemoryRevokedEvent)
                .map(e -> (MemoryRevokedEvent) e)
                .findFirst()
                .orElseThrow();
        assertThat(revokedEvent.memoryId()).isEqualTo("mem-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void distillationShouldHandleNullResultGracefully() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var state = new MemoryTaskState("agg-1", List.of(task), List.of());
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        when(agentProcess.getHistory()).thenReturn(List.of());

        when(memoryDistillerAgent.getName()).thenReturn(MemoryDistillerAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(memoryDistillerAgent));

        // Distiller produces no result
        when(agentPlatform.createAgentProcess(eq(memoryDistillerAgent), eq(ProcessOptions.DEFAULT), anyMap()))
                .thenReturn(distillerProcess);
        when(distillerProcess.getBlackboard()).thenReturn(distillerBlackboard);
        when(distillerBlackboard.last(MemoryDistillationResult.class)).thenReturn(null);

        ArgumentCaptor<Stream<DomainEvent>> eventsCaptor = ArgumentCaptor.forClass(Stream.class);
        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(delegate).processDomainEvents(eventsCaptor.capture(), eq("proc-1"), any(), any());
        List<DomainEvent> events = eventsCaptor.getValue().toList();

        // Should only have AgentTaskFinishedEvent
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(AgentTaskFinishedEvent.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void distillationShouldPassCorrectBindingsToDistillerProcess() throws IOException {
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task", party, Map.of(), Instant.now());
        var existingMemory = new AgenticAggregateMemory(
                "mem-1", "testing", "Use JUnit", "cite", "reason", Instant.now());
        var state = new MemoryTaskState("agg-1", List.of(task), List.of(existingMemory));
        byte[] payload = objectMapper.writeValueAsBytes(state);
        var stateRecord = new AggregateStateRecord(null, "MemoryTaskState", 1, payload,
                PayloadEncoding.JSON, "agg-1", null, 1L);
        when(delegate.materializeState(stateRecord)).thenReturn(state);

        when(agentPlatform.getAgentProcess("proc-1")).thenReturn(agentProcess);
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of("some-object"));
        when(delegate.getAllDomainEventTypes()).thenReturn(List.of());

        when(agentProcess.getFinished()).thenReturn(true);
        when(agentProcess.getStatus()).thenReturn(AgentProcessStatusCode.COMPLETED);
        when(agentProcess.getHistory()).thenReturn(List.of());

        when(memoryDistillerAgent.getName()).thenReturn(MemoryDistillerAgent.AGENT_NAME);
        when(agentPlatform.agents()).thenReturn(List.of(memoryDistillerAgent));

        when(agentPlatform.createAgentProcess(eq(memoryDistillerAgent), eq(ProcessOptions.DEFAULT), anyMap()))
                .thenReturn(distillerProcess);
        when(distillerProcess.getBlackboard()).thenReturn(distillerBlackboard);
        when(distillerBlackboard.last(MemoryDistillationResult.class)).thenReturn(null);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map<String, Object>> bindingsCaptor = ArgumentCaptor.forClass(Map.class);

        runtime.resumeNextAgentTask(pr -> {}, () -> stateRecord, commandBus);

        verify(agentPlatform).createAgentProcess(
                eq(memoryDistillerAgent), eq(ProcessOptions.DEFAULT), bindingsCaptor.capture());

        Map<String, Object> bindings = bindingsCaptor.getValue();
        assertThat(bindings).containsKey("input");
        assertThat(bindings.get("input")).isInstanceOf(MemoryDistillationInput.class);

        MemoryDistillationInput input = (MemoryDistillationInput) bindings.get("input");
        assertThat(input.agentTask()).isEqualTo(task);
        assertThat(input.history()).isEqualTo(List.of());
        assertThat(input.blackboardObjects()).isEqualTo(List.of("some-object"));
        assertThat(input.existingMemories()).isEqualTo(List.of(existingMemory));
        assertThat(input.maxTotalMemories()).isEqualTo(100);
        assertThat(input.maxMemoriesAdded()).isEqualTo(10);
    }
}
