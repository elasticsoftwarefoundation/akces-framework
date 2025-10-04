/*
 * Copyright 2022 - 2025 The Original Authors
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

import static org.junit.jupiter.api.Assertions.*;

public class DomainEventTypeTests {

    @Test
    public void testDomainEventTypeCreation() {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
            "TestEvent",
            1,
            TestEvent.class,
            true,
            false,
            false,
            true
        );
        
        assertEquals("TestEvent", eventType.typeName());
        assertEquals(1, eventType.version());
        assertEquals(TestEvent.class, eventType.typeClass());
        assertTrue(eventType.create());
        assertFalse(eventType.external());
        assertFalse(eventType.error());
        assertTrue(eventType.piiData());
    }

    @Test
    public void testGetSchemaPrefix() {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
            "TestEvent",
            1,
            TestEvent.class,
            false,
            false,
            false,
            false
        );
        
        assertEquals("domainevents.", eventType.getSchemaPrefix());
    }

    @Test
    public void testRelaxExternalValidation() {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
            "TestEvent",
            1,
            TestEvent.class,
            false,
            true,
            false,
            false
        );
        
        assertTrue(eventType.relaxExternalValidation());
    }

    @Test
    public void testDomainEventTypeWithErrorFlag() {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
            "TestErrorEvent",
            1,
            TestEvent.class,
            false,
            false,
            true,
            false
        );
        
        assertTrue(eventType.error());
    }

    @Test
    public void testDomainEventTypeWithExternalFlag() {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
            "ExternalEvent",
            1,
            TestEvent.class,
            false,
            true,
            false,
            false
        );
        
        assertTrue(eventType.external());
    }

    @Test
    public void testDomainEventTypeEquality() {
        DomainEventType<TestEvent> eventType1 = new DomainEventType<>(
            "TestEvent",
            1,
            TestEvent.class,
            true,
            false,
            false,
            false
        );
        
        DomainEventType<TestEvent> eventType2 = new DomainEventType<>(
            "TestEvent",
            1,
            TestEvent.class,
            true,
            false,
            false,
            false
        );
        
        assertEquals(eventType1, eventType2);
        assertEquals(eventType1.hashCode(), eventType2.hashCode());
    }

    static class TestEvent implements DomainEvent {
    }
}
