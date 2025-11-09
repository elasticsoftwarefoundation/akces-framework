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
import static org.mockito.Mockito.mock;

class AggregatePartitionCommandBusTest {

    @Test
    void testRegisterCommandBus() {
        AggregatePartition mockPartition = mock(AggregatePartition.class);
        
        // This should not throw an exception
        AggregatePartitionCommandBus.registerCommandBus(mockPartition);
        
        // The method is package-private and sets a ThreadLocal, so we can't easily verify the result
        // But we can at least ensure it doesn't throw an exception
        assertDoesNotThrow(() -> AggregatePartitionCommandBus.registerCommandBus(mockPartition));
    }

    @Test
    void testRegisterCommandBusWithNull() {
        // Should handle null without throwing
        assertDoesNotThrow(() -> AggregatePartitionCommandBus.registerCommandBus(null));
    }
}
