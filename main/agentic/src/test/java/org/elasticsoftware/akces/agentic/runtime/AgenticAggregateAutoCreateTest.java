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
import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.aggregate.AgenticAggregate;
import org.elasticsoftware.akces.aggregate.AgenticAggregateMemory;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.IndexParams;
import org.elasticsoftware.akces.aggregate.MemoryAwareState;
import org.elasticsoftware.akces.annotations.AgenticAggregateInfo;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.kafka.KafkaAggregateRuntime;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the auto-create hook on {@link AgenticAggregate}.
 *
 * <p>Demonstrates a complete agentic aggregate implementation with:
 * <ul>
 *   <li>A concrete {@link AggregateState} implementation ({@link TestBotState})</li>
 *   <li>A create {@link DomainEvent} ({@link TestBotCreatedEvent})</li>
 *   <li>An {@link AgenticAggregate} implementation ({@link TestBot}) that provides the
 *       create event via {@link AgenticAggregate#getCreateDomainEvent()}</li>
 *   <li>An {@code @EventSourcingHandler(create = true)} method that creates the state
 *       from the create event</li>
 * </ul>
 *
 * <p>Also verifies that {@link KafkaAgenticAggregateRuntime#initializeState} correctly
 * invokes the aggregate's create hook and delegates to the underlying runtime.
 */
@ExtendWith(MockitoExtension.class)
class AgenticAggregateAutoCreateTest {

    // -------------------------------------------------------------------------
    // Test domain: a simple "TestBot" agentic aggregate
    // -------------------------------------------------------------------------

    /**
     * Create event for the TestBot aggregate.
     */
    @DomainEventInfo(type = "TestBotCreated", version = 1)
    record TestBotCreatedEvent(String botId) implements DomainEvent {
        @Override
        @Nonnull
        public String getAggregateId() {
            return botId;
        }
    }

    /**
     * Aggregate state for the TestBot aggregate.
     */
    @AggregateStateInfo(type = "TestBotState", version = 1)
    record TestBotState(
            String id,
            List<AgenticAggregateMemory> memories
    ) implements AggregateState, MemoryAwareState {

        @Override
        @Nonnull
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
            return new TestBotState(id, List.copyOf(updated));
        }

        @Override
        public MemoryAwareState withoutMemory(String memoryId) {
            var updated = memories.stream()
                    .filter(m -> !m.memoryId().equals(memoryId))
                    .toList();
            return new TestBotState(id, updated);
        }
    }

    /**
     * Complete AgenticAggregate implementation with the auto-create hook.
     *
     * <p>Implements {@link AgenticAggregate#getCreateDomainEvent()} to provide the
     * {@link TestBotCreatedEvent} that triggers initial state creation.
     */
    @AgenticAggregateInfo(value = "TestBot", stateClass = TestBotState.class)
    static class TestBot implements AgenticAggregate<TestBotState> {

        @Override
        public Class<TestBotState> getStateClass() {
            return TestBotState.class;
        }

        @Override
        public DomainEvent getCreateDomainEvent() {
            return new TestBotCreatedEvent(getName());
        }

        @EventSourcingHandler(create = true)
        public TestBotState create(TestBotCreatedEvent event, TestBotState isNull) {
            return new TestBotState(event.botId(), List.of());
        }
    }

    @Mock
    private KafkaAggregateRuntime delegate;

    @Mock
    private AgentPlatform agentPlatform;

    // -------------------------------------------------------------------------
    // getCreateDomainEvent() tests
    // -------------------------------------------------------------------------

    @Test
    void getCreateDomainEventShouldReturnCorrectEvent() {
        TestBot testBot = new TestBot();
        DomainEvent createEvent = testBot.getCreateDomainEvent();

        assertThat(createEvent).isNotNull();
        assertThat(createEvent).isInstanceOf(TestBotCreatedEvent.class);
        TestBotCreatedEvent botCreated = (TestBotCreatedEvent) createEvent;
        assertThat(botCreated.botId()).isEqualTo("TestBot");
        assertThat(botCreated.getAggregateId()).isEqualTo("TestBot");
    }

    @Test
    void getCreateDomainEventShouldUseAggregateNameAsId() {
        TestBot testBot = new TestBot();
        // The default getName() returns the simple class name
        assertThat(testBot.getName()).isEqualTo("TestBot");

        DomainEvent createEvent = testBot.getCreateDomainEvent();
        assertThat(createEvent.getAggregateId()).isEqualTo(testBot.getName());
    }

    // -------------------------------------------------------------------------
    // EventSourcingHandler(create = true) tests
    // -------------------------------------------------------------------------

    @Test
    void createHandlerShouldCreateInitialState() {
        TestBot testBot = new TestBot();
        TestBotCreatedEvent event = new TestBotCreatedEvent("TestBot");

        TestBotState state = testBot.create(event, null);

        assertThat(state).isNotNull();
        assertThat(state.getAggregateId()).isEqualTo("TestBot");
        assertThat(state.getMemories()).isEmpty();
    }

    @Test
    void createHandlerShouldUseEventAggregateId() {
        TestBot testBot = new TestBot();
        TestBotCreatedEvent event = new TestBotCreatedEvent("CustomBot");

        TestBotState state = testBot.create(event, null);

        assertThat(state.getAggregateId()).isEqualTo("CustomBot");
    }

    // -------------------------------------------------------------------------
    // Full auto-create flow: getCreateDomainEvent → create handler → state
    // -------------------------------------------------------------------------

    @Test
    void fullAutoCreateFlowShouldProduceCorrectState() {
        TestBot testBot = new TestBot();

        // Step 1: Framework calls getCreateDomainEvent()
        DomainEvent createEvent = testBot.getCreateDomainEvent();
        assertThat(createEvent).isInstanceOf(TestBotCreatedEvent.class);

        // Step 2: Framework applies the event-sourcing create handler
        TestBotState state = testBot.create((TestBotCreatedEvent) createEvent, null);

        // Step 3: Verify the resulting state
        assertThat(state).isNotNull();
        assertThat(state.getAggregateId()).isEqualTo("TestBot");
        assertThat(state.getMemories()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // initializeState() tests on KafkaAgenticAggregateRuntime
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void initializeStateShouldCallGetCreateDomainEventAndDelegateToRuntime() throws IOException {
        TestBot testBot = new TestBot();
        ObjectMapper objectMapper = JsonMapper.builder().build();

        KafkaAgenticAggregateRuntime runtime = new KafkaAgenticAggregateRuntime(
                delegate, objectMapper, TestBotState.class, agentPlatform, testBot, 100);

        when(delegate.getName()).thenReturn("TestBot");

        // Capture the create event passed to the delegate
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);

        runtime.initializeState(pr -> {}, (der, ip) -> {});

        verify(delegate).handleAutoCreateDomainEvent(
                eventCaptor.capture(),
                any(Consumer.class),
                any(BiConsumer.class));

        DomainEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent).isInstanceOf(TestBotCreatedEvent.class);
        assertThat(capturedEvent.getAggregateId()).isEqualTo("TestBot");
    }

    @Test
    void initializeStateShouldRejectNullCreateEvent() {
        AgenticAggregate<?> nullReturningAggregate = new AgenticAggregate<>() {
            @Override
            public DomainEvent getCreateDomainEvent() {
                return null;
            }

            @Override
            public Class getStateClass() {
                return TestBotState.class;
            }
        };

        ObjectMapper objectMapper = JsonMapper.builder().build();
        KafkaAgenticAggregateRuntime runtime = new KafkaAgenticAggregateRuntime(
                delegate, objectMapper, TestBotState.class, agentPlatform, nullReturningAggregate, 100);

        assertThatNullPointerException()
                .isThrownBy(() -> runtime.initializeState(pr -> {}, (der, ip) -> {}))
                .withMessageContaining("getCreateDomainEvent()");
    }

    @Test
    @SuppressWarnings("unchecked")
    void initializeStateShouldPassConsumersThroughToDelegate() throws IOException {
        TestBot testBot = new TestBot();
        ObjectMapper objectMapper = JsonMapper.builder().build();

        KafkaAgenticAggregateRuntime runtime = new KafkaAgenticAggregateRuntime(
                delegate, objectMapper, TestBotState.class, agentPlatform, testBot, 100);

        when(delegate.getName()).thenReturn("TestBot");

        Consumer<ProtocolRecord> protocolConsumer = pr -> {};
        BiConsumer<DomainEventRecord, IndexParams> indexer = (der, ip) -> {};

        runtime.initializeState(protocolConsumer, indexer);

        // Verify that the exact same consumers were passed through to the delegate
        verify(delegate).handleAutoCreateDomainEvent(
                any(DomainEvent.class),
                eq(protocolConsumer),
                eq(indexer));
    }
}
