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

package org.elasticsoftware.akces.operator.utils;

import org.apache.kafka.clients.admin.NewTopic;

import java.util.List;
import java.util.Map;

public class KafkaTopicUtils {
    public static final String COMMANDS_SUFFIX = "-Commands";
    public static final String DOMAINEVENTS_SUFFIX = "-DomainEvents";
    public static final String AGGREGRATESTATE_SUFFIX = "-AggregateState";

    public static List<NewTopic> createTopics(String topicName, int numPartitions) {
        return List.of(
                createTopic(topicName + COMMANDS_SUFFIX, numPartitions),
                createTopic(topicName + DOMAINEVENTS_SUFFIX, numPartitions),
                createCompactedTopic(topicName + AGGREGRATESTATE_SUFFIX, numPartitions)
        );
    }

    /**
     * Creates the Kafka topics for an AgenticAggregate with the default replication factor of 3.
     *
     * <p>All topics are always created with exactly 1 partition because AgenticAggregates are
     * singletons — there is never more than one active consumer per topic. The three topics
     * created are:
     * <ul>
     *   <li>{@code <agenticAggregateName>-Commands}</li>
     *   <li>{@code <agenticAggregateName>-DomainEvents}</li>
     *   <li>{@code <agenticAggregateName>-AggregateState} (log-compacted)</li>
     * </ul>
     *
     * @param agenticAggregateName the name of the AgenticAggregate (used as the topic name prefix)
     * @return an immutable list of the three {@link NewTopic} descriptors to create
     */
    public static List<NewTopic> createAgenticAggregateTopics(String agenticAggregateName) {
        return createAgenticAggregateTopics(agenticAggregateName, (short) 3);
    }

    /**
     * Creates the Kafka topics for an AgenticAggregate with the specified replication factor.
     *
     * <p>All topics are always created with exactly 1 partition because AgenticAggregates are
     * singletons — there is never more than one active consumer per topic. The
     * {@code min.insync.replicas} topic config is set to {@code min(2, replicationFactor)} to
     * ensure it never exceeds the replication factor (which would make producing impossible).
     *
     * @param agenticAggregateName the name of the AgenticAggregate (used as the topic name prefix)
     * @param replicationFactor    the replication factor for all three topics
     * @return an immutable list of the three {@link NewTopic} descriptors to create
     */
    public static List<NewTopic> createAgenticAggregateTopics(String agenticAggregateName, short replicationFactor) {
        return List.of(
                createTopic(agenticAggregateName + COMMANDS_SUFFIX, 1, replicationFactor),
                createTopic(agenticAggregateName + DOMAINEVENTS_SUFFIX, 1, replicationFactor),
                createCompactedTopic(agenticAggregateName + AGGREGRATESTATE_SUFFIX, 1, replicationFactor)
        );
    }

    public static NewTopic createTopic(String name, int numPartitions) {
        return createTopic(name, numPartitions, -1L);
    }

    public static NewTopic createTopic(String name, int numPartitions, long retentionMs) {
        NewTopic topic = new NewTopic(name, numPartitions, Short.parseShort("1"));
        return topic.configs(Map.of(
                "min.insync.replicas", "2",
                "cleanup.policy", "delete",
                "max.message.bytes", "20971520",
                "retention.ms", Long.toString(retentionMs),
                "segment.ms", "604800000",
                "compression.type", "lz4"));
    }

    public static NewTopic createTopic(String name, int numPartitions, short replicationFactor) {
        return createTopic(name, numPartitions, -1L, replicationFactor);
    }

    public static NewTopic createTopic(String name, int numPartitions, long retentionMs, short replicationFactor) {
        NewTopic topic = new NewTopic(name, numPartitions, replicationFactor);
        return topic.configs(Map.of(
                "min.insync.replicas", String.valueOf(Math.min(2, replicationFactor)),
                "cleanup.policy", "delete",
                "max.message.bytes", "20971520",
                "retention.ms", Long.toString(retentionMs),
                "segment.ms", "604800000",
                "compression.type", "lz4"));
    }

    public static NewTopic createCompactedTopic(String name, int numPartitions) {
        NewTopic topic = new NewTopic(name, numPartitions, Short.parseShort("1"));
        return topic.configs(Map.of(
                "min.insync.replicas", "2",
                "cleanup.policy", "compact",
                "max.message.bytes", "20971520",
                "retention.ms", "-1",
                "segment.ms", "604800000",
                "min.cleanable.dirty.ratio", "0.1",
                "delete.retention.ms", "604800000",
                "compression.type", "lz4"));
    }

    public static NewTopic createCompactedTopic(String name, int numPartitions, short replicationFactor) {
        NewTopic topic = new NewTopic(name, numPartitions, replicationFactor);
        return topic.configs(Map.of(
                "min.insync.replicas", String.valueOf(Math.min(2, replicationFactor)),
                "cleanup.policy", "compact",
                "max.message.bytes", "20971520",
                "retention.ms", "-1",
                "segment.ms", "604800000",
                "min.cleanable.dirty.ratio", "0.1",
                "delete.retention.ms", "604800000",
                "compression.type", "lz4"));
    }
}
