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

import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.events.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the built-in event-sourcing handler in {@link KafkaAgenticAggregateRuntime}
 * that processes {@link AgentTaskAssignedEvent} by updating {@link TaskAwareState}.
 *
 * <p>Follows the same pattern as {@link MemoryEventSourcingTest}: event-sourcing handler
 * methods are tested through their actual invocations.
 */
class TaskEventSourcingTest {

    /** Concrete {@link TaskAwareState} implementation for test assertions. */
    record TestTaskState(
            String id,
            List<AssignedTask> assignedTasks
    ) implements AggregateState, TaskAwareState {

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
            var updated = new ArrayList<>(assignedTasks);
            updated.add(task);
            return new TestTaskState(id, List.copyOf(updated));
        }

        @Override
        public TaskAwareState withoutAssignedTask(String agentProcessId) {
            var updated = assignedTasks.stream()
                    .filter(t -> !t.agentProcessId().equals(agentProcessId))
                    .toList();
            return new TestTaskState(id, updated);
        }
    }

    /** A non-TaskAwareState state for testing error paths. */
    record PlainState(String id) implements AggregateState {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    // -------------------------------------------------------------------------
    // onAgentTaskAssigned tests
    // -------------------------------------------------------------------------

    @Test
    void onAgentTaskAssignedShouldAppendTaskToState() {
        Instant now = Instant.parse("2026-04-10T12:00:00Z");
        var party = new HumanRequestingParty("user-1", "Alice", "analyst");
        var metadata = Map.of("correlationId", "corr-123");
        var event = new AgentTaskAssignedEvent("agg-1", "proc-1", "Analyze data",
                party, metadata, now);
        var initialState = new TestTaskState("agg-1", List.of());

        AggregateState result = KafkaAgenticAggregateRuntime.onAgentTaskAssigned(event, initialState);

        assertThat(result).isInstanceOf(TestTaskState.class);
        var state = (TestTaskState) result;
        assertThat(state.getAssignedTasks()).hasSize(1);
        AssignedTask assigned = state.getAssignedTasks().getFirst();
        assertThat(assigned.agentProcessId()).isEqualTo("proc-1");
        assertThat(assigned.taskDescription()).isEqualTo("Analyze data");
        assertThat(assigned.requestingParty()).isEqualTo(party);
        assertThat(assigned.taskMetadata()).isEqualTo(metadata);
        assertThat(assigned.assignedAt()).isEqualTo(now);
    }

    @Test
    void onAgentTaskAssignedShouldThrowWhenStateIsNotTaskAware() {
        var event = new AgentTaskAssignedEvent("agg-1", "proc-1", "task",
                new HumanRequestingParty("u", "n", "r"), null, Instant.now());
        var plainState = new PlainState("agg-1");

        assertThatThrownBy(() -> KafkaAgenticAggregateRuntime.onAgentTaskAssigned(event, plainState))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not implement TaskAwareState");
    }

    @Test
    void onAgentTaskAssignedShouldPreserveExistingTasks() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        var party = new AgentRequestingParty("agent-1", "TestAgent", "supervisor");
        var existing = new AssignedTask("proc-old", "Old task", party, null, t1);
        var state = new TestTaskState("agg-1", List.of(existing));

        var event = new AgentTaskAssignedEvent("agg-1", "proc-new", "New task",
                party, Map.of("priority", "high"), t2);

        AggregateState result = KafkaAgenticAggregateRuntime.onAgentTaskAssigned(event, state);

        var newState = (TestTaskState) result;
        assertThat(newState.getAssignedTasks()).hasSize(2);
        assertThat(newState.getAssignedTasks().get(0).agentProcessId()).isEqualTo("proc-old");
        assertThat(newState.getAssignedTasks().get(1).agentProcessId()).isEqualTo("proc-new");
    }

    // -------------------------------------------------------------------------
    // handleTaskEvent dispatch tests
    // -------------------------------------------------------------------------

    @Test
    void handleTaskEventShouldDispatchAgentTaskAssignedEvent() {
        Instant now = Instant.now();
        var party = new HumanRequestingParty("user-1", "Alice", "analyst");
        var event = new AgentTaskAssignedEvent("agg-1", "proc-1", "task", party, null, now);
        var state = new TestTaskState("agg-1", List.of());

        AggregateState result = KafkaAgenticAggregateRuntime.handleTaskEvent(event, state);

        assertThat(result).isInstanceOf(TestTaskState.class);
        assertThat(((TestTaskState) result).getAssignedTasks()).hasSize(1);
    }

    @Test
    void handleTaskEventShouldThrowForUnknownEventType() {
        var unknownEvent = new DomainEvent() {
            @Override
            public String getAggregateId() {
                return "agg-1";
            }
        };
        var state = new TestTaskState("agg-1", List.of());

        assertThatThrownBy(() -> KafkaAgenticAggregateRuntime.handleTaskEvent(unknownEvent, state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported task event type");
    }

    // -------------------------------------------------------------------------
    // State reconstruction from event sequences
    // -------------------------------------------------------------------------

    @Test
    void shouldReconstructStateFromMultipleTaskAssignedEvents() {
        AggregateState state = new TestTaskState("agg-1", List.of());
        var party = new HumanRequestingParty("user-1", "Alice", "analyst");

        for (int i = 1; i <= 3; i++) {
            var event = new AgentTaskAssignedEvent("agg-1", "proc-" + i, "Task " + i,
                    party, null, Instant.now().plusSeconds(i));
            state = KafkaAgenticAggregateRuntime.handleTaskEvent(event, state);
        }

        var finalState = (TestTaskState) state;
        assertThat(finalState.getAssignedTasks()).hasSize(3);
        assertThat(finalState.getAssignedTasks()).extracting(AssignedTask::agentProcessId)
                .containsExactly("proc-1", "proc-2", "proc-3");
    }
}
