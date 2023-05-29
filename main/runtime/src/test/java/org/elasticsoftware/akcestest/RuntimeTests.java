/*
 * Copyright 2022 - 2023 The Original Authors
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

package org.elasticsoftware.akcestest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import jakarta.inject.Inject;
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
import org.elasticsoftware.akces.AkcesController;
import org.elasticsoftware.akces.protocol.*;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.account.AccountState;
import org.elasticsoftware.akcestest.aggregate.account.CreateAccountCommand;
import org.elasticsoftware.akcestest.aggregate.wallet.CreateWalletCommand;
import org.elasticsoftware.akcestest.aggregate.wallet.CreditWalletCommand;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.control.CommandServiceCommandType;
import org.elasticsoftware.akces.control.CommandServiceDomainEventType;
import org.elasticsoftware.akces.control.CommandServiceRecord;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.elasticsoftware.akcestest.TestUtils.prepareExternalSchemas;
import static org.elasticsoftware.akcestest.TestUtils.prepareKafka;
import static org.elasticsoftware.akces.kafka.PartitionUtils.COMMANDS_SUFFIX;
import static org.elasticsoftware.akces.kafka.PartitionUtils.DOMAINEVENTS_SUFFIX;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = RuntimeConfiguration.class, properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ContextConfiguration(initializers = RuntimeTests.DataSourceInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RuntimeTests  {

    private static final String CONFLUENT_PLATFORM_VERSION = "7.4.0";

    private static final Network network = Network.newNetwork();

    @Container
    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:"+CONFLUENT_PLATFORM_VERSION))
                    .withNetwork(network)
                    .withNetworkAliases("kafka");

    @Container
    private static final GenericContainer<?> schemaRegistry =
            new GenericContainer<>(DockerImageName.parse("confluentinc/cp-schema-registry:"+CONFLUENT_PLATFORM_VERSION))
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

    @Inject @Qualifier("WalletAkcesController")
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
            prepareExternalSchemas("http://"+schemaRegistry.getHost()+":"+schemaRegistry.getMappedPort(8081), List.of(AccountCreatedEvent.class));
            //prepareExternalServices(kafka.getBootstrapServers());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrapServers="+kafka.getBootstrapServers(),
                    "kafka.schemaregistry.url=http://"+schemaRegistry.getHost()+":"+schemaRegistry.getMappedPort(8081)
            );
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
            while(allRecords.size() < 40) {
                records.forEach(record -> allRecords.add(record.value()));
                // wait for the events to be produced
                records = testConsumer.poll(Duration.ofMillis(250));
            }

            assertEquals(40, allRecords.size());
        }

    }

    @Test
    @Order(5)
    public void testCreateViaExternalDomainEvent() throws JsonProcessingException {
        // wait until the ackes controller is running
        while(!akcesController.isRunning()) {
            Thread.onSpinWait();
        }

        String userId = "47db2418-dd10-11ed-afa1-0242ac120012";

        try (
                Producer<String, ProtocolRecord> testProducer = producerFactory.createProducer("test");
                Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test", "test")
        ) {

            // find and store the current offsets
            Map<TopicPartition, Long> endOffsets = testConsumer.endOffsets(
                    Stream.concat(
                                    generateTopicPartitions("Wallet-AggregateState", 3),
                                    generateTopicPartitions("Wallet-DomainEvents", 3))
                            .toList());

            testProducer.beginTransaction();
            CreateAccountCommand command = new CreateAccountCommand(userId,"NL", "Fahim","Zuijderwijk", "FahimZuijderwijk@jourrapide.com");
            CommandRecord commandRecord = new CommandRecord(
                    null,
                    "CreateAccount",
                    1,
                    objectMapper.writeValueAsBytes(command),
                    PayloadEncoding.JSON,
                    command.getAggregateId(),
                    null);
            String topicName = akcesController.resolveTopic(command.getClass());
            int partition = akcesController.resolvePartition(command.getAggregateId());
            // produce a command to create an Account
            testProducer.send(new ProducerRecord<>(topicName, partition, commandRecord.aggregateId(), commandRecord));
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
            while(allRecords.size() < 4) {
                records.forEach(record -> allRecords.add(record.value()));
                // wait for the events to be produced
                records = testConsumer.poll(Duration.ofMillis(250));
            }

            assertEquals(4, allRecords.size());
        }
    }

    @Test
    @Order(6)
    public void testGDPREncryption() throws IOException {
        // wait until the ackes controller is running
        while(!akcesController.isRunning()) {
            Thread.onSpinWait();
        }

        String userId = "ca7c8e7f-d1a3-46ba-b400-f543d0c04bc6";

        try (
                Producer<String, ProtocolRecord> testProducer = producerFactory.createProducer("test");
                Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test", "test")
        ) {

            // find and store the current offsets
            Map<TopicPartition, Long> endOffsets = testConsumer.endOffsets(
                    Stream.concat(
                                    generateTopicPartitions("Account-AggregateState", 3),
                                    generateTopicPartitions("Account-DomainEvents", 3))
                            .toList());

            testProducer.beginTransaction();
            CreateAccountCommand command = new CreateAccountCommand(userId,"NL", "Fahim","Zuijderwijk", "FahimZuijderwijk@jourrapide.com");
            CommandRecord commandRecord = new CommandRecord(
                    null,
                    "CreateAccount",
                    1,
                    objectMapper.writeValueAsBytes(command),
                    PayloadEncoding.JSON,
                    command.getAggregateId(),
                    null);
            String topicName = akcesController.resolveTopic(command.getClass());
            int partition = akcesController.resolvePartition(command.getAggregateId());
            // produce a command to create an Account
            testProducer.send(new ProducerRecord<>(topicName, partition, commandRecord.aggregateId(), commandRecord));
            testProducer.commitTransaction();

            testConsumer.subscribe(List.of("Account-AggregateState","Account-DomainEvents"), new ConsumerRebalanceListener() {
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
            while(allRecords.size() < 2) {
                records.forEach(record -> allRecords.add(record.value()));
                // wait for the events to be produced
                records = testConsumer.poll(Duration.ofMillis(250));
            }

            assertEquals(2, allRecords.size());
            // now see if the domain event and state are encrypted
            assertTrue(allRecords.get(1) instanceof DomainEventRecord);
            DomainEventRecord domainEventRecord = (DomainEventRecord) allRecords.get(1);
            AccountCreatedEvent accountCreatedEvent = objectMapper.readValue(domainEventRecord.payload(), AccountCreatedEvent.class);
            assertNotEquals("Fahim", accountCreatedEvent.firstName());
            assertNotEquals("Zuijderwijk", accountCreatedEvent.lastName());
            assertNotEquals("FahimZuijderwijk@jourrapide.com", accountCreatedEvent.email());
            AggregateStateRecord stateRecord = (AggregateStateRecord) allRecords.get(0);
            AccountState accountState = objectMapper.readValue(stateRecord.payload(), AccountState.class);
            assertNotEquals("Fahim", accountState.firstName());
            assertNotEquals("Zuijderwijk", accountState.lastName());
            assertNotEquals("FahimZuijderwijk@jourrapide.com", accountState.email());
        }
    }

    public static Stream<TopicPartition> generateTopicPartitions(String topic, int partitions) {
        return IntStream.range(0, partitions)
                .mapToObj(i -> new TopicPartition(topic, i));
    }

}
