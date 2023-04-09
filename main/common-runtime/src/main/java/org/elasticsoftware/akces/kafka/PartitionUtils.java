package org.elasticsoftware.akces.kafka;

import org.apache.kafka.common.TopicPartition;

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
                throw new NoSuchElementException("Partition "+partition+" is incomplete, found "+aggregatePartition);
            }
        });
        return aggregatePartitions;
    }
}
