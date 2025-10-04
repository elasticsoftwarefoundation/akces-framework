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

import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PartitionUtilsTest {

    @Test
    void testToCommandTopicPartition() {
        AggregateRuntime aggregate = mock(AggregateRuntime.class);
        when(aggregate.getName()).thenReturn("TestAggregate");
        
        TopicPartition result = PartitionUtils.toCommandTopicPartition(aggregate, 3);
        
        assertEquals("TestAggregate-Commands", result.topic());
        assertEquals(3, result.partition());
    }

    @Test
    void testToDomainEventTopicPartition() {
        AggregateRuntime aggregate = mock(AggregateRuntime.class);
        when(aggregate.getName()).thenReturn("TestAggregate");
        
        TopicPartition result = PartitionUtils.toDomainEventTopicPartition(aggregate, 5);
        
        assertEquals("TestAggregate-DomainEvents", result.topic());
        assertEquals(5, result.partition());
    }

    @Test
    void testToAggregateStateTopicPartition() {
        AggregateRuntime aggregate = mock(AggregateRuntime.class);
        when(aggregate.getName()).thenReturn("TestAggregate");
        
        TopicPartition result = PartitionUtils.toAggregateStateTopicPartition(aggregate, 2);
        
        assertEquals("TestAggregate-AggregateState", result.topic());
        assertEquals(2, result.partition());
    }

    @Test
    void testToGDPRKeysTopicPartition() {
        AggregateRuntime aggregate = mock(AggregateRuntime.class);
        when(aggregate.getName()).thenReturn("TestAggregate");
        
        TopicPartition result = PartitionUtils.toGDPRKeysTopicPartition(aggregate, 1);
        
        assertEquals("Akces-GDPRKeys", result.topic());
        assertEquals(1, result.partition());
    }

    @Test
    void testParseReplyToTopicPartition() {
        String replyTo = "TestTopic-Commands-3";
        
        TopicPartition result = PartitionUtils.parseReplyToTopicPartition(replyTo);
        
        assertEquals("TestTopic-Commands", result.topic());
        assertEquals(3, result.partition());
    }

    @Test
    void testParseReplyToTopicPartitionWithMultipleDashes() {
        String replyTo = "Test-Topic-With-Dashes-10";
        
        TopicPartition result = PartitionUtils.parseReplyToTopicPartition(replyTo);
        
        assertEquals("Test-Topic-With-Dashes", result.topic());
        assertEquals(10, result.partition());
    }

    @Test
    void testConstants() {
        assertEquals("-Commands", PartitionUtils.COMMANDS_SUFFIX);
        assertEquals("-DomainEvents", PartitionUtils.DOMAINEVENTS_SUFFIX);
        assertEquals("-AggregateState", PartitionUtils.AGGREGRATESTATE_SUFFIX);
    }
}
