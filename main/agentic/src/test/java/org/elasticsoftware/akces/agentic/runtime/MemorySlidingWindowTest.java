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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the sliding-window memory eviction logic in the state transition layer.
 * The eviction algorithm is exercised by simulating the sequence of events that are
 * produced by the Embabel layer during the agent's memory management process.
 *
 * <p>Memory revocation is handled by the Embabel agent (via its memory management tools),
 * which produces {@link MemoryRevokedEvent} directly. Each such event updates the
 * state through the built-in event-sourcing handler in
 * {@link KafkaAgenticAggregateRuntime}.
 */
class MemorySlidingWindowTest {

    /** Maximum memories in the sliding window (mirrors default of {@code maxMemories = 100}). */
    private static final int MAX_MEMORIES = 100;

    /** Concrete {@link MemoryAwareState} implementation for testing. */
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

    /**
     * Simulates the enforceMemorySlidingWindow logic: when memory count exceeds
     * maxMemories, evict oldest entries by applying MemoryRevokedEvent until within bounds.
     */
    private static AggregateState enforceWindow(AggregateState state, int maxMemories) {
        if (state instanceof MemoryAwareState mas) {
            while (mas.getMemories().size() > maxMemories) {
                AgenticAggregateMemory oldest = mas.getMemories().getFirst();
                MemoryRevokedEvent revokeEvent = new MemoryRevokedEvent(
                        ((AggregateState) mas).getAggregateId(),
                        oldest.memoryId(),
                        "sliding window eviction",
                        Instant.now());
                state = KafkaAgenticAggregateRuntime.onMemoryRevoked(revokeEvent, (AggregateState) mas);
                mas = (MemoryAwareState) state;
            }
        }
        return state;
    }

