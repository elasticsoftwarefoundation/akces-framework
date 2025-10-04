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

package org.elasticsoftware.akces.kafka;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AggregatePartitionStateTest {

    @Test
    void testEnumValues() {
        AggregatePartitionState[] states = AggregatePartitionState.values();
        assertEquals(5, states.length);
        assertNotNull(AggregatePartitionState.valueOf("INITIALIZING"));
        assertNotNull(AggregatePartitionState.valueOf("LOADING_GDPR_KEYS"));
        assertNotNull(AggregatePartitionState.valueOf("LOADING_STATE"));
        assertNotNull(AggregatePartitionState.valueOf("PROCESSING"));
        assertNotNull(AggregatePartitionState.valueOf("SHUTTING_DOWN"));
    }

    @Test
    void testEnumValueOf() {
        assertEquals(AggregatePartitionState.INITIALIZING, AggregatePartitionState.valueOf("INITIALIZING"));
        assertEquals(AggregatePartitionState.PROCESSING, AggregatePartitionState.valueOf("PROCESSING"));
        assertEquals(AggregatePartitionState.SHUTTING_DOWN, AggregatePartitionState.valueOf("SHUTTING_DOWN"));
    }

    @Test
    void testEnumValueOfInvalid() {
        assertThrows(IllegalArgumentException.class, () -> 
            AggregatePartitionState.valueOf("INVALID_STATE")
        );
    }
}
