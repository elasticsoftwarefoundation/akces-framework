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

package org.elasticsoftware.akces.agentic.testfixtures;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;

/**
 * Test utility methods for the TradingAdvisor integration tests.
 * Handles Kafka topic creation for the agentic aggregate.
 */
public final class TradingAdvisorTestUtils {

    private TradingAdvisorTestUtils() {
        // utility class
    }

    /**
     * Creates all required Kafka topics for the TradingAdvisor agentic aggregate.
     *
     * <p>Agentic aggregates always use 1 partition (singleton pattern).
     */
    public static void prepareKafka(String bootstrapServers) {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        kafkaAdmin.createOrModifyTopics(
                createCompactedTopic("Akces-Control", 1),
                createTopic("Akces-CommandResponses", 1, 604800000L),
                createCompactedTopic("Akces-Schemas", 1),
                createTopic("TradingAdvisor-Commands", 1, -1L),
                createTopic("TradingAdvisor-DomainEvents", 1, -1L),
                createCompactedTopic("TradingAdvisor-AggregateState", 1));
    }

    private static NewTopic createTopic(String name, int numPartitions, long retentionMs) {
        NewTopic topic = new NewTopic(name, numPartitions, Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy", "delete",
                "max.message.bytes", "20971520",
                "retention.ms", Long.toString(retentionMs),
                "segment.ms", "604800000"));
    }

    private static NewTopic createCompactedTopic(String name, int numPartitions) {
        NewTopic topic = new NewTopic(name, numPartitions, Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy", "compact",
                "max.message.bytes", "20971520",
                "retention.ms", "-1",
                "segment.ms", "604800000",
                "min.cleanable.dirty.ratio", "0.1",
                "delete.retention.ms", "604800000",
                "compression.type", "lz4"));
    }
}
