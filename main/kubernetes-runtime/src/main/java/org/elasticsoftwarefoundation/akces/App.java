package org.elasticsoftwarefoundation.akces;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.kafka.common.IsolationLevel.READ_COMMITTED;

/**
 * Test the loadbalancing functionality
 *
 */
public class App {
    public static void main( String[] args ) {
        ProtocolRecordSerde serde = new ProtocolRecordSerde();
        final Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "2000");
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumerConfig.put("internal.leave.group.on.close", true);
        consumerConfig.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 2000);
        consumerConfig.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 10000);
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "akces-kafka-bootstrap.strimzi:9092");
        // all nodes of the same service are in a group and will get some partitions assigned
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "Wallet");
        consumerConfig.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, System.getenv("POD_NAME"));
        consumerConfig.put(CommonClientConfigs.CLIENT_ID_CONFIG, System.getenv("POD_NAME"));
        consumerConfig.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, READ_COMMITTED.name().toLowerCase(Locale.ROOT));
        consumerConfig.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        final Consumer<String, ProtocolRecord> mainConsumer = new KafkaConsumer<>(consumerConfig, new StringDeserializer(), serde.deserializer());
        final CountDownLatch waitLatch = new CountDownLatch(1);
        final AtomicBoolean running = new AtomicBoolean(true);
        Executors.newSingleThreadExecutor().submit(() -> {
            mainConsumer.subscribe(List.of("AkcesHub"), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    partitions.forEach(topicPartition -> System.out.println("Revoked "+ topicPartition));
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    partitions.forEach(topicPartition -> System.out.println("Assigned "+ topicPartition));
                }
            });
            while(running.get()) {
                try {
                    mainConsumer.poll(Duration.ofMillis(100));
                } catch(Exception e) {
                    // ignore
                }
            }
            System.out.println("Shutdown hook ran!");
            mainConsumer.close();
            waitLatch.countDown();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Inside Shutdown hook");
            running.set(false);
        }));
        try {
            waitLatch.await();
        } catch (InterruptedException e) {
            //Thread.currentThread().interrupt();
            // ignore
        }
        System.out.println("EOF");
    }
}
