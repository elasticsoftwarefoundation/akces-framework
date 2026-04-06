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

package org.elasticsoftware.akces.operator;

import org.apache.kafka.clients.admin.NewTopic;
import org.elasticsoftware.akces.operator.utils.KafkaTopicUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KafkaTopicUtils#createAgenticAggregateTopics(String)} and
 * {@link KafkaTopicUtils#createAgenticAggregateTopics(String, short)}, verifying that
 * agentic aggregate topics are created with the correct partition count (always 1),
 * cleanup policies, and naming conventions.
 */
class AgenticAggregateTopicCreationTest {

    @Test
    void shouldCreateThreeTopicsForAgenticAggregate() {
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics("TestAgentic");
        assertThat(topics).hasSize(3);
    }

    @Test
    void shouldCreateTopicsWithCorrectNames() {
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics("MyAgent");

        assertThat(topics).extracting(NewTopic::name)
                .containsExactly(
                        "MyAgent-Commands",
                        "MyAgent-DomainEvents",
                        "MyAgent-AggregateState");
    }

    @Test
    void allTopicsShouldHaveExactlyOnePartition() {
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics("SingletonAgent");

        for (NewTopic topic : topics) {
            assertThat(topic.numPartitions())
                    .as("Topic %s should have 1 partition", topic.name())
                    .isEqualTo(1);
        }
    }

    @Test
    void commandsAndDomainEventsTopicsShouldUseDeleteCleanupPolicy() {
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics("TestAgent", (short) 1);

        NewTopic commandsTopic = topics.stream()
                .filter(t -> t.name().endsWith("-Commands"))
                .findFirst().orElseThrow();
        NewTopic eventsTopic = topics.stream()
                .filter(t -> t.name().endsWith("-DomainEvents"))
                .findFirst().orElseThrow();

        assertThat(commandsTopic.configs().get("cleanup.policy")).isEqualTo("delete");
        assertThat(eventsTopic.configs().get("cleanup.policy")).isEqualTo("delete");
    }

    @Test
    void aggregateStateTopicShouldUseCompactCleanupPolicy() {
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics("TestAgent", (short) 1);

        NewTopic stateTopic = topics.stream()
                .filter(t -> t.name().endsWith("-AggregateState"))
                .findFirst().orElseThrow();

        assertThat(stateTopic.configs().get("cleanup.policy")).isEqualTo("compact");
    }

    @Test
    void defaultReplicationFactorShouldBeThree() {
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics("TestAgent");

        for (NewTopic topic : topics) {
            assertThat(topic.replicationFactor())
                    .as("Topic %s default replication factor should be 3", topic.name())
                    .isEqualTo((short) 3);
        }
    }

    @Test
    void customReplicationFactorShouldBeRespected() {
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics("TestAgent", (short) 1);

        for (NewTopic topic : topics) {
            assertThat(topic.replicationFactor())
                    .as("Topic %s should use custom replication factor", topic.name())
                    .isEqualTo((short) 1);
        }
    }

    @Test
    void minInsyncReplicasShouldNotExceedReplicationFactor() {
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics("TestAgent", (short) 1);

        for (NewTopic topic : topics) {
            int minIsr = Integer.parseInt(topic.configs().get("min.insync.replicas"));
            assertThat(minIsr)
                    .as("min.insync.replicas for %s should not exceed replication factor", topic.name())
                    .isLessThanOrEqualTo(topic.replicationFactor());
        }
    }

    @Test
    void allTopicsShouldUseLz4Compression() {
        List<NewTopic> topics = KafkaTopicUtils.createAgenticAggregateTopics("TestAgent", (short) 1);

        for (NewTopic topic : topics) {
            assertThat(topic.configs().get("compression.type"))
                    .as("Topic %s should use lz4 compression", topic.name())
                    .isEqualTo("lz4");
        }
    }
}
