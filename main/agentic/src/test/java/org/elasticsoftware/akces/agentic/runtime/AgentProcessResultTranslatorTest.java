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

import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.support.InMemoryBlackboard;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentProcessResultTranslator}, verifying event collection,
 * blackboard draining, and handling of registered vs. unregistered error events.
 */
class AgentProcessResultTranslatorTest {

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    @DomainEventInfo(type = "TestStateChanged", version = 1)
    record TestStateChangedEvent(@AggregateIdentifier String id) implements DomainEvent {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    @DomainEventInfo(type = "TestRegisteredError", version = 1)
    record TestRegisteredErrorEvent(@AggregateIdentifier String id) implements ErrorEvent {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    @DomainEventInfo(type = "TestUnregisteredError", version = 1)
    record TestUnregisteredErrorEvent(@AggregateIdentifier String id) implements ErrorEvent {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    private static final DomainEventType<TestStateChangedEvent> STATE_CHANGED_TYPE =
            new DomainEventType<>("TestStateChanged", 1, TestStateChangedEvent.class,
                    false, false, false, false);

    private static final DomainEventType<TestRegisteredErrorEvent> REGISTERED_ERROR_TYPE =
            new DomainEventType<>("TestRegisteredError", 1, TestRegisteredErrorEvent.class,
                    false, false, true, false);

    private Blackboard blackboard;

    @BeforeEach
    void setUp() {
        blackboard = new InMemoryBlackboard();
    }

    // -------------------------------------------------------------------------
    // Empty blackboard
    // -------------------------------------------------------------------------

    @Test
    void collectEventsShouldReturnEmptyListForEmptyBlackboard() {
        List<DomainEvent> events = AgentProcessResultTranslator.collectEvents(blackboard, List.of());
        assertThat(events).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Non-error domain events
    // -------------------------------------------------------------------------

    @Test
    void collectEventsShouldIncludeRegisteredStateChangingEvent() {
        TestStateChangedEvent event = new TestStateChangedEvent("agg-1");
        blackboard.addObject(event);

        List<DomainEvent> events = AgentProcessResultTranslator.collectEvents(
                blackboard, List.of(STATE_CHANGED_TYPE));

        assertThat(events).containsExactly(event);
    }

    @Test
    void collectEventsShouldHideEventsAfterCollection() {
        TestStateChangedEvent event = new TestStateChangedEvent("agg-1");
        blackboard.addObject(event);

        AgentProcessResultTranslator.collectEvents(blackboard, List.of(STATE_CHANGED_TYPE));

        // Second call should return nothing — events were hidden on first call
        List<DomainEvent> second = AgentProcessResultTranslator.collectEvents(
                blackboard, List.of(STATE_CHANGED_TYPE));
        assertThat(second).isEmpty();
    }

    @Test
    void collectEventsShouldNotIncludeNonDomainEventObjects() {
        blackboard.addObject("not an event");
        blackboard.addObject(42);
        blackboard.addObject(new TestStateChangedEvent("agg-1"));

        List<DomainEvent> events = AgentProcessResultTranslator.collectEvents(
                blackboard, List.of(STATE_CHANGED_TYPE));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(TestStateChangedEvent.class);
    }

    // -------------------------------------------------------------------------
    // Registered error events — must be included
    // -------------------------------------------------------------------------

    @Test
    void collectEventsShouldIncludeRegisteredErrorEvent() {
        TestRegisteredErrorEvent error = new TestRegisteredErrorEvent("agg-1");
        blackboard.addObject(error);

        List<DomainEvent> events = AgentProcessResultTranslator.collectEvents(
                blackboard, List.of(REGISTERED_ERROR_TYPE));

        assertThat(events).containsExactly(error);
    }

    // -------------------------------------------------------------------------
    // Unregistered error events — must be excluded with warning
    // -------------------------------------------------------------------------

    @Test
    void collectEventsShouldExcludeUnregisteredErrorEvent() {
        TestUnregisteredErrorEvent unknownError = new TestUnregisteredErrorEvent("agg-1");
        blackboard.addObject(unknownError);

        // Only STATE_CHANGED_TYPE and REGISTERED_ERROR_TYPE are registered — not the unknown one
        List<DomainEvent> events = AgentProcessResultTranslator.collectEvents(
                blackboard, List.of(STATE_CHANGED_TYPE, REGISTERED_ERROR_TYPE));

        assertThat(events).isEmpty();
    }

    @Test
    void collectEventsShouldExcludeUnregisteredErrorAndKeepRegisteredEvents() {
        TestStateChangedEvent stateEvent = new TestStateChangedEvent("agg-1");
        TestRegisteredErrorEvent registeredError = new TestRegisteredErrorEvent("agg-1");
        TestUnregisteredErrorEvent unknownError = new TestUnregisteredErrorEvent("agg-1");
        blackboard.addObject(stateEvent);
        blackboard.addObject(registeredError);
        blackboard.addObject(unknownError);

        List<DomainEvent> events = AgentProcessResultTranslator.collectEvents(
                blackboard, List.of(STATE_CHANGED_TYPE, REGISTERED_ERROR_TYPE));

        assertThat(events).containsExactlyInAnyOrder(stateEvent, registeredError);
        assertThat(events).doesNotContain(unknownError);
    }

    @Test
    void collectEventsShouldHideUnregisteredErrorEventSoItIsNotReturnedAgain() {
        TestUnregisteredErrorEvent unknownError = new TestUnregisteredErrorEvent("agg-1");
        blackboard.addObject(unknownError);

        AgentProcessResultTranslator.collectEvents(
                blackboard, List.of(STATE_CHANGED_TYPE, REGISTERED_ERROR_TYPE));

        // The unknown error was hidden — second call also returns nothing
        List<DomainEvent> second = AgentProcessResultTranslator.collectEvents(
                blackboard, List.of(STATE_CHANGED_TYPE, REGISTERED_ERROR_TYPE));
        assertThat(second).isEmpty();
    }
}
