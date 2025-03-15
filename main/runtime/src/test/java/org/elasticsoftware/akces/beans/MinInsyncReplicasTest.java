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

package org.elasticsoftware.akces.beans;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.elasticsoftware.akces.util.KafkaUtils.calculateQuorum;

public class MinInsyncReplicasTest {
    @Test
    void testScenarios() {
        // calculate quorom based on replication factor
        Assertions.assertEquals(1, calculateQuorum((short) 1));
        Assertions.assertEquals(2, calculateQuorum((short) 2));
        Assertions.assertEquals(2, calculateQuorum((short) 3));
        Assertions.assertEquals(3, calculateQuorum((short) 4));
        Assertions.assertEquals(3, calculateQuorum((short) 5));
        Assertions.assertEquals(4, calculateQuorum((short) 6));
        Assertions.assertEquals(4, calculateQuorum((short) 7));
        Assertions.assertEquals(5, calculateQuorum((short) 8));
        Assertions.assertEquals(5, calculateQuorum((short) 9));
        Assertions.assertEquals(6, calculateQuorum((short) 10));
    }

}
