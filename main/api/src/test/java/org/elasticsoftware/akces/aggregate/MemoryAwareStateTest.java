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

import org.elasticsoftware.akces.events.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link MemoryAwareState} interface contract and the
 * {@link AgenticAggregate#getMemories(AggregateState)} default method. Per framework testing
 * guidelines, interfaces are tested through concrete implementations rather than directly.
 */
class MemoryAwareStateTest {

    /** Concrete MemoryAwareState implementation for testing. */
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

    /** Simple non-MemoryAwareState state class. */
    record PlainState(String id) implements AggregateState {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    /** Test AgenticAggregate that uses MemoryAwareState. */
    static class MemoryAggregate implements AgenticAggregate<TestMemoryState> {
        @Override
        public Class<TestMemoryState> getStateClass() {
            return TestMemoryState.class;
        }

        @Override
        public DomainEvent getCreateDomainEvent() {
            return new DomainEvent() {
                @Override
                public String getAggregateId() {
                    return getName();
                }
            };
        }
    }

    /** Test AgenticAggregate that uses a plain (non-MemoryAwareState) state. */
    static class PlainAggregate implements AgenticAggregate<PlainState> {
        @Override
        public Class<PlainState> getStateClass() {
            return PlainState.class;
        }

        @Override
        public DomainEvent getCreateDomainEvent() {
            return new DomainEvent() {
                @Override
                public String getAggregateId() {
                    return getName();
                }
            };
        }
    }

    // -------------------------------------------------------------------------
    // MemoryAwareState contract tests
    // -------------------------------------------------------------------------

    @Test
    void getMemoriesShouldReturnCorrectList() {
        Instant now = Instant.now();
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", now);
        var m2 = new AgenticAggregateMemory("m2", "s2", "f2", "c2", "r2", now);

        var state = new TestMemoryState("agg-1", List.of(m1, m2));

        assertThat(state.getMemories()).hasSize(2);
        assertThat(state.getMemories()).containsExactly(m1, m2);
    }

    @Test
    void emptyStateShouldReturnEmptyMemoryList() {
        var state = new TestMemoryState("agg-1", List.of());

        assertThat(state.getMemories()).isEmpty();
    }

    @Test
    void withMemoryShouldAppendToEnd() {
        Instant now = Instant.now();
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", now);
        var m2 = new AgenticAggregateMemory("m2", "s2", "f2", "c2", "r2", now);

        MemoryAwareState state = new TestMemoryState("agg-1", List.of());
        state = state.withMemory(m1);
        state = state.withMemory(m2);

        assertThat(state.getMemories()).hasSize(2);
        assertThat(state.getMemories().get(0).memoryId()).isEqualTo("m1");
        assertThat(state.getMemories().get(1).memoryId()).isEqualTo("m2");
    }

    @Test
    void withoutMemoryShouldRemoveByMemoryId() {
        Instant now = Instant.now();
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", now);
        var m2 = new AgenticAggregateMemory("m2", "s2", "f2", "c2", "r2", now);
        var m3 = new AgenticAggregateMemory("m3", "s3", "f3", "c3", "r3", now);

        MemoryAwareState state = new TestMemoryState("agg-1", List.of(m1, m2, m3));
        state = state.withoutMemory("m2");

        assertThat(state.getMemories()).hasSize(2);
        assertThat(state.getMemories()).extracting(AgenticAggregateMemory::memoryId)
                .containsExactly("m1", "m3");
    }

    @Test
    void withoutNonExistentMemoryIdShouldReturnEquivalentState() {
        Instant now = Instant.now();
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", now);

        MemoryAwareState state = new TestMemoryState("agg-1", List.of(m1));
        MemoryAwareState unchanged = state.withoutMemory("non-existent");

        assertThat(unchanged.getMemories()).isEqualTo(state.getMemories());
    }

    // -------------------------------------------------------------------------
    // AgenticAggregate.getMemories(S state) default method tests
    // -------------------------------------------------------------------------

    @Test
    void getMemoriesDefaultMethodShouldReturnMemoriesForMemoryAwareState() {
        Instant now = Instant.now();
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", now);
        var m2 = new AgenticAggregateMemory("m2", "s2", "f2", "c2", "r2", now);

        var state = new TestMemoryState("agg-1", List.of(m1, m2));
        var aggregate = new MemoryAggregate();

        List<AgenticAggregateMemory> memories = aggregate.getMemories(state);

        assertThat(memories).hasSize(2);
        assertThat(memories).containsExactly(m1, m2);
    }

    @Test
    void getMemoriesDefaultMethodShouldReturnEmptyForNonMemoryAwareState() {
        var state = new PlainState("agg-1");
        var aggregate = new PlainAggregate();

        List<AgenticAggregateMemory> memories = aggregate.getMemories(state);

        assertThat(memories).isEmpty();
    }

    @Test
    void getMemoriesDefaultMethodShouldReturnEmptyForEmptyMemoryAwareState() {
        var state = new TestMemoryState("agg-1", List.of());
        var aggregate = new MemoryAggregate();

        List<AgenticAggregateMemory> memories = aggregate.getMemories(state);

        assertThat(memories).isEmpty();
    }
}
