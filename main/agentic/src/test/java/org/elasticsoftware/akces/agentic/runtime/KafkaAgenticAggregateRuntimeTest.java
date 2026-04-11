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
import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.kafka.KafkaAggregateRuntime;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KafkaAgenticAggregateRuntime}, verifying the delegation pattern,
 * memory extraction from state records, AgentPlatform exposure, and the built-in domain
 * event type constants.
 *
 * <p>Uses Mockito to mock the underlying {@link AggregateRuntime} delegate and the
 * {@link com.embabel.agent.core.AgentPlatform}, avoiding any Kafka or Spring dependencies.
 */
@ExtendWith(MockitoExtension.class)
class KafkaAgenticAggregateRuntimeTest {

    /** Concrete MemoryAwareState for round-trip serialization. */
    record TestMemoryState(
            String id,
            List<AgenticAggregateMemory> memories
    ) implements AggregateState, MemoryAwareState {

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
            return new TestMemoryState(id, List.copyOf(updated));
        }

        @Override
        public MemoryAwareState withoutMemory(String memoryId) {
            var updated = memories.stream()
                    .filter(m -> !m.memoryId().equals(memoryId))
                    .toList();
            return new TestMemoryState(id, updated);
        }
    }

    /** Simple non-MemoryAwareState for testing. */
    record PlainState(String id) implements AggregateState {
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
    private AgenticAggregate<?> aggregate;

    private ObjectMapper objectMapper;
    private KafkaAgenticAggregateRuntime runtime;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        runtime = new KafkaAgenticAggregateRuntime(delegate, objectMapper, TestMemoryState.class, agentPlatform, aggregate, 100, 10);
    }

    // -------------------------------------------------------------------------
    // getMemories() tests
    // -------------------------------------------------------------------------

    @Test
    void getMemoriesShouldReturnEmptyListForNullStateRecord() throws IOException {
        List<AgenticAggregateMemory> memories = runtime.getMemories(null);
        assertThat(memories).isEmpty();
    }

    @Test
    void getMemoriesShouldReturnMemoriesFromMemoryAwareState() throws IOException {
        Instant now = Instant.parse("2026-01-15T10:00:00Z");
        var memory = new AgenticAggregateMemory("mem-1", "testing", "Use JUnit",
                "test.java:1", "standards", now);
        var state = new TestMemoryState("agg-1", List.of(memory));

        byte[] payload = objectMapper.writeValueAsBytes(state);
        AggregateStateRecord record = new AggregateStateRecord(
                "tenant", "TestMemoryState", 1, payload,
                PayloadEncoding.JSON, "agg-1", "corr-1", 1L);
        when(delegate.materializeState(record)).thenReturn(state);

        List<AgenticAggregateMemory> memories = runtime.getMemories(record);

        assertThat(memories).hasSize(1);
        assertThat(memories.getFirst().memoryId()).isEqualTo("mem-1");
        assertThat(memories.getFirst().subject()).isEqualTo("testing");
        assertThat(memories.getFirst().fact()).isEqualTo("Use JUnit");
        assertThat(memories.getFirst().storedAt()).isEqualTo(now);
    }

    @Test
    void getMemoriesShouldReturnEmptyListForNonMemoryAwareState() throws IOException {
        var plainRuntime = new KafkaAgenticAggregateRuntime(delegate, objectMapper, PlainState.class, agentPlatform, aggregate, 100, 10);
        var state = new PlainState("agg-1");

        byte[] payload = objectMapper.writeValueAsBytes(state);
        AggregateStateRecord record = new AggregateStateRecord(
                "tenant", "PlainState", 1, payload,
                PayloadEncoding.JSON, "agg-1", "corr-1", 1L);
        when(delegate.materializeState(record)).thenReturn(state);

        List<AgenticAggregateMemory> memories = runtime.getMemories(record);
        assertThat(memories).isEmpty();
    }

    @Test
    void getMemoriesShouldReturnEmptyListForStateWithNoMemories() throws IOException {
        var state = new TestMemoryState("agg-1", List.of());

        byte[] payload = objectMapper.writeValueAsBytes(state);
        AggregateStateRecord record = new AggregateStateRecord(
                "tenant", "TestMemoryState", 1, payload,
                PayloadEncoding.JSON, "agg-1", "corr-1", 1L);
        when(delegate.materializeState(record)).thenReturn(state);

        List<AgenticAggregateMemory> memories = runtime.getMemories(record);
        assertThat(memories).isEmpty();
    }

    @Test
    void getMemoriesShouldReturnMultipleMemoriesInOrder() throws IOException {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        var m1 = new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", t1);
        var m2 = new AgenticAggregateMemory("m2", "s2", "f2", "c2", "r2", t2);
        var state = new TestMemoryState("agg-1", List.of(m1, m2));

        byte[] payload = objectMapper.writeValueAsBytes(state);
        AggregateStateRecord record = new AggregateStateRecord(
                "tenant", "TestMemoryState", 1, payload,
                PayloadEncoding.JSON, "agg-1", "corr-1", 1L);
        when(delegate.materializeState(record)).thenReturn(state);

        List<AgenticAggregateMemory> memories = runtime.getMemories(record);
        assertThat(memories).hasSize(2);
        assertThat(memories.get(0).memoryId()).isEqualTo("m1");
        assertThat(memories.get(1).memoryId()).isEqualTo("m2");
    }

    // -------------------------------------------------------------------------
    // Delegation tests
    // -------------------------------------------------------------------------

    @Test
    void getNameShouldDelegateToUnderlyingRuntime() {
        when(delegate.getName()).thenReturn("TestAggregate");
        assertThat(runtime.getName()).isEqualTo("TestAggregate");
        verify(delegate).getName();
    }

    @Test
    void shouldHandlePIIDataShouldDelegateToUnderlyingRuntime() {
        when(delegate.shouldHandlePIIData()).thenReturn(false);
        assertThat(runtime.shouldHandlePIIData()).isFalse();
        verify(delegate).shouldHandlePIIData();
    }

    @Test
    void getAllDomainEventTypesShouldDelegateToUnderlyingRuntime() {
        Collection<DomainEventType<?>> types = List.of();
        when(delegate.getAllDomainEventTypes()).thenReturn(types);
        assertThat(runtime.getAllDomainEventTypes()).isSameAs(types);
        verify(delegate).getAllDomainEventTypes();
    }

    @Test
    void getAllCommandTypesShouldDelegateToUnderlyingRuntime() {
        Collection<CommandType<?>> types = List.of();
        when(delegate.getAllCommandTypes()).thenReturn(types);
        assertThat(runtime.getAllCommandTypes()).isSameAs(types);
        verify(delegate).getAllCommandTypes();
    }

    @Test
    void getAgentPlatformShouldReturnInjectedPlatform() {
        assertThat(runtime.getAgentPlatform()).isSameAs(agentPlatform);
    }

    @Test
    void constructorShouldRejectNullDelegate() {
        assertThatNullPointerException()
                .isThrownBy(() -> new KafkaAgenticAggregateRuntime(
                        null, objectMapper, TestMemoryState.class, agentPlatform, aggregate, 100, 10))
                .withMessageContaining("delegate");
    }

    @Test
    void constructorShouldRejectNullObjectMapper() {
        assertThatNullPointerException()
                .isThrownBy(() -> new KafkaAgenticAggregateRuntime(
                        delegate, null, TestMemoryState.class, agentPlatform, aggregate, 100, 10))
                .withMessageContaining("objectMapper");
    }

    @Test
    void constructorShouldRejectNullStateClass() {
        assertThatNullPointerException()
                .isThrownBy(() -> new KafkaAgenticAggregateRuntime(
                        delegate, objectMapper, null, agentPlatform, aggregate, 100, 10))
                .withMessageContaining("stateClass");
    }

    @Test
    void constructorShouldRejectNullAgentPlatform() {
        assertThatNullPointerException()
                .isThrownBy(() -> new KafkaAgenticAggregateRuntime(
                        delegate, objectMapper, TestMemoryState.class, null, aggregate, 100, 10))
                .withMessageContaining("agentPlatform");
    }

    @Test
    void constructorShouldRejectNullAggregate() {
        assertThatNullPointerException()
                .isThrownBy(() -> new KafkaAgenticAggregateRuntime(
                        delegate, objectMapper, TestMemoryState.class, agentPlatform, null, 100, 10))
                .withMessageContaining("aggregate");
    }

    // -------------------------------------------------------------------------
    // Built-in DomainEventType constants
    // -------------------------------------------------------------------------

    @Test
    void memoryStoredTypeShouldBeConfiguredCorrectly() {
        DomainEventType<?> type = AgenticAggregateRuntime.MEMORY_STORED_TYPE;
        assertThat(type.typeName()).isEqualTo("MemoryStored");
        assertThat(type.version()).isEqualTo(1);
    }

    @Test
    void memoryRevokedTypeShouldBeConfiguredCorrectly() {
        DomainEventType<?> type = AgenticAggregateRuntime.MEMORY_REVOKED_TYPE;
        assertThat(type.typeName()).isEqualTo("MemoryRevoked");
        assertThat(type.version()).isEqualTo(1);
    }
}
