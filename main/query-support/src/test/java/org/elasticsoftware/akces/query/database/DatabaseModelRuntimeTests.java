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

package org.elasticsoftware.akces.query.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.account.CreateAccountCommand;
import org.elasticsoftware.akcestest.aggregate.wallet.BalanceCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.WalletCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.WalletCreditedEvent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
@SpringBootTest(
        classes = AggregateServiceApplication.class,  // we need to use AggregateServiceApplication in order to load the Aggregate beeans for the test
        args = "org.elasticsoftware.akces.query.database.DatabaseModelTestConfiguration",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@EnableJpaRepositories
@ContextConfiguration(initializers = DatabaseModelRuntimeTests.ContextInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
public class DatabaseModelRuntimeTests {
    private static final String CONFLUENT_PLATFORM_VERSION = "7.8.1";

    private static final Network network = Network.newNetwork();

    @Container
    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:" + CONFLUENT_PLATFORM_VERSION))
                    .withKraft()
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                    .withNetwork(network)
                    .withNetworkAliases("kafka");



    @Container
    private static final PostgreSQLContainer<?> postgresql = new PostgreSQLContainer<>("postgres:17.4")
            .withDatabaseName("akces")
            .withUsername("akces")
            .withPassword("akces")
            .withNetwork(network)
            .withNetworkAliases("postgresql");

    @Inject
    @Qualifier("aggregateServiceKafkaAdmin")
    KafkaAdmin adminClient;

    @Inject
    @Qualifier("akcesDatabaseModelSchemaStorage")
    org.elasticsoftware.akces.schemas.storage.KafkaTopicSchemaStorage schemaStorage;

    @Inject
    @Qualifier("akcesDatabaseModelConsumerFactory")
    ConsumerFactory<String, ProtocolRecord> consumerFactory;

    @Inject
    @Qualifier("WalletAkcesController")
    AkcesAggregateController walletController;

    @Inject
    @Qualifier("AccountAkcesController")
    AkcesAggregateController accountController;

    @Inject
    @Qualifier("OrderProcessManagerAkcesController")
    AkcesAggregateController orderProcessManagerController;

    @Inject
    AkcesClientController akcesClientController;

    @Inject //@Qualifier("Defaultv1AkcesDatabaseModelController")
    AkcesDatabaseModelController defaultModelController;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ApplicationContext applicationContext;

    public static class ContextInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            // Schema registration is now automatic through KafkaTopicSchemaStorage
            try {
                prepareAggregateServiceRecords(kafka.getBootstrapServers());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "akces.rocksdb.baseDir=/tmp/akces",
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "spring.datasource.url=" + postgresql.getJdbcUrl(),
                    "spring.datasource.username=akces",
                    "spring.datasource.password=akces",
                    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
            );
        }
    }

    public static void prepareAggregateServiceRecords(String bootstrapServers) throws IOException {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        builder.modulesToInstall(new AkcesGDPRModule());
        builder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        ObjectMapper objectMapper = builder.build();
        AkcesControlRecordSerde controlSerde = new AkcesControlRecordSerde(objectMapper);
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
        try (Producer<String, AkcesControlRecord> controlProducer = new KafkaProducer<>(controlProducerProps, new StringSerializer(), controlSerde.serializer())) {
            controlProducer.initTransactions();
            AggregateServiceRecord accountServiceRecord = objectMapper.readValue("{\"aggregateName\":\"Account\",\"commandTopic\":\"Account-Commands\",\"domainEventTopic\":\"Account-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"CreateAccount\",\"version\":1,\"create\":true,\"schemaName\":\"commands.CreateAccount\"}],\"producedEvents\":[{\"typeName\":\"AccountCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.AccountCreated\"},{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"},{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"}],\"consumedEvents\":[]}", AggregateServiceRecord.class);
            AggregateServiceRecord orderProcessManagerServiceRecord = objectMapper.readValue("{\"aggregateName\":\"OrderProcessManager\",\"commandTopic\":\"OrderProcessManager-Commands\",\"domainEventTopic\":\"OrderProcessManager-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"PlaceBuyOrder\",\"version\":1,\"create\":false,\"schemaName\":\"commands.PlaceBuyOrder\"}],\"producedEvents\":[{\"typeName\":\"BuyOrderRejected\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BuyOrderRejected\"},{\"typeName\":\"BuyOrderCreated\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BuyOrderCreated\"},{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"},{\"typeName\":\"UserOrderProcessesCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.UserOrderProcessesCreated\"},{\"typeName\":\"BuyOrderPlaced\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BuyOrderPlaced\"},{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"}],\"consumedEvents\":[{\"typeName\":\"InsufficientFundsError\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.InsufficientFundsError\"},{\"typeName\":\"AccountCreated\",\"version\":1,\"create\":true,\"external\":true,\"schemaName\":\"domainevents.AccountCreated\"},{\"typeName\":\"InvalidCurrencyError\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.InvalidCurrencyError\"},{\"typeName\":\"AmountReserved\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.AmountReserved\"}]}", AggregateServiceRecord.class);
            AggregateServiceRecord walletServiceRecord = objectMapper.readValue("{\"aggregateName\":\"Wallet\",\"commandTopic\":\"Wallet-Commands\",\"domainEventTopic\":\"Wallet-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"ReserveAmount\",\"version\":1,\"create\":false,\"schemaName\":\"commands.ReserveAmount\"},{\"typeName\":\"CreateWallet\",\"version\":1,\"create\":true,\"schemaName\":\"commands.CreateWallet\"},{\"typeName\":\"CreateBalance\",\"version\":1,\"create\":false,\"schemaName\":\"commands.CreateBalance\"},{\"typeName\":\"CreditWallet\",\"version\":1,\"create\":false,\"schemaName\":\"commands.CreditWallet\"}],\"producedEvents\":[{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"},{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"},{\"typeName\":\"BalanceCreated\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BalanceCreated\"},{\"typeName\":\"AmountReserved\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AmountReserved\"},{\"typeName\":\"BalanceAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BalanceAlreadyExistsError\"},{\"typeName\":\"WalletCredited\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.WalletCredited\"},{\"typeName\":\"InsufficientFundsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.InsufficientFundsError\"},{\"typeName\":\"WalletCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.WalletCreated\"},{\"typeName\":\"InvalidCurrencyError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.InvalidCurrencyError\"},{\"typeName\":\"InvalidAmountError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.InvalidAmountError\"}],\"consumedEvents\":[{\"typeName\":\"AccountCreated\",\"version\":1,\"create\":true,\"external\":true,\"schemaName\":\"domainevents.AccountCreated\"}]}", AggregateServiceRecord.class);
            controlProducer.beginTransaction();
            for (int partition = 0; partition < 3; partition++) {
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "Account", accountServiceRecord));
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "OrderProcessManager", orderProcessManagerServiceRecord));
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "Wallet", walletServiceRecord));
            }
            controlProducer.commitTransaction();
        }
    }

    public static void prepareKafka(String bootstrapServers) {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        kafkaAdmin.createOrModifyTopics(
                createCompactedTopic("Akces-Control", 3),
                createTopic("Akces-CommandResponses", 3, 604800000L),
                createCompactedTopic("Akces-GDPRKeys", 3),
                createCompactedTopic("Akces-Schemas", 3),
                createTopic("Wallet-Commands", 3),
                createTopic("Wallet-DomainEvents", 3),
                createTopic("Account-Commands", 3),
                createTopic("Account-DomainEvents", 3),
                createTopic("OrderProcessManager-Commands", 3),
                createTopic("OrderProcessManager-DomainEvents", 3),
                createCompactedTopic("Wallet-AggregateState", 3),
                createCompactedTopic("Account-AggregateState", 3),
                createCompactedTopic("OrderProcessManager-AggregateState", 3));
    }

    // Schema registration is now handled automatically by the framework
    // through KafkaTopicSchemaStorage when schemas are first used

    private static NewTopic createTopic(String name, int numPartitions) {
        return createTopic(name, numPartitions, -1L);
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

    @BeforeAll
    @AfterAll
    public static void cleanUp() throws IOException {
        // clean up the rocksdb directory
        if (Files.exists(Paths.get("/tmp/akces"))) {
            // clean up the rocksdb directory
            Files.walk(Paths.get("/tmp/akces"))
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    @Order(1)
    void testContextLoads() {
        assertNotNull(adminClient);
        assertNotNull(schemaStorage);
        assertNotNull(consumerFactory);
        assertNotNull(objectMapper);
        assertNotNull(walletController);
        assertNotNull(accountController);
        assertNotNull(orderProcessManagerController);
        assertNotNull(akcesClientController);
        assertNotNull(defaultModelController);

        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !defaultModelController.isRunning()) {
            Thread.onSpinWait();
        }
    }

    @Test
    @Order(2)
    public void testFindBeans() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !defaultModelController.isRunning()) {
            Thread.onSpinWait();
        }
        assertNotNull(applicationContext);
        assertEquals(1, applicationContext.getBeansOfType(DatabaseModelRuntime.class).size());
    }

    @Test
    @Order(3)
    public void testCreateAccount() throws Exception {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !defaultModelController.isRunning()) {
            Thread.onSpinWait();
        }
        // Given
        String userId = "d0daa4ab-ed08-436a-884e-0da1ed7b1f05";
        CreateAccountCommand command = new CreateAccountCommand(
            userId,
            "NL",
            "John",
            "Doe",
            "john.doe@example.com"
        );

        // When
        akcesClientController.sendAndForget("Default", userId, command);

        // Sleep briefly to allow event processing
        Thread.sleep(1000);

        // Then
        // Verify database state directly using JDBC
        JdbcTemplate jdbcTemplate = applicationContext.getBean(JdbcTemplate.class);
        Map<String, Object> result = jdbcTemplate.queryForMap(
            "SELECT * FROM account WHERE user_id = ?",
            userId
        );

        assertEquals(userId, result.get("user_id"));
        assertEquals("NL", result.get("country"));
        assertEquals("John", result.get("first_name"));
        assertEquals("Doe", result.get("last_name"));
        assertEquals("john.doe@example.com", result.get("email"));
    }
}
