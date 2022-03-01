package org.elasticsoftware.akces.kafka;

import org.apache.kafka.common.TopicPartition;

public record AggregatePartition(Integer id, TopicPartition commands, TopicPartition domainEvents, TopicPartition state) {
}
