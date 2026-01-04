/*
 * Copyright 2022 - 2026 The Original Authors
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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.query.QueryModelEventHandlerFunction;
import org.elasticsoftware.akces.query.models.account.AccountQueryModel;
import org.elasticsoftware.akces.query.models.account.AccountQueryModelState;
import org.elasticsoftware.akces.query.models.wallet.WalletQueryModel;
import org.elasticsoftware.akces.query.models.wallet.WalletQueryModelState;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.account.CreateAccountCommand;
import org.elasticsoftware.akcestest.aggregate.wallet.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsoftware.akces.query.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(
        classes = AggregateServiceApplication.class,  // we need to use AggregateServiceApplication in order to load the Aggregate beeans for the test
        args = "org.elasticsoftware.akces.query.models.QueryModelTestConfiguration",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.elasticsoftware.akces.query.database.AkcesDatabaseModelAutoConfiguration")
@ContextConfiguration(initializers = QueryModelRuntimeTests.ContextInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
public class QueryModelRuntimeTests {
    private static final String CONFLUENT_PLATFORM_VERSION = "7.8.1";

    private static final Network network = Network.newNetwork();

    @Container
    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:" + CONFLUENT_PLATFORM_VERSION))
                    .withKraft()
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                    .withNetwork(network)
                    .withNetworkAliases("kafka");

    @Inject
    @Qualifier("akcesQueryModelKafkaAdmin")
    KafkaAdmin adminClient;

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
        assertEquals(4, applicationContext.getBeansOfType(QueryModelEventHandlerFunction.class).size());
        assertNotNull(applicationContext.getBean("WalletQueryModel_qmeh_create_WalletCreated_1"));
        assertNotNull(applicationContext.getBean("WalletQueryModel_qmeh_createBalance_BalanceCreated_1"));
        assertNotNull(applicationContext.getBean("AccountQueryModel_qmeh_create_AccountCreated_1"));
        assertNotNull(applicationContext.getBean("WalletQueryModel_qmeh_creditWallet_WalletCredited_1"));
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
                .map(userId -> (CompletableFuture<WalletQueryModelState>) akcesQueryModelController.getHydratedState(WalletQueryModel.class, userId)
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

    @Test @Order(9)
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

        result = assertDoesNotThrow(() -> akcesClientController.send("TEST_TENANT", new CreateBalanceCommand(userId, "BTC"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        assertInstanceOf(BalanceCreatedEvent.class, result.getFirst());
        assertEquals("BTC", ((BalanceCreatedEvent) result.getFirst()).currency());

        CompletableFuture<WalletQueryModelState> walletStateFuture3 = akcesQueryModelController.getHydratedState(WalletQueryModel.class, userId)
                .toCompletableFuture();
        assertNotNull(walletStateFuture3);
        WalletQueryModelState walletState3 = assertDoesNotThrow(() -> walletStateFuture3.get(10, TimeUnit.SECONDS));
        assertNotNull(walletState3);
        assertEquals(2, walletState3.balances().size());
        assertEquals("EUR", walletState3.balances().getFirst().currency());
        assertEquals("BTC", walletState3.balances().getLast().currency());

        // add an ETH balance
        result = assertDoesNotThrow(() -> akcesClientController.send("TEST_TENANT", new CreateBalanceCommand(userId, "ETH"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        assertInstanceOf(BalanceCreatedEvent.class, result.getFirst());

        CompletableFuture<WalletQueryModelState> walletStateFuture4 = akcesQueryModelController.getHydratedState(WalletQueryModel.class, userId)
                .toCompletableFuture();
        assertNotNull(walletStateFuture4);
        WalletQueryModelState walletState4 = assertDoesNotThrow(() -> walletStateFuture4.get(10, TimeUnit.SECONDS));
        assertNotNull(walletState4);
        assertEquals(3, walletState4.balances().size());
        assertEquals("EUR", walletState4.balances().get(0).currency());
        assertEquals("BTC", walletState4.balances().get(1).currency());
        assertEquals("ETH", walletState4.balances().get(2).currency());

        // Credit the EUR wallet with 1000 euros
        result = assertDoesNotThrow(() -> akcesClientController.send("TEST_TENANT", new CreditWalletCommand(userId, "EUR", new BigDecimal("1000.00")))
                .toCompletableFuture().get(10, TimeUnit.SECONDS));

        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        assertInstanceOf(WalletCreditedEvent.class, result.getFirst());
        assertEquals("EUR", ((WalletCreditedEvent) result.getFirst()).currency());
        assertEquals(new BigDecimal("1000.00"), ((WalletCreditedEvent) result.getFirst()).amount());

        CompletableFuture<WalletQueryModelState> walletStateFuture5 = akcesQueryModelController.getHydratedState(WalletQueryModel.class, userId)
                .toCompletableFuture();
        assertNotNull(walletStateFuture5);
        WalletQueryModelState walletState5 = assertDoesNotThrow(() -> walletStateFuture5.get(10, TimeUnit.SECONDS));
        assertNotNull(walletState5);
        assertEquals(3, walletState5.balances().size());
        assertEquals("EUR", walletState5.balances().getFirst().currency());
        assertEquals(new BigDecimal("1000.00"), walletState5.balances().getFirst().amount());


    }

    public static class ContextInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            // Prepare schemas by writing to Akces-Schemas topic
            prepareDomainEventSchemas(kafka.getBootstrapServers(), "org.elasticsoftware.akcestest.aggregate");
            try {
                prepareAggregateServiceRecords(kafka.getBootstrapServers());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "akces.rocksdb.baseDir=/tmp/akces",
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers()
            );
        }
    }

}
