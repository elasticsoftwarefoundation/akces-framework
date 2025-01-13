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
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.query.QueryModelEventHandlerFunction;
import org.elasticsoftware.akces.query.models.wallet.WalletQueryModel;
import org.elasticsoftware.akces.query.models.wallet.WalletQueryModelState;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akcestest.aggregate.wallet.BalanceCreatedEvent;
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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
//@SpringBootTest(classes = QueryModelTestConfiguration.class, properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@SpringBootTest(
        classes = AggregateServiceApplication.class,
        args = "org.elasticsoftware.akces.query.models.QueryModelTestConfiguration",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ContextConfiguration(initializers = QueryModelRuntimeTests.ContextInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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

    //    @Inject @Qualifier("WalletQueryModelQueryModelRuntime")
//    QueryModelRuntime<WalletQueryModelState> walletQueryModelRuntime;
    @Inject
    ApplicationContext applicationContext;

    public static class ContextInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            prepareDomainEventSchemas("http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081),
                    List.of(
                            WalletCreatedEvent.class,
                            BalanceCreatedEvent.class
                    ));
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "akces.rocksdb.baseDir=/tmp/akces",
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "kafka.schemaregistry.url=http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081)
            );
        }
    }

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
            for(Class<?> domainEventClass : domainEventClasses) {
                DomainEventInfo info = domainEventClass.getAnnotation(DomainEventInfo.class);
                src.register("domainevents."+info.type(),
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
        assertNotNull(applicationContext);
        assertEquals(2, applicationContext.getBeansOfType(QueryModelEventHandlerFunction.class).size());
        assertNotNull(applicationContext.getBean("WalletQueryModel_qmeh_create_WalletCreated_1"));
        assertNotNull(applicationContext.getBean("WalletQueryModel_qmeh_createBalance_BalanceCreated_1"));
        assertEquals(1, applicationContext.getBeansOfType(QueryModelRuntimeFactory.class).size());
        assertNotNull(applicationContext.getBean("WalletQueryModelQueryModelRuntime"));
        assertEquals(1, applicationContext.getBeansOfType(QueryModelRuntime.class).size());
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
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
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
        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    }

    @Test @Order(5)
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
        assertEquals(1,schemaRegistryClient.getVersion("domainevents.WalletCreated", registeredSchemas.getFirst()));

        registeredSchemas = schemaRegistryClient.getSchemas("domainevents.AccountCreated", false, false);
        assertFalse(registeredSchemas.isEmpty());
        assertEquals(1,schemaRegistryClient.getVersion("domainevents.AccountCreated", registeredSchemas.getFirst()));
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
}
