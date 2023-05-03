package org.elasticsoftware.akces;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.aggregate.AccountCreatedEvent;
import org.elasticsoftware.akces.aggregate.CreateWalletCommand;
import org.elasticsoftware.akces.aggregate.CreditWalletCommand;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.control.CommandServiceCommandType;
import org.elasticsoftware.akces.control.CommandServiceDomainEventType;
import org.elasticsoftware.akces.control.CommandServiceRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.elasticsoftware.akces.kafka.PartitionUtils.COMMANDS_SUFFIX;
import static org.elasticsoftware.akces.kafka.PartitionUtils.DOMAINEVENTS_SUFFIX;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = RuntimeConfiguration.class, properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ContextConfiguration(initializers = RuntimeTests.DataSourceInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RuntimeTests  {

    private static final Network network = Network.newNetwork();

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
    KafkaAdmin adminClient;

    @Inject
    SchemaRegistryClient schemaRegistryClient;

    @Inject
    AkcesController akcesController;

    @Inject
    ConsumerFactory<String, ProtocolRecord> consumerFactory;

    @Inject
    ProducerFactory<String, ProtocolRecord> producerFactory;

    @Inject
    ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory;

    @Inject
    ObjectMapper objectMapper;

    public static class DataSourceInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            prepareExternalSchemas("http://"+schemaRegistry.getHost()+":"+schemaRegistry.getMappedPort(8081));
            prepareExternalServices(kafka.getBootstrapServers());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrapServers="+kafka.getBootstrapServers(),
                    "kafka.schemaregistry.url=http://"+schemaRegistry.getHost()+":"+schemaRegistry.getMappedPort(8081)
            );
        }
    }

    public static void prepareKafka(String bootstrapServers) {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        kafkaAdmin.createOrModifyTopics(
                createCompactedTopic("Akces-Control", 3),
                createTopic("Wallet-Commands", 3),
                createTopic("Wallet-DomainEvents", 3),
                createTopic("Account-DomainEvents", 3),
                createCompactedTopic("Wallet-AggregateState", 3));
    }

    private static NewTopic createTopic(String name, int numPartitions) {
        NewTopic topic = new NewTopic(name, numPartitions , Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy","delete",
                "max.message.bytes","10485760",
                "retention.ms", "-1",
                "segment.ms","604800000"));
    }

    private static NewTopic createCompactedTopic(String name, int numPartitions) {
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

    public static void prepareExternalSchemas(String url) {
        SchemaRegistryClient src = new CachedSchemaRegistryClient(url, 100);
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS, JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator jsonSchemaGenerator = new SchemaGenerator(config);
        try {
            src.register("AccountCreated",
                    new JsonSchema(jsonSchemaGenerator.generateSchema(AccountCreatedEvent.class), List.of(), Map.of(), 1),
                    1,
                    -1);
        } catch (IOException | RestClientException e) {
            throw new ApplicationContextException("Problem populating SchemaRegistry", e);
        }
    }

    public static void prepareExternalServices(String bootstrapServers) {
        AkcesControlRecordSerde controlSerde = new AkcesControlRecordSerde(new ObjectMapper());
        Map<String, Object> controlProducerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true",
                ProducerConfig.LINGER_MS_CONFIG, "0",
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1",
                ProducerConfig.RETRIES_CONFIG, "2147483647",
                ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "0",
                ProducerConfig.TRANSACTIONAL_ID_CONFIG, "Test-AkcesControllerProducer",
                ProducerConfig.CLIENT_ID_CONFIG, "Test-AkcesControllerProducer");
        try (Producer<String,AkcesControlRecord> controlProducer = new KafkaProducer<>(controlProducerProps, new StringSerializer(), controlSerde.serializer())) {
            controlProducer.initTransactions();
            CommandServiceRecord commandServiceRecord = new CommandServiceRecord(
                    "Account",
                    "Account" + COMMANDS_SUFFIX,
                    "Account" + DOMAINEVENTS_SUFFIX,
                    List.of(new CommandServiceCommandType<>("CreateAccount",1, true)),
                    List.of(new CommandServiceDomainEventType<>("AccountCreated", 1, true, false)),
                    List.of());
            controlProducer.beginTransaction();
            for (int partition = 0; partition < 3; partition++) {
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "Account", commandServiceRecord));
            }
            controlProducer.commitTransaction();
        }
    }

    @AfterAll
    public static void cleanUp() throws IOException {
        // clean up the rocksdb directory
        Files.walk(Paths.get("/tmp/akces"))
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Test
    @Order(1)
    public void testKafkaAdminClient() {
        assertNotNull(adminClient);
        Map<String,TopicDescription> topics =
                adminClient.describeTopics(
                        "Akces-Control",
                        "Wallet-Commands",
                        "Wallet-DomainEvents",
                        "Account-DomainEvents",
                        "Wallet-AggregateState");
        assertNotNull(topics);
        assertFalse(topics.isEmpty());
    }

    @Test
    @Order(2)
    public void createSchemas() throws RestClientException, IOException, InterruptedException {
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    @Order(3)
    public void testAckesControl() throws JsonProcessingException {
        assertNotNull(akcesController);
        Producer<String, ProtocolRecord> testProducer = producerFactory.createProducer("test");
        Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test", "test");
        Consumer<String, AkcesControlRecord> controlConsumer = controlConsumerFactory.createConsumer("Test-AkcesControl","test-akces-control");

        controlConsumer.subscribe(List.of("Akces-Control"));
        controlConsumer.poll(Duration.ofMillis(1000));
        controlConsumer.seekToBeginning(controlConsumer.assignment());

        ConsumerRecords<String, AkcesControlRecord> controlRecords = new ConsumerRecords<>(Collections.emptyMap());
        while(controlRecords.isEmpty()) {
            controlRecords = controlConsumer.poll(Duration.ofMillis(1000));
        }

        // TODO: ensure that we see the Wallet command service

        controlConsumer.close();

        // wait until the ackes controller is running
        while(!akcesController.isRunning()) {
            Thread.onSpinWait();
        }

        String userId = "47db2418-dd10-11ed-afa1-0242ac120002";
        CreateWalletCommand command = new CreateWalletCommand(userId,"USD");
        CommandRecord commandRecord = new CommandRecord(null,"CreateWallet", 1, objectMapper.writeValueAsBytes(command), PayloadEncoding.JSON, command.getAggregateId(), null);
        String topicName = akcesController.resolveTopic(command.getClass());
        int partition = akcesController.resolvePartition(command.getAggregateId());
        // produce a command to create a Wallet
        testProducer.beginTransaction();
        testProducer.send(new ProducerRecord<>(topicName, partition, commandRecord.aggregateId(), commandRecord));
        testProducer.commitTransaction();

        TopicPartition aggregateStatePartition = new TopicPartition("Wallet-AggregateState", partition);
        TopicPartition domainEventsPartition = new TopicPartition("Wallet-DomainEvents", partition);

        // now we should have an entry in the Wallet-AggregateState topic and in the Wallet-DomainEvents topic
        testConsumer.assign(List.of(aggregateStatePartition, domainEventsPartition));
        // make sure we don't miss any events due to default offset reset strategy latest
        testConsumer.seekToBeginning(testConsumer.assignment());
        ConsumerRecords<String, ProtocolRecord> records = testConsumer.poll(Duration.ofMillis(250));
        while(records.isEmpty()) {
            // wait for the event to be produced
            records = testConsumer.poll(Duration.ofMillis(250));
        }

        assertFalse(records.isEmpty());

        CreditWalletCommand creditCommand = new CreditWalletCommand(userId, "USD", new BigDecimal("100.00"));
        CommandRecord creditCommandRecord = new CommandRecord(null,"CreditWallet", 1, objectMapper.writeValueAsBytes(creditCommand), PayloadEncoding.JSON, creditCommand.getAggregateId(), null);

        testProducer.beginTransaction();
        testProducer.send(new ProducerRecord<>(topicName, partition, creditCommandRecord.aggregateId(), creditCommandRecord));
        testProducer.commitTransaction();

        records = testConsumer.poll(Duration.ofMillis(250));
        while(records.isEmpty()) {
            // wait for the event to be produced
            records = testConsumer.poll(Duration.ofMillis(250));
        }

        assertFalse(records.isEmpty());

        // now create a command that will cause an error
        CreditWalletCommand invalidCommand = new CreditWalletCommand(userId,"USD", new BigDecimal("-100.00"));
        CommandRecord invalidCommandRecord = new CommandRecord(null,"CreditWallet", 1, objectMapper.writeValueAsBytes(invalidCommand), PayloadEncoding.JSON, invalidCommand.getAggregateId(), null);

        testProducer.beginTransaction();
        testProducer.send(new ProducerRecord<>(topicName, partition, invalidCommandRecord.aggregateId(), invalidCommandRecord));
        testProducer.commitTransaction();

        records = testConsumer.poll(Duration.ofMillis(250));
        while(records.isEmpty()) {
            // wait for the event to be produced
            records = testConsumer.poll(Duration.ofMillis(250));
        }

        // we should have no records in the state topic
        assertTrue(records.records(aggregateStatePartition).isEmpty());
        // and we should have a error event in the domain events
        assertEquals(1, records.records(domainEventsPartition).size());

        DomainEventRecord protocolRecord = (DomainEventRecord) records.records(domainEventsPartition).get(0).value();
        assertEquals("InvalidAmountError", protocolRecord.name());

        testConsumer.close();
        testProducer.close();
    }

    @Test
    @Order(4)
    public void testBatchedCommands() throws JsonProcessingException {
        // wait until the ackes controller is running
        while(!akcesController.isRunning()) {
            Thread.onSpinWait();
        }
        List<String> userIds = List.of(
                "47db2418-dd10-11ed-afa1-0242ac120002",
                "47db2418-dd10-11ed-afa1-0242ac120003",
                "47db2418-dd10-11ed-afa1-0242ac120004",
                "47db2418-dd10-11ed-afa1-0242ac120005",
                "47db2418-dd10-11ed-afa1-0242ac120006",
                "47db2418-dd10-11ed-afa1-0242ac120007",
                "47db2418-dd10-11ed-afa1-0242ac120008",
                "47db2418-dd10-11ed-afa1-0242ac120009",
                "47db2418-dd10-11ed-afa1-0242ac120010",
                "47db2418-dd10-11ed-afa1-0242ac120011");
        try (
            Producer<String, ProtocolRecord> testProducer = producerFactory.createProducer("test");
            Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test", "test")
        ) {

            // find and store the current offsets
            Map<TopicPartition, Long> endOffsets = testConsumer.endOffsets(
                    Stream.concat(
                            generateTopicPartitions("Wallet-AggregateState",3),
                            generateTopicPartitions("Wallet-DomainEvents",3))
                            .toList());

            testProducer.beginTransaction();
            for(String userId : userIds) {
                CreateWalletCommand command = new CreateWalletCommand(userId,"USD");
                CommandRecord commandRecord = new CommandRecord(null,"CreateWallet", 1, objectMapper.writeValueAsBytes(command), PayloadEncoding.JSON, command.getAggregateId(), null);
                String topicName = akcesController.resolveTopic(command.getClass());
                int partition = akcesController.resolvePartition(command.getAggregateId());
                // produce a command to create a Wallet
                testProducer.send(new ProducerRecord<>(topicName, partition, commandRecord.aggregateId(), commandRecord));
            }
            testProducer.commitTransaction();

            testConsumer.subscribe(List.of("Wallet-AggregateState","Wallet-DomainEvents"), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {

                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    partitions.forEach(partition -> testConsumer.seek(partition, endOffsets.get(partition)));
                }
            });

            ConsumerRecords<String, ProtocolRecord> records = testConsumer.poll(Duration.ofMillis(250));
            List<ProtocolRecord> allRecords = new ArrayList<>();
            while(allRecords.size() < 20) {
                records.forEach(record -> allRecords.add(record.value()));
                // wait for the events to be produced
                records = testConsumer.poll(Duration.ofMillis(250));
            }

            assertEquals(20, allRecords.size());
        }

    }

    public static Stream<TopicPartition> generateTopicPartitions(String topic, int partitions) {
        return IntStream.range(0, partitions)
                .mapToObj(i -> new TopicPartition(topic, i));
    }

}
