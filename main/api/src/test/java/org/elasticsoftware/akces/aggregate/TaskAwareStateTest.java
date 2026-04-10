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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link TaskAwareState} interface contract and the {@link AssignedTask} record,
 * following the same pattern as {@link MemoryAwareStateTest}. Per framework testing guidelines,
 * interfaces and records are tested through concrete implementations rather than directly.
 */
class TaskAwareStateTest {

    /** Concrete {@link TaskAwareState} implementation for testing. */
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

    // -------------------------------------------------------------------------
    // AssignedTask record tests
    // -------------------------------------------------------------------------

    @Test
    void assignedTaskShouldPopulateAllFields() {
        Instant now = Instant.now();
        var party = new HumanRequestingParty("user-1", "analyst");
        var metadata = Map.of("correlationId", "corr-123");

        var task = new AssignedTask("proc-1", "Analyze data", party, metadata, now);

        assertThat(task.agentProcessId()).isEqualTo("proc-1");
        assertThat(task.taskDescription()).isEqualTo("Analyze data");
        assertThat(task.requestingParty()).isEqualTo(party);
        assertThat(task.taskMetadata()).isEqualTo(metadata);
        assertThat(task.assignedAt()).isEqualTo(now);
    }

    @Test
    void assignedTaskEqualityShouldHold() {
        Instant now = Instant.parse("2026-04-10T12:00:00Z");
        var party = new AgentRequestingParty("agent-1", "TestAgent", "supervisor");
        var a = new AssignedTask("proc-1", "Do task", party, Map.of(), now);
        var b = new AssignedTask("proc-1", "Do task", party, Map.of(), now);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void assignedTaskInequalityWhenProcessIdDiffers() {
        Instant now = Instant.now();
        var party = new HumanRequestingParty("user-1", "analyst");
        var a = new AssignedTask("proc-1", "task", party, null, now);
        var b = new AssignedTask("proc-2", "task", party, null, now);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void assignedTaskShouldAllowNullMetadata() {
        Instant now = Instant.now();
        var party = new HumanRequestingParty("user-1", "analyst");

        var task = new AssignedTask("proc-1", "task", party, null, now);

        assertThat(task.taskMetadata()).isNull();
    }

    // -------------------------------------------------------------------------
    // TaskAwareState contract tests
    // -------------------------------------------------------------------------

    @Test
    void emptyStateShouldReturnEmptyTaskList() {
        var state = new TestTaskState("agg-1", List.of());

        assertThat(state.getAssignedTasks()).isEmpty();
    }

    @Test
    void withAssignedTaskShouldAppendToEnd() {
        Instant now = Instant.now();
        var party = new HumanRequestingParty("user-1", "analyst");
        var task1 = new AssignedTask("proc-1", "Task 1", party, null, now);
        var task2 = new AssignedTask("proc-2", "Task 2", party, null, now.plusSeconds(1));

        TaskAwareState state = new TestTaskState("agg-1", List.of());
        state = state.withAssignedTask(task1);
        state = state.withAssignedTask(task2);

        assertThat(state.getAssignedTasks()).hasSize(2);
        assertThat(state.getAssignedTasks().get(0).agentProcessId()).isEqualTo("proc-1");
        assertThat(state.getAssignedTasks().get(1).agentProcessId()).isEqualTo("proc-2");
    }

    @Test
    void withoutAssignedTaskShouldRemoveByProcessId() {
        Instant now = Instant.now();
        var party = new AgentRequestingParty("agent-1", "Agent", "supervisor");
        var task1 = new AssignedTask("proc-1", "Task 1", party, null, now);
        var task2 = new AssignedTask("proc-2", "Task 2", party, null, now);
        var task3 = new AssignedTask("proc-3", "Task 3", party, null, now);

        TaskAwareState state = new TestTaskState("agg-1", List.of(task1, task2, task3));
        state = state.withoutAssignedTask("proc-2");

        assertThat(state.getAssignedTasks()).hasSize(2);
        assertThat(state.getAssignedTasks()).extracting(AssignedTask::agentProcessId)
                .containsExactly("proc-1", "proc-3");
    }

    @Test
    void withoutNonExistentProcessIdShouldReturnEquivalentState() {
        Instant now = Instant.now();
        var party = new HumanRequestingParty("user-1", "analyst");
        var task = new AssignedTask("proc-1", "Task 1", party, null, now);

        TaskAwareState state = new TestTaskState("agg-1", List.of(task));
        TaskAwareState unchanged = state.withoutAssignedTask("non-existent");

        assertThat(unchanged.getAssignedTasks()).isEqualTo(state.getAssignedTasks());
    }

    @Test
    void taskRoundTripThroughTaskAwareState() {
        Instant now = Instant.now();
        var party = new HumanRequestingParty("user-42", "manager");
        var metadata = Map.of("priority", "high", "deadline", "2026-05-01");
        var task = new AssignedTask("proc-42", "Urgent analysis", party, metadata, now);

        var emptyState = new TestTaskState("agg-1", List.of());
        var stateWithTask = (TestTaskState) emptyState.withAssignedTask(task);

        assertThat(stateWithTask.getAssignedTasks()).hasSize(1);
        assertThat(stateWithTask.getAssignedTasks().getFirst()).isEqualTo(task);

        // Verify all fields survived the round-trip
        AssignedTask retrieved = stateWithTask.getAssignedTasks().getFirst();
        assertThat(retrieved.agentProcessId()).isEqualTo("proc-42");
        assertThat(retrieved.taskDescription()).isEqualTo("Urgent analysis");
        assertThat(retrieved.requestingParty()).isEqualTo(party);
        assertThat(retrieved.taskMetadata()).isEqualTo(metadata);
        assertThat(retrieved.assignedAt()).isEqualTo(now);
    }
}
