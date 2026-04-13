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

import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.elasticsoftware.akces.aggregate.AgenticAggregateMemory;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.MemoryAwareState;
import org.elasticsoftware.akces.aggregate.MemoryDistillation;
import org.elasticsoftware.akces.events.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the built-in event-sourcing handlers in {@link KafkaAgenticAggregateRuntime}
 * that reconstruct aggregate state from {@link MemoryStoredEvent} and
 * {@link MemoryRevokedEvent} sequences.
 *
 * <p>Per framework testing guidelines, the event-sourcing handler methods
 * ({@code onMemoryStored}, {@code onMemoryRevoked}, {@code handleBuiltInEvent}) are
 * tested through their actual invocations rather than testing the event records directly.
 */
class MemoryEventSourcingTest {

    /** Concrete {@link MemoryAwareState} implementation for test assertions. */
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

    /** A non-MemoryAwareState state for testing error paths. */
    record PlainState(String id) implements AggregateState {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    // -------------------------------------------------------------------------
    // onMemoryStored tests
    // -------------------------------------------------------------------------

    @Test
    void onMemoryStoredShouldAppendMemoryToState() {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        var event = new MemoryStoredEvent("agg-1", "mem-1", "testing", "Use JUnit 5",
                "build.gradle:10", "consistency", now);
        var initialState = new TestMemoryState("agg-1", List.of());

        AggregateState result = KafkaAgenticAggregateRuntime.onMemoryStored(event, initialState);

        assertThat(result).isInstanceOf(TestMemoryState.class);
        var state = (TestMemoryState) result;
        assertThat(state.getMemories()).hasSize(1);
        AgenticAggregateMemory stored = state.getMemories().getFirst();
        assertThat(stored.memoryId()).isEqualTo("mem-1");
        assertThat(stored.subject()).isEqualTo("testing");
        assertThat(stored.fact()).isEqualTo("Use JUnit 5");
        assertThat(stored.citations()).isEqualTo("build.gradle:10");
        assertThat(stored.reason()).isEqualTo("consistency");
        assertThat(stored.storedAt()).isEqualTo(now);
    }

    @Test
    void onMemoryStoredShouldThrowWhenStateIsNotMemoryAware() {
        var event = new MemoryStoredEvent("agg-1", "mem-1", "s", "f", "c", "r", Instant.now());
        var plainState = new PlainState("agg-1");

        assertThatThrownBy(() -> KafkaAgenticAggregateRuntime.onMemoryStored(event, plainState))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not implement MemoryAwareState");
    }

    // -------------------------------------------------------------------------
    // onMemoryRevoked tests
    // -------------------------------------------------------------------------

    @Test
    void onMemoryRevokedShouldRemoveMemoryById() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", t1);
        var m2 = new AgenticAggregateMemory("m2", "s2", "f2", "c2", "r2", t2);
        var state = new TestMemoryState("agg-1", List.of(m1, m2));

        var revokeEvent = new MemoryRevokedEvent("agg-1", "m1", "no longer needed", Instant.now());
        AggregateState result = KafkaAgenticAggregateRuntime.onMemoryRevoked(revokeEvent, state);

        assertThat(result).isInstanceOf(TestMemoryState.class);
        var newState = (TestMemoryState) result;
        assertThat(newState.getMemories()).hasSize(1);
        assertThat(newState.getMemories().getFirst().memoryId()).isEqualTo("m2");
    }

    @Test
    void onMemoryRevokedShouldThrowWhenStateIsNotMemoryAware() {
        var event = new MemoryRevokedEvent("agg-1", "mem-1", "reason", Instant.now());
        var plainState = new PlainState("agg-1");

        assertThatThrownBy(() -> KafkaAgenticAggregateRuntime.onMemoryRevoked(event, plainState))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not implement MemoryAwareState");
    }

    // -------------------------------------------------------------------------
    // handleMemoryEvent dispatch tests (via handleBuiltInEvent)
    // -------------------------------------------------------------------------

