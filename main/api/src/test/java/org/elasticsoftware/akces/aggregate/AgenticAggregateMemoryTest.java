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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link AgenticAggregateMemory} record by exercising it through the
 * {@link MemoryAwareState} interface contract. Per framework testing guidelines, records
 * are not tested in isolation — instead, their behaviour is verified through the components
 * that use them.
 */
class AgenticAggregateMemoryTest {

    /** Minimal {@link MemoryAwareState} implementation used for round-trip verification. */
    record TestMemoryState(
            String id,
            List<AgenticAggregateMemory> memories,
            List<MemoryDistillation> memoryDistillations
    ) implements AggregateState, MemoryAwareState {

        TestMemoryState(String id, List<AgenticAggregateMemory> memories) {
            this(id, memories, List.of());
        }

        @Override
        public String getAggregateId() {
            return id;
        }

        @Override
        public List<AgenticAggregateMemory> getMemories() {
            return memories;
        }

        @Override
        public MemoryAwareState withMemory(AgenticAggregateMemory memory) {
            var updated = new ArrayList<>(memories);
            updated.add(memory);
            return new TestMemoryState(id, List.copyOf(updated), memoryDistillations);
        }

        @Override
        public MemoryAwareState withoutMemory(String memoryId) {
            var updated = memories.stream()
                    .filter(m -> !m.memoryId().equals(memoryId))
                    .toList();
            return new TestMemoryState(id, updated, memoryDistillations);
        }

        @Override
        public List<MemoryDistillation> getMemoryDistillations() {
            return memoryDistillations;
        }

        @Override
        public MemoryAwareState withMemoryDistillation(MemoryDistillation distillation) {
            var updated = new ArrayList<>(memoryDistillations);
            updated.add(distillation);
            return new TestMemoryState(id, memories, List.copyOf(updated));
        }

        @Override
        public MemoryAwareState withoutMemoryDistillation(String agentProcessId) {
            return new TestMemoryState(id, memories, memoryDistillations.stream()
                    .filter(d -> !d.agentProcessId().equals(agentProcessId))
                    .toList());
        }
    }

    @Test
    void constructionShouldPopulateAllFields() {
        Instant now = Instant.now();
        var memory = new AgenticAggregateMemory(
                "mem-1", "testing", "Use JUnit 5", "build.gradle:10", "consistency", now);

        assertThat(memory.memoryId()).isEqualTo("mem-1");
        assertThat(memory.subject()).isEqualTo("testing");
        assertThat(memory.fact()).isEqualTo("Use JUnit 5");
        assertThat(memory.citations()).isEqualTo("build.gradle:10");
        assertThat(memory.reason()).isEqualTo("consistency");
        assertThat(memory.storedAt()).isEqualTo(now);
    }

    @Test
    void equalityBetweenIdenticalRecordsShouldHold() {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        var a = new AgenticAggregateMemory("id-1", "sub", "fact", "cite", "reason", now);
        var b = new AgenticAggregateMemory("id-1", "sub", "fact", "cite", "reason", now);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void inequalityWhenFieldsDiffer() {
        Instant now = Instant.now();
        var a = new AgenticAggregateMemory("id-1", "sub", "fact", "cite", "reason", now);
        var b = new AgenticAggregateMemory("id-2", "sub", "fact", "cite", "reason", now);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void memoryRoundTripThroughMemoryAwareState() {
        Instant now = Instant.now();
        var memory = new AgenticAggregateMemory(
                "mem-42", "auth", "Use JWT", "auth.java:5", "security", now);

        var emptyState = new TestMemoryState("agg-1", List.of());
        var stateWithMemory = (TestMemoryState) emptyState.withMemory(memory);

        assertThat(stateWithMemory.getMemories()).hasSize(1);
        assertThat(stateWithMemory.getMemories().getFirst()).isEqualTo(memory);

        // Verify all 6 fields survived the round-trip
        AgenticAggregateMemory retrieved = stateWithMemory.getMemories().getFirst();
        assertThat(retrieved.memoryId()).isEqualTo("mem-42");
        assertThat(retrieved.subject()).isEqualTo("auth");
        assertThat(retrieved.fact()).isEqualTo("Use JWT");
        assertThat(retrieved.citations()).isEqualTo("auth.java:5");
        assertThat(retrieved.reason()).isEqualTo("security");
        assertThat(retrieved.storedAt()).isEqualTo(now);
    }

    @Test
    void multipleMemoriesCanBeStoredAndRetrieved() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", t1);
        var m2 = new AgenticAggregateMemory("m2", "s2", "f2", "c2", "r2", t2);

        MemoryAwareState state = new TestMemoryState("agg-1", List.of());
        state = state.withMemory(m1);
        state = state.withMemory(m2);

        assertThat(state.getMemories()).hasSize(2);
        assertThat(state.getMemories().get(0)).isEqualTo(m1);
        assertThat(state.getMemories().get(1)).isEqualTo(m2);
    }

    @Test
    void removeMemoryByIdShouldReturnStateWithoutIt() {
        Instant now = Instant.now();
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", now);
        var m2 = new AgenticAggregateMemory("m2", "s2", "f2", "c2", "r2", now);

        MemoryAwareState state = new TestMemoryState("agg-1", List.of());
        state = state.withMemory(m1);
        state = state.withMemory(m2);
        state = state.withoutMemory("m1");

        assertThat(state.getMemories()).hasSize(1);
        assertThat(state.getMemories().getFirst().memoryId()).isEqualTo("m2");
    }

    @Test
    void removeNonExistentMemoryIdShouldReturnUnchangedState() {
        Instant now = Instant.now();
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", now);

        MemoryAwareState state = new TestMemoryState("agg-1", List.of());
        state = state.withMemory(m1);
        state = state.withoutMemory("non-existent");

        assertThat(state.getMemories()).hasSize(1);
        assertThat(state.getMemories().getFirst().memoryId()).isEqualTo("m1");
    }
}
