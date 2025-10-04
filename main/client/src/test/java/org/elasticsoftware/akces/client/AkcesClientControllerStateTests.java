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

package org.elasticsoftware.akces.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AkcesClientControllerStateTests {

    @Test
    public void testEnumValues() {
        AkcesClientControllerState[] states = AkcesClientControllerState.values();
        
        assertEquals(3, states.length);
        assertEquals(AkcesClientControllerState.INITIALIZING, states[0]);
        assertEquals(AkcesClientControllerState.RUNNING, states[1]);
        assertEquals(AkcesClientControllerState.SHUTTING_DOWN, states[2]);
    }

    @Test
    public void testValueOf() {
        assertEquals(AkcesClientControllerState.INITIALIZING, 
                    AkcesClientControllerState.valueOf("INITIALIZING"));
        assertEquals(AkcesClientControllerState.RUNNING, 
                    AkcesClientControllerState.valueOf("RUNNING"));
        assertEquals(AkcesClientControllerState.SHUTTING_DOWN, 
                    AkcesClientControllerState.valueOf("SHUTTING_DOWN"));
    }

    @Test
    public void testValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> 
            AkcesClientControllerState.valueOf("INVALID_STATE")
        );
    }

    @Test
    public void testEnumEquality() {
        AkcesClientControllerState state1 = AkcesClientControllerState.RUNNING;
        AkcesClientControllerState state2 = AkcesClientControllerState.RUNNING;
        
        assertSame(state1, state2);
        assertEquals(state1, state2);
    }

    @Test
    public void testEnumToString() {
        assertEquals("INITIALIZING", AkcesClientControllerState.INITIALIZING.toString());
        assertEquals("RUNNING", AkcesClientControllerState.RUNNING.toString());
        assertEquals("SHUTTING_DOWN", AkcesClientControllerState.SHUTTING_DOWN.toString());
    }

    @Test
    public void testEnumName() {
        assertEquals("INITIALIZING", AkcesClientControllerState.INITIALIZING.name());
        assertEquals("RUNNING", AkcesClientControllerState.RUNNING.name());
        assertEquals("SHUTTING_DOWN", AkcesClientControllerState.SHUTTING_DOWN.name());
    }
}