    @Test
    void handleBuiltInEventShouldDispatchMemoryStoredEvent() {
        Instant now = Instant.now();
        var event = new MemoryStoredEvent("agg-1", "mem-1", "sub", "fact", "cite", "reason", now);
        var state = new TestMemoryState("agg-1", List.of());

        AggregateState result = KafkaAgenticAggregateRuntime.handleBuiltInEvent(event, state);

        assertThat(result).isInstanceOf(TestMemoryState.class);
        assertThat(((TestMemoryState) result).getMemories()).hasSize(1);
    }

    @Test
    void handleBuiltInEventShouldDispatchMemoryRevokedEvent() {
        var m1 = new AgenticAggregateMemory("m1", "s", "f", "c", "r", Instant.now());
        var state = new TestMemoryState("agg-1", List.of(m1));

        var event = new MemoryRevokedEvent("agg-1", "m1", "evicted", Instant.now());
        AggregateState result = KafkaAgenticAggregateRuntime.handleBuiltInEvent(event, state);

        assertThat(result).isInstanceOf(TestMemoryState.class);
        assertThat(((TestMemoryState) result).getMemories()).isEmpty();
    }

    @Test
    void handleBuiltInEventShouldThrowForUnknownEventType() {
        var unknownEvent = new DomainEvent() {
            @Override
            public String getAggregateId() {
                return "agg-1";
            }
        };
        var state = new TestMemoryState("agg-1", List.of());

        assertThatThrownBy(() -> KafkaAgenticAggregateRuntime.handleBuiltInEvent(unknownEvent, state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported built-in event type");
    }

    // -------------------------------------------------------------------------
    // State reconstruction from event sequences
    // -------------------------------------------------------------------------

    @Test
    void shouldReconstructStateFromEventSequence() {
        AggregateState state = new TestMemoryState("agg-1", List.of());

        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        Instant t3 = Instant.parse("2026-01-03T00:00:00Z");

        // Store three memories
        List<DomainEvent> events = List.of(
                new MemoryStoredEvent("agg-1", "m1", "auth", "Use JWT", "auth.java:5", "security", t1),
                new MemoryStoredEvent("agg-1", "m2", "logging", "Use Winston", "log.js:1", "consistency", t2),
                new MemoryStoredEvent("agg-1", "m3", "testing", "Use JUnit", "test.java:1", "standards", t3),
                // Revoke m1
                new MemoryRevokedEvent("agg-1", "m1", "obsolete", Instant.now())
        );

        // Replay events to reconstruct state
        for (DomainEvent event : events) {
            state = KafkaAgenticAggregateRuntime.handleBuiltInEvent(event, state);
        }

        var finalState = (TestMemoryState) state;
        assertThat(finalState.getMemories()).hasSize(2);
        assertThat(finalState.getMemories()).extracting(AgenticAggregateMemory::memoryId)
                .containsExactly("m2", "m3");
    }

    @Test
    void shouldHandleStoreAndRevokeOfSameMemoryInSequence() {
        AggregateState state = new TestMemoryState("agg-1", List.of());

        // Store then immediately revoke
        state = KafkaAgenticAggregateRuntime.handleBuiltInEvent(
                new MemoryStoredEvent("agg-1", "m1", "s", "f", "c", "r", Instant.now()), state);
        state = KafkaAgenticAggregateRuntime.handleBuiltInEvent(
                new MemoryRevokedEvent("agg-1", "m1", "undo", Instant.now()), state);

        assertThat(((TestMemoryState) state).getMemories()).isEmpty();
    }

    @Test
    void shouldPreserveInsertionOrderAfterMultipleStoredEvents() {
        AggregateState state = new TestMemoryState("agg-1", List.of());

        for (int i = 1; i <= 5; i++) {
            state = KafkaAgenticAggregateRuntime.handleBuiltInEvent(
                    new MemoryStoredEvent("agg-1", "m" + i, "s" + i, "f" + i,
                            "c" + i, "r" + i, Instant.now().plusSeconds(i)),
                    state);
        }

        var finalState = (TestMemoryState) state;
        assertThat(finalState.getMemories()).hasSize(5);
        assertThat(finalState.getMemories()).extracting(AgenticAggregateMemory::memoryId)
                .containsExactly("m1", "m2", "m3", "m4", "m5");
    }
}
