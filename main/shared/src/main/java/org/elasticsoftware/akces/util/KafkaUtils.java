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

package org.elasticsoftware.akces.util;

import org.apache.kafka.clients.admin.NewTopic;

import java.util.Map;

public final class KafkaUtils {
    public static final String DOMAIN_EVENT_INDEX = "-DomainEventIndex";

    private KafkaUtils() {
    }

    public static String getIndexTopicName(String indexName, String indexKey) {
        return indexName + "-" + indexKey + DOMAIN_EVENT_INDEX;
    }

    public static NewTopic createCompactedTopic(String name, int numPartitions, short replicationFactor) {
        NewTopic topic = new NewTopic(name, numPartitions, replicationFactor);
        // calculate the min.insync.replicas based on the replication factor
        String minInSyncReplicas = String.valueOf(calculateQuorum(replicationFactor));
        return topic.configs(Map.of(
                "min.insync.replicas", minInSyncReplicas,
                "cleanup.policy", "compact",
                "max.message.bytes", "20971520",
                "retention.ms", "-1",
                "segment.ms", "604800000",
                "min.cleanable.dirty.ratio", "0.1",
                "delete.retention.ms", "604800000",
                "compression.type", "lz4"));
    }

    public static int calculateQuorum(short replicationFactor) {
        return (replicationFactor / 2) + 1;
    }

}
