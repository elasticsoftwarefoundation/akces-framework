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

package org.elasticsoftware.akces.kafka;

import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;

public final class PartitionUtils {
    public static final String COMMANDS_SUFFIX = "-Commands";
    public static final String DOMAINEVENTS_SUFFIX = "-DomainEvents";
    public static final String AGGREGRATESTATE_SUFFIX = "-AggregateState";

    private PartitionUtils() {
    }

    public static TopicPartition toCommandTopicPartition(AggregateRuntime aggregate, int partition) {
        return new TopicPartition(aggregate.getName() + COMMANDS_SUFFIX, partition);
    }

    public static TopicPartition toDomainEventTopicPartition(AggregateRuntime aggregate, int partition) {
        return new TopicPartition(aggregate.getName() + DOMAINEVENTS_SUFFIX, partition);
    }

    public static TopicPartition toAggregateStateTopicPartition(AggregateRuntime aggregate, int partition) {
        return new TopicPartition(aggregate.getName() + AGGREGRATESTATE_SUFFIX, partition);
    }

    public static TopicPartition toGDPRKeysTopicPartition(AggregateRuntime aggregate, int partition) {
        return new TopicPartition("Akces-GDPRKeys", partition);
    }

    public static TopicPartition parseReplyToTopicPartition(String replyTo) {
        // split by last index of '-'
        int lastIndex = replyTo.lastIndexOf('-');
        String topic = replyTo.substring(0, lastIndex);
        int partition = Integer.parseInt(replyTo.substring(lastIndex + 1));
        return new TopicPartition(topic, partition);
    }
}
