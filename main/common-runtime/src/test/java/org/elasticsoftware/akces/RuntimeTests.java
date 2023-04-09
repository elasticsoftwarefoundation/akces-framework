package org.elasticsoftware.akces;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest
public class RuntimeTests implements TestPropertyProvider {

    private static Network network = Network.newNetwork();

    @Container
    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.3.3"))
                    .withNetwork(network)
                    .withNetworkAliases("kafka");

    @Container
    private static final GenericContainer<?> schemaRegistry =
            new GenericContainer<>(DockerImageName.parse("confluentinc/cp-schema-registry:7.3.3"))
                    .withNetwork(network)
                    .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:9092")
                    .withEnv("SCHEMA_REGISTRY_HOST_NAME", "localhost")
                    .withExposedPorts(8081)
                    .withNetworkAliases("schema-registry")
                    .dependsOn(kafka);

    @Inject
    AdminClient adminClient;

    @Inject
    SchemaRegistryClient schemaRegistryClient;

    @Test
    @Order(1)
    public void createTopics() throws ExecutionException, InterruptedException {
        List<NewTopic> topics = List.of(
                createCompactedTopic("Akces-Control", 3),
                createTopic("Wallet-Commands", 3),
                createTopic("Wallet-DomainEvents", 3),
                createCompactedTopic("Wallet-AggregateState", 3));
        CreateTopicsResult result = adminClient.createTopics(topics);
        result.all().get();
        System.out.println(adminClient.listTopics(new ListTopicsOptions().listInternal(true)).listings().get());
    }

    @Test
    @Order(2)
    public void createSchemas() throws RestClientException, IOException {
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                    "kafka.enabled", "true",
                "kafka.bootstrap.servers", kafka.getBootstrapServers(),
                "kafka.schemaregistry.url", "http://"+schemaRegistry.getHost()+":"+schemaRegistry.getMappedPort(8081)
        );
    }

    private NewTopic createTopic(String name, int numPartitions) {
        NewTopic topic = new NewTopic(name, numPartitions , Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy","delete",
                "max.message.bytes","10485760",
                "retention.ms", "-1",
                "segment.ms","604800000"));
    }

    private NewTopic createCompactedTopic(String name, int numPartitions) {
        NewTopic topic = new NewTopic(name, numPartitions , Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy","compact",
                "max.message.bytes","20971520",
                "retention.ms", "-1",
                "segment.ms","604800000",
                "min.cleanable.dirty.ratio", "0.1",
                "delete.retention.ms", "604800000",
                "compression.type", "lz4"));
    }
}