    /** Helper to store N memories and return the resulting state. */
    private static AggregateState storeMemories(int count) {
        AggregateState state = new TestMemoryState("agg-1", List.of());
        for (int i = 1; i <= count; i++) {
            MemoryStoredEvent event = new MemoryStoredEvent(
                    "agg-1", "m" + i, "s" + i, "fact-" + i,
                    "cite-" + i, "reason-" + i,
                    Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i));
            state = KafkaAgenticAggregateRuntime.onMemoryStored(event, state);
        }
        return state;
    }

    // -------------------------------------------------------------------------
    // Boundary condition: 0 memories
    // -------------------------------------------------------------------------

    @Test
    void emptyStateShouldNotTriggerEviction() {
        AggregateState state = new TestMemoryState("agg-1", List.of());
        AggregateState result = enforceWindow(state, MAX_MEMORIES);

        assertThat(((TestMemoryState) result).getMemories()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Below limit: no eviction
    // -------------------------------------------------------------------------

    @Test
    void belowLimitShouldNotTriggerEviction() {
        AggregateState state = storeMemories(50);
        AggregateState result = enforceWindow(state, MAX_MEMORIES);

        assertThat(((TestMemoryState) result).getMemories()).hasSize(50);
    }

    // -------------------------------------------------------------------------
    // At exactly the limit: no eviction
    // -------------------------------------------------------------------------

    @Test
    void exactlyAtLimitShouldNotTriggerEviction() {
        AggregateState state = storeMemories(MAX_MEMORIES);
        AggregateState result = enforceWindow(state, MAX_MEMORIES);

        assertThat(((TestMemoryState) result).getMemories()).hasSize(MAX_MEMORIES);
    }

    // -------------------------------------------------------------------------
    // One over limit: oldest evicted
    // -------------------------------------------------------------------------

    @Test
    void oneOverLimitShouldEvictOldestMemory() {
        AggregateState state = storeMemories(MAX_MEMORIES + 1);
        AggregateState result = enforceWindow(state, MAX_MEMORIES);

        var memories = ((TestMemoryState) result).getMemories();
        assertThat(memories).hasSize(MAX_MEMORIES);
        // The oldest memory (m1) should have been evicted
        assertThat(memories.getFirst().memoryId()).isEqualTo("m2");
        // The newest should still be present
        assertThat(memories.getLast().memoryId()).isEqualTo("m" + (MAX_MEMORIES + 1));
    }

    // -------------------------------------------------------------------------
    // Multiple over limit: multiple oldest evicted
    // -------------------------------------------------------------------------

    @Test
    void multipleOverLimitShouldEvictMultipleOldestMemories() {
        int overCount = 5;
        AggregateState state = storeMemories(MAX_MEMORIES + overCount);
        AggregateState result = enforceWindow(state, MAX_MEMORIES);

        var memories = ((TestMemoryState) result).getMemories();
        assertThat(memories).hasSize(MAX_MEMORIES);
        // First 5 memories (m1-m5) should have been evicted
        assertThat(memories.getFirst().memoryId()).isEqualTo("m" + (overCount + 1));
        assertThat(memories.getLast().memoryId()).isEqualTo("m" + (MAX_MEMORIES + overCount));
    }

    // -------------------------------------------------------------------------
    // Revoke memory by ID removes specific memory
    // -------------------------------------------------------------------------

    @Test
    void forgetMemoryByIdShouldRemoveSpecificMemory() {
        AggregateState state = storeMemories(5);
        // Simulate revoking memory m3 via MemoryRevokedEvent (produced by Embabel layer)
        MemoryRevokedEvent revokeEvent = new MemoryRevokedEvent(
                "agg-1", "m3", "no longer relevant", Instant.now());
        state = KafkaAgenticAggregateRuntime.onMemoryRevoked(revokeEvent, state);

        var memories = ((TestMemoryState) state).getMemories();
        assertThat(memories).hasSize(4);
        assertThat(memories).extracting(AgenticAggregateMemory::memoryId)
                .containsExactly("m1", "m2", "m4", "m5");
    }

    @Test
    void forgetNonExistentMemoryIdShouldNotChangeState() {
        AggregateState state = storeMemories(3);
        MemoryRevokedEvent revokeEvent = new MemoryRevokedEvent(
                "agg-1", "non-existent", "cleanup", Instant.now());
        AggregateState result = KafkaAgenticAggregateRuntime.onMemoryRevoked(revokeEvent, state);

        assertThat(((TestMemoryState) result).getMemories()).hasSize(3);
    }

    // -------------------------------------------------------------------------
    // Eviction ordering: oldest memory is evicted first
    // -------------------------------------------------------------------------

    @Test
    void evictionShouldRemoveOldestFirst() {
        // Store memories with specific timestamps to verify FIFO eviction
        AggregateState state = new TestMemoryState("agg-1", List.of());
        Instant baseTime = Instant.parse("2026-01-01T00:00:00Z");

        for (int i = 0; i < MAX_MEMORIES + 3; i++) {
            MemoryStoredEvent event = new MemoryStoredEvent(
                    "agg-1", "mem-" + i, "s", "f", "c", "r",
                    baseTime.plusSeconds(i));
            state = KafkaAgenticAggregateRuntime.onMemoryStored(event, state);
        }

        state = enforceWindow(state, MAX_MEMORIES);
        var memories = ((TestMemoryState) state).getMemories();

        // The three oldest (mem-0, mem-1, mem-2) should be evicted
        assertThat(memories).hasSize(MAX_MEMORIES);
        assertThat(memories.getFirst().memoryId()).isEqualTo("mem-3");
        assertThat(memories.getFirst().storedAt()).isEqualTo(baseTime.plusSeconds(3));
    }

    // -------------------------------------------------------------------------
    // Small limit: verify with maxMemories = 3
    // -------------------------------------------------------------------------

    @Test
    void smallWindowSizeShouldWorkCorrectly() {
        int smallLimit = 3;
        AggregateState state = storeMemories(5);
        AggregateState result = enforceWindow(state, smallLimit);

        var memories = ((TestMemoryState) result).getMemories();
        assertThat(memories).hasSize(smallLimit);
        // m1 and m2 should be evicted, m3-m5 should remain
        assertThat(memories).extracting(AgenticAggregateMemory::memoryId)
                .containsExactly("m3", "m4", "m5");
    }

    // -------------------------------------------------------------------------
    // Comprehensive sequence: store, evict, store again
    // -------------------------------------------------------------------------

    @Test
    void storeEvictAndStoreAgainShouldMaintainWindow() {
        int limit = 3;
        AggregateState state = new TestMemoryState("agg-1", List.of());

        // Store 3 memories (at limit)
        for (int i = 1; i <= 3; i++) {
            state = KafkaAgenticAggregateRuntime.onMemoryStored(
                    new MemoryStoredEvent("agg-1", "m" + i, "s", "f", "c", "r",
                            Instant.now().plusSeconds(i)), state);
        }
        assertThat(((TestMemoryState) state).getMemories()).hasSize(3);

        // Store 4th memory (1 over limit)
        state = KafkaAgenticAggregateRuntime.onMemoryStored(
                new MemoryStoredEvent("agg-1", "m4", "s", "f", "c", "r",
                        Instant.now().plusSeconds(4)), state);

        // Enforce window -> evicts m1
        state = enforceWindow(state, limit);
        var memories = ((TestMemoryState) state).getMemories();
        assertThat(memories).hasSize(3);
        assertThat(memories).extracting(AgenticAggregateMemory::memoryId)
                .containsExactly("m2", "m3", "m4");

        // Store 5th memory (1 over limit again)
        state = KafkaAgenticAggregateRuntime.onMemoryStored(
                new MemoryStoredEvent("agg-1", "m5", "s", "f", "c", "r",
                        Instant.now().plusSeconds(5)), state);

        state = enforceWindow(state, limit);
        memories = ((TestMemoryState) state).getMemories();
        assertThat(memories).hasSize(3);
        assertThat(memories).extracting(AgenticAggregateMemory::memoryId)
                .containsExactly("m3", "m4", "m5");
    }
}
