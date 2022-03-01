package org.elasticsoftware.akces;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateBuilder;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.kafka.AggregatePartition;
import org.elasticsoftware.akces.kafka.KafkaAggregateBuilder;
import org.elasticsoftware.akces.kafka.PartitionUtils;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;

import java.time.Duration;
import java.util.*;

import static org.apache.kafka.common.IsolationLevel.READ_COMMITTED;

public class AkcesHub<S extends AggregateState> extends Thread implements AutoCloseable, ConsumerRebalanceListener {
    private static final ThreadLocal<CommandBus> commandBusHolder = new ThreadLocal<>();
    private final String bootstrapServers;
    private final Consumer<String, ProtocolRecord> mainConsumer;
    private final Aggregate<S> aggregate;
    private volatile boolean running = true;

    public AkcesHub(Aggregate<S> aggregate,
                    String nodeId,
                    String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.aggregate = aggregate;
        AggregateBuilder<S> builder = new KafkaAggregateBuilder<>(aggregate.getName(), aggregate.getStateClass());
        aggregate.configure(builder);
        ProtocolRecordSerde serde = new ProtocolRecordSerde();
        final Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "2000");
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumerConfig.put("internal.leave.group.on.close", true);
        consumerConfig.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 2000);
        consumerConfig.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 10000);
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // all nodes of the same service are in a group and will get some partitions assigned
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, aggregate.getName());
        consumerConfig.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, nodeId);
        consumerConfig.put(CommonClientConfigs.CLIENT_ID_CONFIG, nodeId);
        consumerConfig.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, READ_COMMITTED.name().toLowerCase(Locale.ROOT));
        mainConsumer = new KafkaConsumer<>(consumerConfig, new StringDeserializer(), serde.deserializer());
    }

    public static CommandBus getCommandBus() {
        return commandBusHolder.get();
    }

    public void start() {
        mainConsumer.subscribe(List.of(aggregate.getName() + "-Commands", aggregate.getName() + "-DomainEvents", aggregate.getName() + "-AggregateState"),
                new ConsumerRebalanceListener() {
                    @Override
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                        // cleanup the aggregate partitions, finish processing exising batches
                    }

                    @Override
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        // for each partition we need to create the handler, first we need to group partitions together
                        Map<Integer, AggregatePartition> aggregatePartitions = PartitionUtils.toAggregatePartitions(partitions);
                        // for each aggregate partition we need to initialize the subsystem

                        // make sure the state is loaded into the aggregate state stores


                    }
                });
        // make sure we will rebalance in next poll
        mainConsumer.enforceRebalance();
        // TODO: create a proper loop
        while(running) {
            // prepare the AggregatePartitions

            ConsumerRecords<String, ProtocolRecord> records = mainConsumer.poll(Duration.ofMillis(100));
            // loop over the current AggregatePartitions

            // first handle the state updates (generally only when we are loading)

            // then handle the DomainEvents (internal and external)

            // then handle the Commands
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        // need to shut down the proper partitions (and wait for it)
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {

    }
}
