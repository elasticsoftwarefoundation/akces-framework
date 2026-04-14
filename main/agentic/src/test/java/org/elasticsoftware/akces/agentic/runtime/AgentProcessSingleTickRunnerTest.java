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

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AgentProcessSingleTickRunner}.
 */
@ExtendWith(MockitoExtension.class)
class AgentProcessSingleTickRunnerTest {

    /** Simple test domain event. */
    record TestEvent(String id) implements DomainEvent {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    /** Simple test error event. */
    record TestErrorEvent(String id) implements ErrorEvent {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    @Mock
    private AgentProcess agentProcess;

    @Mock
    private Blackboard blackboard;

    @Test
    void tickShouldCallTickAndCollectEvents() {
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        TestEvent event = new TestEvent("agg-1");
        when(blackboard.getObjects()).thenReturn(List.of(event));
        // The cursor is lazily created on first call
        when(blackboard.get(AgentProcessResultTranslator.PROCESSED_INDEX_KEY)).thenReturn(null);

        Collection<DomainEventType<?>> registeredTypes = List.of(
                new DomainEventType<>("TestEvent", 1, TestEvent.class, false, false, false, false));

        Stream<DomainEvent> result = AgentProcessSingleTickRunner.tick(agentProcess, registeredTypes);
        List<DomainEvent> events = result.toList();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isEqualTo(event);

        verify(agentProcess).tick();
        verify(agentProcess).getBlackboard();
        // Events are tracked via an index cursor stored on the blackboard, not hidden
        verify(blackboard).set(eq(AgentProcessResultTranslator.PROCESSED_INDEX_KEY), any());
        verify(blackboard, never()).hide(any());
    }

    @Test
    void tickShouldReturnEmptyStreamWhenNoEventsProduced() {
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        when(blackboard.getObjects()).thenReturn(List.of());
        when(blackboard.get(AgentProcessResultTranslator.PROCESSED_INDEX_KEY)).thenReturn(null);

        Stream<DomainEvent> result = AgentProcessSingleTickRunner.tick(agentProcess, List.of());
        List<DomainEvent> events = result.toList();

        assertThat(events).isEmpty();
        verify(agentProcess).tick();
    }

    @Test
    void tickShouldFilterUnregisteredErrorEvents() {
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        TestEvent normalEvent = new TestEvent("agg-1");
        TestErrorEvent errorEvent = new TestErrorEvent("agg-1");
        when(blackboard.getObjects()).thenReturn(List.of(normalEvent, errorEvent));
        when(blackboard.get(AgentProcessResultTranslator.PROCESSED_INDEX_KEY)).thenReturn(null);

        // Only register the normal event type, not the error event type
        Collection<DomainEventType<?>> registeredTypes = List.of(
                new DomainEventType<>("TestEvent", 1, TestEvent.class, false, false, false, false));

        Stream<DomainEvent> result = AgentProcessSingleTickRunner.tick(agentProcess, registeredTypes);
        List<DomainEvent> events = result.toList();

        // Only the normal event should be included; error event is unregistered
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isEqualTo(normalEvent);
    }

    @Test
    void tickShouldIncludeRegisteredErrorEvents() {
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        TestErrorEvent errorEvent = new TestErrorEvent("agg-1");
        when(blackboard.getObjects()).thenReturn(List.of(errorEvent));
        when(blackboard.get(AgentProcessResultTranslator.PROCESSED_INDEX_KEY)).thenReturn(null);

        // Register the error event type
        Collection<DomainEventType<?>> registeredTypes = List.of(
                new DomainEventType<>("TestErrorEvent", 1, TestErrorEvent.class, false, false, true, false));

        Stream<DomainEvent> result = AgentProcessSingleTickRunner.tick(agentProcess, registeredTypes);
        List<DomainEvent> events = result.toList();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isEqualTo(errorEvent);
    }

    @Test
    void tickShouldCollectMultipleEvents() {
        when(agentProcess.getBlackboard()).thenReturn(blackboard);
        TestEvent event1 = new TestEvent("agg-1");
        TestEvent event2 = new TestEvent("agg-2");
        when(blackboard.getObjects()).thenReturn(List.of(event1, event2));
        when(blackboard.get(AgentProcessResultTranslator.PROCESSED_INDEX_KEY)).thenReturn(null);

        Collection<DomainEventType<?>> registeredTypes = List.of(
                new DomainEventType<>("TestEvent", 1, TestEvent.class, false, false, false, false));

        Stream<DomainEvent> result = AgentProcessSingleTickRunner.tick(agentProcess, registeredTypes);
        List<DomainEvent> events = result.toList();

        assertThat(events).hasSize(2);
        assertThat(events).containsExactly(event1, event2);
    }
}
