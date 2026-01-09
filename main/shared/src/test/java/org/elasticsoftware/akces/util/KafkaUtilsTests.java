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

package org.elasticsoftware.akces.util;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class KafkaUtilsTests {

    @Test
    public void testGetIndexTopicName() {
        String result = KafkaUtils.getIndexTopicName("wallet", "userId");
        assertEquals("wallet-userId-DomainEventIndex", result);
    }

    @Test
    public void testGetIndexTopicNameWithEmptyIndexKey() {
        String result = KafkaUtils.getIndexTopicName("account", "");
        assertEquals("account--DomainEventIndex", result);
    }

    @Test
    public void testCreateCompactedTopic() {
        NewTopic topic = KafkaUtils.createCompactedTopic("test-topic", 3, (short) 3);
        
        assertNotNull(topic);
        assertEquals("test-topic", topic.name());
        assertEquals(3, topic.numPartitions());
        assertEquals((short) 3, topic.replicationFactor());
        
        // Verify configs
        assertNotNull(topic.configs());
        assertEquals("2", topic.configs().get("min.insync.replicas"));
        assertEquals("compact", topic.configs().get("cleanup.policy"));
        assertEquals("20971520", topic.configs().get("max.message.bytes"));
        assertEquals("-1", topic.configs().get("retention.ms"));
        assertEquals("lz4", topic.configs().get("compression.type"));
    }

    @Test
    public void testCreateCompactedTopicWithSingleReplica() {
        NewTopic topic = KafkaUtils.createCompactedTopic("single-topic", 1, (short) 1);
        
        assertEquals("1", topic.configs().get("min.insync.replicas"));
    }

    @Test
    public void testCalculateQuorum() {
        assertEquals(1, KafkaUtils.calculateQuorum((short) 1));
        assertEquals(2, KafkaUtils.calculateQuorum((short) 2));
        assertEquals(2, KafkaUtils.calculateQuorum((short) 3));
        assertEquals(3, KafkaUtils.calculateQuorum((short) 4));
        assertEquals(3, KafkaUtils.calculateQuorum((short) 5));
    }
}
