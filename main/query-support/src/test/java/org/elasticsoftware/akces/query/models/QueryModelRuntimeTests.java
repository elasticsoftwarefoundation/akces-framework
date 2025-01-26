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

package org.elasticsoftware.akces.query.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
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
import org.elasticsoftware.akces.query.QueryModelEventHandlerFunction;
import org.elasticsoftware.akces.query.models.account.AccountQueryModel;
import org.elasticsoftware.akces.query.models.account.AccountQueryModelState;
import org.elasticsoftware.akces.query.models.wallet.WalletQueryModel;
import org.elasticsoftware.akces.query.models.wallet.WalletQueryModelState;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.account.CreateAccountCommand;
import org.elasticsoftware.akcestest.aggregate.wallet.BalanceCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.CreateBalanceCommand;
import org.elasticsoftware.akcestest.aggregate.wallet.CreateWalletCommand;
import org.elasticsoftware.akcestest.aggregate.wallet.WalletCreatedEvent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(
        classes = AggregateServiceApplication.class,  // we need to use AggregateServiceApplication in order to load the Aggregate beeans for the test
        args = "org.elasticsoftware.akces.query.models.QueryModelTestConfiguration",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ContextConfiguration(initializers = QueryModelRuntimeTests.ContextInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
public class QueryModelRuntimeTests {
    private static final String CONFLUENT_PLATFORM_VERSION = "7.8.0";

    private static final Network network = Network.newNetwork();

    @Container
    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:" + CONFLUENT_PLATFORM_VERSION))
                    .withKraft()
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                    .withNetwork(network)
                    .withNetworkAliases("kafka");

    @Container
    private static final GenericContainer<?> schemaRegistry =
            new GenericContainer<>(DockerImageName.parse("confluentinc/cp-schema-registry:" + CONFLUENT_PLATFORM_VERSION))
                    .withNetwork(network)
                    .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:9092")
                    .withEnv("SCHEMA_REGISTRY_HOST_NAME", "localhost")
                    .withExposedPorts(8081)
                    .withNetworkAliases("schema-registry")
                    .dependsOn(kafka);

    @Inject
    @Qualifier("akcesQueryModelKafkaAdmin")
    KafkaAdmin adminClient;

    @Inject
    @Qualifier("akcesQueryModelSchemaRegistryClient")
    SchemaRegistryClient schemaRegistryClient;

    @Inject
    @Qualifier("akcesQueryModelConsumerFactory")
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

    @Inject
    AkcesQueryModelController akcesQueryModelController;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ApplicationContext applicationContext;

    public static void prepareKafka(String bootstrapServers) {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        kafkaAdmin.createOrModifyTopics(
                createCompactedTopic("Akces-Control", 3),
                createTopic("Akces-CommandResponses", 3, 604800000L),
                createCompactedTopic("Akces-GDPRKeys", 3),
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

    public static <D extends DomainEvent> void prepareDomainEventSchemas(String url, List<Class<?>> domainEventClasses) {
        SchemaRegistryClient src = new CachedSchemaRegistryClient(url, 100);
        Jackson2ObjectMapperBuilder objectMapperBuilder = new Jackson2ObjectMapperBuilder();
        objectMapperBuilder.modulesToInstall(new AkcesGDPRModule());
        objectMapperBuilder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(objectMapperBuilder.build(),
                SchemaVersion.DRAFT_7,
                OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS,
                JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(new JacksonModule());
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_FIELDS_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_METHOD_RETURN_VALUES_BY_DEFAULT);
        // we need to override the default behavior of the generator to write BigDecimal as type = number
        configBuilder.forTypesInGeneral().withTypeAttributeOverride((collectedTypeAttributes, scope, context) -> {
            if (scope.getType().getTypeName().equals("java.math.BigDecimal")) {
                JsonNode typeNode = collectedTypeAttributes.get("type");
                if (typeNode.isArray()) {
                    ((ArrayNode) collectedTypeAttributes.get("type")).set(0, "string");
                } else
                    collectedTypeAttributes.put("type", "string");
            }
        });
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator jsonSchemaGenerator = new SchemaGenerator(config);
        try {
            for (Class<?> domainEventClass : domainEventClasses) {
                DomainEventInfo info = domainEventClass.getAnnotation(DomainEventInfo.class);
                src.register("domainevents." + info.type(),
                        new JsonSchema(jsonSchemaGenerator.generateSchema(domainEventClass), List.of(), Map.of(), info.version()),
                        info.version(),
                        -1);
            }
        } catch (IOException | RestClientException e) {
            throw new ApplicationContextException("Problem populating SchemaRegistry", e);
        }
    }

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
        assertNotNull(schemaRegistryClient);
        assertNotNull(consumerFactory);
        assertNotNull(objectMapper);
        assertNotNull(walletController);
        assertNotNull(accountController);
        assertNotNull(orderProcessManagerController);
        assertNotNull(akcesClientController);
        assertNotNull(akcesQueryModelController);

        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !akcesQueryModelController.isRunning()) {
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
                !akcesQueryModelController.isRunning()) {
            Thread.onSpinWait();
        }
        assertNotNull(applicationContext);
        assertEquals(3, applicationContext.getBeansOfType(QueryModelEventHandlerFunction.class).size());
        assertNotNull(applicationContext.getBean("WalletQueryModel_qmeh_create_WalletCreated_1"));
        assertNotNull(applicationContext.getBean("WalletQueryModel_qmeh_createBalance_BalanceCreated_1"));
        assertNotNull(applicationContext.getBean("AccountQueryModel_qmeh_create_AccountCreated_1"));
        assertEquals(2, applicationContext.getBeansOfType(QueryModelRuntimeFactory.class).size());
        assertNotNull(applicationContext.getBean("WalletQueryModelQueryModelRuntime"));
        assertNotNull(applicationContext.getBean("AccountQueryModelQueryModelRuntime"));
        assertEquals(2, applicationContext.getBeansOfType(QueryModelRuntime.class).size());
    }

    @Test
    @Order(3)
    public void testWithUnknownId() throws InterruptedException, TimeoutException {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !akcesQueryModelController.isRunning()) {
            Thread.onSpinWait();
        }

        CompletableFuture<WalletQueryModelState> stateFuture = akcesQueryModelController.getHydratedState(
                WalletQueryModel.class, "unknown-id").toCompletableFuture();
        assertNotNull(stateFuture);
        ExecutionException exception = assertThrows(ExecutionException.class, stateFuture::get);
        assertInstanceOf(QueryModelIdNotFoundException.class, exception.getCause());
    }

    @Test
    @Order(4)
    public void testWithUnknownIdAgainAndInsureNotCreated() throws InterruptedException, TimeoutException {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !akcesQueryModelController.isRunning()) {
            Thread.onSpinWait();
        }

        CompletableFuture<WalletQueryModelState> stateFuture = akcesQueryModelController.getHydratedState(WalletQueryModel.class, "unknown-id")
                .toCompletableFuture();
        assertNotNull(stateFuture);
        ExecutionException exception = assertThrows(ExecutionException.class, stateFuture::get);
        assertInstanceOf(QueryModelIdNotFoundException.class, exception.getCause());
    }

    @Test
    @Order(5)
    public void testRegisteredSchemas() throws RestClientException, IOException {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !akcesQueryModelController.isRunning()) {
            Thread.onSpinWait();
        }

        // now we should have all the schemas registered
        List<ParsedSchema> registeredSchemas = schemaRegistryClient.getSchemas("domainevents.WalletCreated", false, false);
        assertFalse(registeredSchemas.isEmpty());
        assertEquals(1, schemaRegistryClient.getVersion("domainevents.WalletCreated", registeredSchemas.getFirst()));

        registeredSchemas = schemaRegistryClient.getSchemas("domainevents.AccountCreated", false, false);
        assertFalse(registeredSchemas.isEmpty());
        assertEquals(1, schemaRegistryClient.getVersion("domainevents.AccountCreated", registeredSchemas.getFirst()));
    }

    @Test
    @Order(6)
    public void testCreateAndQueryWalletQueryModel() throws ExecutionException, InterruptedException, TimeoutException {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !akcesQueryModelController.isRunning()) {
            Thread.onSpinWait();
        }

        String userId = "a28d41c4-9f9c-4708-b142-6b83768ee8f3";
        List<DomainEvent> result;

        CreateWalletCommand createWalletCommand = new CreateWalletCommand(userId, "BTC");
        result = assertDoesNotThrow(() -> akcesClientController.send("TEST_TENANT", createWalletCommand).toCompletableFuture().get(10, TimeUnit.SECONDS));
        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.size());
        assertInstanceOf(WalletCreatedEvent.class, result.getFirst());
        assertInstanceOf(BalanceCreatedEvent.class, result.getLast());

        CompletableFuture<WalletQueryModelState> stateFuture = akcesQueryModelController.getHydratedState(WalletQueryModel.class, userId)
                .toCompletableFuture();

        assertNotNull(stateFuture);
        WalletQueryModelState state = assertDoesNotThrow(() -> stateFuture.get(10, TimeUnit.SECONDS));
        assertNotNull(state);
    }

    @Test
    @Order(7)
    public void testCreateAndQueryWalletQueryModelWithMultipleConcurrentRequests() throws ExecutionException, InterruptedException, TimeoutException {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !akcesQueryModelController.isRunning()) {
            Thread.onSpinWait();
        }

        List<String> userIds = List.of(
                "0dfc7c15-2e97-43c0-81f3-52f19e177909",
                "69084006-819f-47bf-8dad-026915e70eef",
                "1deccc0d-75ce-4fdd-900d-779a088b5449",
                "1c28cd3c-9d67-4756-aa40-8d8bb60870a0",
                "21d39df6-29d2-465f-bef1-e32206a95519",
                "6873a740-a15c-4164-ad8d-101006deea22",
                "fae4298d-041a-4ad3-bef0-1903361c1408",
                "9eb4ebd4-3897-460d-a756-0615e6eb8c7e",
                "278254d9-47ee-4014-b275-1119d74d4af2",
                "fbfd596f-0a84-413c-9f98-206bc6f0be0a"
        );

        List<CompletableFuture<List<DomainEvent>>> futures = userIds.stream()
                .map(userId -> akcesClientController.send("TEST_TENANT", new CreateWalletCommand(userId, "BTC"))
                        .toCompletableFuture())
                .toList();

        CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        allOf.get(10, TimeUnit.SECONDS);

        for (CompletableFuture<List<DomainEvent>> future : futures) {
            List<DomainEvent> result = future.get();
            assertNotNull(result);
            assertEquals(2, result.size());
            assertInstanceOf(WalletCreatedEvent.class, result.get(0));
            assertInstanceOf(BalanceCreatedEvent.class, result.get(1));
        }

        List<CompletableFuture<WalletQueryModelState>> stateFutures = userIds.stream()
                .map(userId -> akcesQueryModelController.getHydratedState(WalletQueryModel.class, userId)
                        .toCompletableFuture())
                .toList();

        CompletableFuture<Void> allOfStates = CompletableFuture.allOf(stateFutures.toArray(new CompletableFuture[0]));
        allOfStates.get(10, TimeUnit.SECONDS);

        for (CompletableFuture<WalletQueryModelState> stateFuture : stateFutures) {
            WalletQueryModelState state = stateFuture.get();
            assertNotNull(state);
        }
    }

    @Test @Order(8)
    public void testAccountQueryModel() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !akcesQueryModelController.isRunning()) {
            Thread.onSpinWait();
        }

        String userId = "7ee48409-f381-4bb1-8115-0a7bbfd30e9c";
        List<DomainEvent> result;

        CreateAccountCommand createAccountCommand = new CreateAccountCommand(userId, "US", "John", "Doe", "john.doe@example.com");
        result = assertDoesNotThrow(() -> akcesClientController.send("TEST_TENANT", createAccountCommand).toCompletableFuture().get(10, TimeUnit.SECONDS));

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        assertInstanceOf(AccountCreatedEvent.class, result.getFirst());

        CompletableFuture<AccountQueryModelState> stateFuture = akcesQueryModelController.getHydratedState(AccountQueryModel.class, userId)
                .toCompletableFuture();

        assertNotNull(stateFuture);
        AccountQueryModelState state = assertDoesNotThrow(() -> stateFuture.get(10, TimeUnit.SECONDS));
        assertNotNull(state);
        assertThat(state.country()).isEqualTo("US");
        assertThat(state.firstName()).isEqualTo("John");
        assertThat(state.lastName()).isEqualTo("Doe");
        assertThat(state.email()).isEqualTo("john.doe@example.com");
    }

    @Test @Order(8)
    public void testAccountQueryModelUsingCache() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !akcesQueryModelController.isRunning()) {
            Thread.onSpinWait();
        }

        String userId = "4117b11f-3dde-4b71-b80c-fa20a12d9add";
        List<DomainEvent> result;

        CreateAccountCommand createAccountCommand = new CreateAccountCommand(userId, "US", "John", "Doe", "john.doe@example.com");
        result = assertDoesNotThrow(() -> akcesClientController.send("TEST_TENANT", createAccountCommand).toCompletableFuture().get(10, TimeUnit.SECONDS));

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        assertInstanceOf(AccountCreatedEvent.class, result.getFirst());

        CompletableFuture<AccountQueryModelState> stateFuture1 = akcesQueryModelController.getHydratedState(AccountQueryModel.class, userId)
                .toCompletableFuture();

        assertNotNull(stateFuture1);
        AccountQueryModelState state1 = assertDoesNotThrow(() -> stateFuture1.get(10, TimeUnit.SECONDS));
        assertNotNull(state1);
        assertThat(state1.country()).isEqualTo("US");
        assertThat(state1.firstName()).isEqualTo("John");
        assertThat(state1.lastName()).isEqualTo("Doe");
        assertThat(state1.email()).isEqualTo("john.doe@example.com");

        CompletableFuture<AccountQueryModelState> stateFuture2 = akcesQueryModelController.getHydratedState(AccountQueryModel.class, userId)
                .toCompletableFuture();

        assertNotNull(stateFuture2);
        AccountQueryModelState state2 = assertDoesNotThrow(() -> stateFuture2.get(10, TimeUnit.SECONDS));
        assertNotNull(state2);
        assertThat(state2.country()).isEqualTo("US");
        assertThat(state2.firstName()).isEqualTo("John");
        assertThat(state2.lastName()).isEqualTo("Doe");
        assertThat(state2.email()).isEqualTo("john.doe@example.com");

        // fetch the wallet model state
        CompletableFuture<WalletQueryModelState> walletStateFuture = akcesQueryModelController.getHydratedState(WalletQueryModel.class, userId)
                .toCompletableFuture();
        assertNotNull(walletStateFuture);
        WalletQueryModelState walletState = assertDoesNotThrow(() -> walletStateFuture.get(10, TimeUnit.SECONDS));
        assertNotNull(walletState);
        assertEquals(1, walletState.balances().size());
        assertEquals("EUR", walletState.balances().getFirst().currency());

        // and again, should come straight from the cache
        CompletableFuture<WalletQueryModelState> walletStateFuture2 = akcesQueryModelController.getHydratedState(WalletQueryModel.class, userId)
                .toCompletableFuture();
        assertNotNull(walletStateFuture2);
        WalletQueryModelState walletState2 = assertDoesNotThrow(() -> walletStateFuture2.get(10, TimeUnit.SECONDS));
        assertNotNull(walletState2);
        assertEquals(1, walletState2.balances().size());
        assertEquals("EUR", walletState2.balances().getFirst().currency());

        // now create a new BTC balance
        CompletableFuture<List<DomainEvent>> resultFuture = akcesClientController.send("TEST_TENANT", new CreateBalanceCommand(userId, "ETH"))
                .toCompletableFuture();

        result = assertDoesNotThrow(() -> akcesClientController.send("TEST_TENANT", new CreateBalanceCommand(userId, "BTC"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        assertInstanceOf(BalanceCreatedEvent.class, result.getFirst());

        CompletableFuture<WalletQueryModelState> walletStateFuture3 = akcesQueryModelController.getHydratedState(WalletQueryModel.class, userId)
                .toCompletableFuture();
        assertNotNull(walletStateFuture3);
        WalletQueryModelState walletState3 = assertDoesNotThrow(() -> walletStateFuture3.get(10, TimeUnit.SECONDS));
        assertNotNull(walletState3);
        assertEquals(2, walletState3.balances().size());
        assertEquals("EUR", walletState3.balances().getFirst().currency());
        assertEquals("BTC", walletState3.balances().getLast().currency());

    }

    public static class ContextInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            prepareDomainEventSchemas("http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081),
                    List.of(
                            WalletCreatedEvent.class,
                            BalanceCreatedEvent.class,
                            AccountCreatedEvent.class
                    ));
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
                    "akces.schemaregistry.url=http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081)
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
}
