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

import java.util.*;
import java.util.stream.Collectors;

public final class PartitionUtils {
    public static final String COMMANDS_SUFFIX = "-Commands";
    public static final String DOMAINEVENTS_SUFFIX = "-DomainEvents";
    public static final String AGGREGRATESTATE_SUFFIX = "-AggregateState";

    private PartitionUtils() {
    }

    public static Map<Integer, AggregatePartition> toAggregatePartitions(Collection<TopicPartition> topicPartitions) {
        Set<Integer> partitions = topicPartitions.stream().map(TopicPartition::partition).collect(Collectors.toSet());
        Map<Integer, AggregatePartition> aggregatePartitions = new HashMap<>();
        partitions.forEach(partition -> {
            Set<TopicPartition> aggregatePartition = topicPartitions.stream().filter(topicPartition ->
                    topicPartition.partition() == partition &&
                            (topicPartition.topic().endsWith(COMMANDS_SUFFIX) ||
                                    topicPartition.topic().endsWith(DOMAINEVENTS_SUFFIX) ||
                                    topicPartition.topic().endsWith(AGGREGRATESTATE_SUFFIX))).collect(Collectors.toSet());
            if (aggregatePartition.size() == 3) {
                TopicPartition command = aggregatePartition.stream().filter(topicPartition ->
                        topicPartition.topic().endsWith(COMMANDS_SUFFIX)).findFirst().orElseThrow();
                TopicPartition domainEvent = aggregatePartition.stream().filter(topicPartition ->
                        topicPartition.topic().endsWith(DOMAINEVENTS_SUFFIX)).findFirst().orElseThrow();
                TopicPartition aggregateState = aggregatePartition.stream().filter(topicPartition ->
                        topicPartition.topic().endsWith(AGGREGRATESTATE_SUFFIX)).findFirst().orElseThrow();
                //aggregatePartitions.put(partition, new AggregatePartition(consumer, producer, partition, command, domainEvent, aggregateState));
            } else {
                throw new NoSuchElementException("Partition " + partition + " is incomplete, found " + aggregatePartition);
            }
        });
        return aggregatePartitions;
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

    public static List<TopicPartition> toExternalDomainEventTopicPartitions(AggregateRuntime aggregate, int partition) {
        return aggregate.getExternalDomainEventTypes().stream().map(externalDomainEvent ->
                new TopicPartition(externalDomainEvent.typeName() + DOMAINEVENTS_SUFFIX, partition)).collect(Collectors.toList());
    }

    public static TopicPartition parseReplyToTopicPartition(String replyTo) {
        // split by last index of '-'
        int lastIndex = replyTo.lastIndexOf('-');
        String topic = replyTo.substring(0, lastIndex);
        int partition = Integer.parseInt(replyTo.substring(lastIndex + 1));
        return new TopicPartition(topic, partition);
    }
}
