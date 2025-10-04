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

package org.elasticsoftware.akces;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AkcesControllerStateTest {

    @Test
    void testEnumValues() {
        AkcesControllerState[] states = AkcesControllerState.values();
        assertEquals(6, states.length);
        assertNotNull(AkcesControllerState.valueOf("INITIALIZING"));
        assertNotNull(AkcesControllerState.valueOf("INITIAL_REBALANCING"));
        assertNotNull(AkcesControllerState.valueOf("REBALANCING"));
        assertNotNull(AkcesControllerState.valueOf("RUNNING"));
        assertNotNull(AkcesControllerState.valueOf("SHUTTING_DOWN"));
        assertNotNull(AkcesControllerState.valueOf("ERROR"));
    }

    @Test
    void testEnumValueOf() {
        assertEquals(AkcesControllerState.INITIALIZING, AkcesControllerState.valueOf("INITIALIZING"));
        assertEquals(AkcesControllerState.RUNNING, AkcesControllerState.valueOf("RUNNING"));
        assertEquals(AkcesControllerState.ERROR, AkcesControllerState.valueOf("ERROR"));
    }

    @Test
    void testEnumValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> 
            AkcesControllerState.valueOf("INVALID_STATE")
        );
    }
}
