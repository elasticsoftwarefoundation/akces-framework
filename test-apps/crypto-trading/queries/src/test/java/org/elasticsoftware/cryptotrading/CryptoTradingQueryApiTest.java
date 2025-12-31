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

package org.elasticsoftware.cryptotrading;

import jakarta.inject.Inject;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftware.cryptotrading.query.jdbc.CryptoMarketRepository;
import org.elasticsoftware.cryptotrading.web.AccountCommandController;
import org.elasticsoftware.cryptotrading.web.AccountQueryController;
import org.elasticsoftware.cryptotrading.web.CryptoMarketsQueryController;
import org.elasticsoftware.cryptotrading.web.WalletCommandController;
import org.elasticsoftware.cryptotrading.web.dto.*;
import org.elasticsoftware.cryptotrading.web.errors.ErrorEventResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.PropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsoftware.cryptotrading.TestUtils.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        classes = AggregateServiceApplication.class,
        args = {
                "org.elasticsoftware.cryptotrading.AggregateConfig",
                "org.elasticsoftware.cryptotrading.ClientConfig"
        },
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "akces.client.domainEventsPackage=org.elasticsoftware.cryptotrading.aggregates",
                "spring.autoconfigure.exclude=org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                "spring.main.allow-bean-definition-overriding=true"
        }
)
@AutoConfigureWebTestClient
@PropertySource("classpath:akces-aggregateservice.properties")
@ContextConfiguration(initializers = CryptoTradingQueryApiTest.Initializer.class)
@Testcontainers
@DirtiesContext
public class CryptoTradingQueryApiTest {
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
            .withDatabaseName("cryptotrading")
            .withUsername("akces")
            .withPassword("akces")
            .withNetwork(network)
            .withNetworkAliases("postgresql");

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
    @Qualifier("CryptoMarketAkcesController")
    AkcesAggregateController cryptoMarketController;
    @Inject
    AkcesClientController akcesClientController;
    @Inject
    AccountCommandController accountWebController;
    @Inject
    WalletCommandController walletWebController;
    @Inject
    AccountQueryController accountQueryController;
    @LocalServerPort
    private int port;
    @Inject
    private WebTestClient webTestClient;
    @Inject
    private CryptoMarketRepository cryptoMarketRepository;
    @Inject
    private CryptoMarketsQueryController cryptoMarketsQueryController;

    @AfterAll
    @BeforeAll
    public static void cleanUp() throws IOException {
        if (Files.exists(Paths.get("/tmp/akces"))) {
            // clean up the rocksdb directory
            Files.walk(Paths.get("/tmp/akces"))
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void contextLoads() {
        assertThat(walletController).isNotNull();
        assertThat(accountController).isNotNull();
        assertThat(orderProcessManagerController).isNotNull();
        assertThat(akcesClientController).isNotNull();
        assertThat(cryptoMarketController).isNotNull();

        assertThat(accountWebController).isNotNull();
        assertThat(walletWebController).isNotNull();
        assertThat(accountQueryController).isNotNull();
        assertThat(cryptoMarketsQueryController).isNotNull();

        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }
    }

    @Test
    void healthReadinessEndpointShouldBeEnabled() {
        webTestClient.get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(response -> assertThat(response).contains("{\"status\":\"UP\"}"));
    }

    @Test
    void healthLivenessEndpointShouldBeEnabled() {
        webTestClient.get()
                .uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(response -> assertThat(response).contains("{\"status\":\"UP\"}"));
    }

    @Test
    void testCreateAccount() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(accountOutput -> {
                    assertThat(accountOutput).isNotNull();
                    assertThat(accountOutput.userId()).isNotNull();
                    assertThat(accountOutput.country()).isEqualTo("NL");
                    assertThat(accountOutput.firstName()).isEqualTo("John");
                    assertThat(accountOutput.lastName()).isEqualTo("Doe");
                    assertThat(accountOutput.email()).isEqualTo("john.doe@example.com");
                });
    }

    @Test
    void testCreateAccountAndCreditWallet() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(accountOutput -> {
                    assertThat(accountOutput.userId()).isNotNull();

                    // credit the wallet for this user id with 1 BTC
                    CreditWalletInput creditInput = new CreditWalletInput(new BigDecimal("1.0"));
                    webTestClient.post()
                            .uri("/v1/accounts/" + accountOutput.userId() + "/wallet/balances/EUR/credit")
                            .bodyValue(creditInput)
                            .exchange()
                            .expectStatus().is2xxSuccessful()
                            .expectBody(BalanceOutput.class)
                            .value(creditOutput -> {
                                assertThat(creditOutput).isNotNull();
                                assertThat(creditOutput.amount()).isEqualByComparingTo("1.0");
                                assertThat(creditOutput.currency()).isEqualTo("EUR");
                            });
                });
    }

    @Test
    void testCreateAccountAndCreditWalletWithoutBalance() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(accountOutput -> {
                    assertThat(accountOutput.userId()).isNotNull();

                    // credit the wallet for this user id with 1 ETH
                    CreditWalletInput creditInput = new CreditWalletInput(new BigDecimal("1.0"));
                    webTestClient.post()
                            .uri("/v1/accounts/" + accountOutput.userId() + "/wallet/balances/ETH/credit")
                            .bodyValue(creditInput)
                            .exchange()
                            .expectStatus().is4xxClientError()
                            .expectBody(ErrorEventResponse.class)
                            .value(response -> {
                                assertThat(response).isNotNull();
                                assertThat(response.eventType()).isEqualTo("InvalidCryptoCurrencyError");
                            });
                });
    }

    @Test
    void testCreateAccountAndAddBtcBalance() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(accountOutput -> {
                    assertThat(accountOutput.userId()).isNotNull();

                    // add a BTC balance to the wallet for this user id
                    CreateBalanceInput createBalanceInput = new CreateBalanceInput("BTC");
                    webTestClient.post()
                            .uri("/v1/accounts/" + accountOutput.userId() + "/wallet/balances")
                            .bodyValue(createBalanceInput)
                            .exchange()
                            .expectStatus().is2xxSuccessful();
                });
    }

    @Test
    void testGetAccount() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        AccountInput accountInput = new AccountInput("US", "John", "Doe", "john.doe@example.com");
        webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(accountOutput -> {
                    assertThat(accountOutput.userId()).isNotNull();

                    // retrieve the account details
                    webTestClient.get()
                            .uri("/v1/accounts/" + accountOutput.userId())
                            .exchange()
                            .expectStatus().is2xxSuccessful()
                            .expectBody(AccountOutput.class)
                            .value(retrievedAccount -> {
                                assertThat(retrievedAccount).isNotNull();
                                assertThat(retrievedAccount.userId()).isEqualTo(accountOutput.userId());
                                assertThat(retrievedAccount.country()).isEqualTo("US");
                                assertThat(retrievedAccount.firstName()).isEqualTo("John");
                                assertThat(retrievedAccount.lastName()).isEqualTo("Doe");
                                assertThat(retrievedAccount.email()).isEqualTo("john.doe@example.com");
                            });
                });
    }

    @Test
    void testInvalidApiVersion() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }
        webTestClient.get()
                .uri("/v13/accounts/invalid-id")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void testCryptoMarkets() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        // see if we have any crypto markets in the database
        while(cryptoMarketRepository.count() == 0) {
            Thread.onSpinWait();
        }

        assertNotNull(cryptoMarketRepository.findById("BTC-EUR").orElse(null));
    }

    @Test
    void testCryptoMarket() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        // Wait until crypto markets are available in the database
        while(cryptoMarketRepository.count() == 0) {
            Thread.onSpinWait();
        }

        // Test retrieving a specific market by ID
        webTestClient.get()
                .uri("/v1/markets/BTC-EUR")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("BTC-EUR")
                .jsonPath("$.baseCrypto").isEqualTo("BTC")
                .jsonPath("$.quoteCrypto").isEqualTo("EUR");
    }

    @Test
    void testPlaceBuyOrder() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        // Create account and credit EUR balance
        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        AccountOutput accountOutput = webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(accountOutput).isNotNull();
        String userId = accountOutput.userId();

        // Credit EUR balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances/EUR/credit")
                .bodyValue(new CreditWalletInput(new BigDecimal("10000.0")))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Add BTC balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances")
                .bodyValue(new CreateBalanceInput("BTC"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Place buy order
        BuyOrderInput buyOrderInput = new BuyOrderInput("BTC-EUR", new BigDecimal("1000"), "client-ref-1");

        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/orders/buy")
                .bodyValue(buyOrderInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(OrderOutput.class)
                .value(orderOutput -> {
                    assertThat(orderOutput).isNotNull();
                    assertThat(orderOutput.orderId()).isNotNull();
                });
    }

    @Test
    void testGetOpenBuyOrders() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        // Create account and credit EUR balance
        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        AccountOutput accountOutput = webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(accountOutput).isNotNull();
        String userId = accountOutput.userId();

        // Credit EUR balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances/EUR/credit")
                .bodyValue(new CreditWalletInput(new BigDecimal("10000.0")))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Add BTC balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances")
                .bodyValue(new CreateBalanceInput("BTC"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Place first buy order
        BuyOrderInput buyOrderInput1 = new BuyOrderInput("BTC-EUR", new BigDecimal("1000"), "client-ref-1");
        OrderOutput orderOutput1 = webTestClient.post()
                .uri("/v1/accounts/" + userId + "/orders/buy")
                .bodyValue(buyOrderInput1)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(OrderOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(orderOutput1).isNotNull();
        assertThat(orderOutput1.orderId()).isNotNull();

        // Place second buy order
        BuyOrderInput buyOrderInput2 = new BuyOrderInput("BTC-EUR", new BigDecimal("500"), "client-ref-2");
        OrderOutput orderOutput2 = webTestClient.post()
                .uri("/v1/accounts/" + userId + "/orders/buy")
                .bodyValue(buyOrderInput2)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(OrderOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(orderOutput2).isNotNull();
        assertThat(orderOutput2.orderId()).isNotNull();

        // Get open orders
        webTestClient.get()
                .uri("/v1/accounts/" + userId + "/orders")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.openBuyOrders").isArray()
                .jsonPath("$.openBuyOrders.length()").isEqualTo(2)
                .jsonPath("$.openBuyOrders[0].orderId").exists()
                .jsonPath("$.openBuyOrders[0].market.id").isEqualTo("BTC-EUR")
                .jsonPath("$.openBuyOrders[0].amount").isEqualTo("1000")
                .jsonPath("$.openBuyOrders[0].clientReference").isEqualTo("client-ref-1")
                .jsonPath("$.openBuyOrders[0].state").exists()
                .jsonPath("$.openBuyOrders[1].orderId").exists()
                .jsonPath("$.openBuyOrders[1].market.id").isEqualTo("BTC-EUR")
                .jsonPath("$.openBuyOrders[1].amount").isEqualTo("500")
                .jsonPath("$.openBuyOrders[1].clientReference").isEqualTo("client-ref-2")
                .jsonPath("$.openBuyOrders[1].state").exists();
    }

    @Test
    void testGetOrderById() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        // Create account and credit EUR balance
        AccountInput accountInput = new AccountInput("NL", "Jane", "Smith", "jane.smith@example.com");
        AccountOutput accountOutput = webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(accountOutput).isNotNull();
        String userId = accountOutput.userId();

        // Credit EUR balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances/EUR/credit")
                .bodyValue(new CreditWalletInput(new BigDecimal("5000.0")))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Add BTC balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances")
                .bodyValue(new CreateBalanceInput("BTC"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Place buy order
        BuyOrderInput buyOrderInput = new BuyOrderInput("BTC-EUR", new BigDecimal("750"), "test-ref-123");
        OrderOutput orderOutput = webTestClient.post()
                .uri("/v1/accounts/" + userId + "/orders/buy")
                .bodyValue(buyOrderInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(OrderOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(orderOutput).isNotNull();
        assertThat(orderOutput.orderId()).isNotNull();
        String orderId = orderOutput.orderId();

        // Get order by ID
        webTestClient.get()
                .uri("/v1/accounts/" + userId + "/orders/" + orderId)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.orderId").isEqualTo(orderId)
                .jsonPath("$.market.id").isEqualTo("BTC-EUR")
                .jsonPath("$.amount").isEqualTo("750")
                .jsonPath("$.clientReference").isEqualTo("test-ref-123")
                .jsonPath("$.state").exists();

        // Test getting non-existent order
        webTestClient.get()
                .uri("/v1/accounts/" + userId + "/orders/non-existent-order-id")
                .exchange()
                .expectStatus().isNotFound();
    }


    public static class Initializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            prepareDomainEventSchemas(kafka.getBootstrapServers(), "org.elasticsoftware.cryptotrading.aggregates");
            prepareCommandSchemas(kafka.getBootstrapServers(), "org.elasticsoftware.cryptotrading.aggregates");
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
                    "spring.datasource.password=akces"
            );
        }
    }

    @Test
    void testPlaceSellOrder() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        // Create account and credit BTC balance
        AccountInput accountInput = new AccountInput("NL", "Jane", "Doe", "jane.doe@example.com");
        AccountOutput accountOutput = webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(accountOutput).isNotNull();
        String userId = accountOutput.userId();

        // Add BTC balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances")
                .bodyValue(new CreateBalanceInput("BTC"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Credit BTC balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances/BTC/credit")
                .bodyValue(new CreditWalletInput(new BigDecimal("1.5")))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Add EUR balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances")
                .bodyValue(new CreateBalanceInput("EUR"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Place sell order
        SellOrderInput sellOrderInput = new SellOrderInput("BTC-EUR", new BigDecimal("0.5"), "sell-client-ref-1");

        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/orders/sell")
                .bodyValue(sellOrderInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(OrderOutput.class)
                .value(orderOutput -> {
                    assertThat(orderOutput).isNotNull();
                    assertThat(orderOutput.orderId()).isNotNull();
                    assertThat(orderOutput.market().id()).isEqualTo("BTC-EUR");
                    assertThat(orderOutput.amount().toString()).isEqualTo("0.5");
                });
    }

    @Test
    void testGetOpenSellOrders() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        // Create account and credit BTC balance
        AccountInput accountInput = new AccountInput("NL", "Jane", "Doe", "jane.doe@example.com");
        AccountOutput accountOutput = webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(accountOutput).isNotNull();
        String userId = accountOutput.userId();

        // Add BTC balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances")
                .bodyValue(new CreateBalanceInput("BTC"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Credit BTC balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances/BTC/credit")
                .bodyValue(new CreditWalletInput(new BigDecimal("2.0")))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Add EUR balance
        webTestClient.post()
                .uri("/v1/accounts/" + userId + "/wallet/balances")
                .bodyValue(new CreateBalanceInput("EUR"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Place first sell order
        SellOrderInput sellOrderInput1 = new SellOrderInput("BTC-EUR", new BigDecimal("0.5"), "sell-ref-1");
        OrderOutput orderOutput1 = webTestClient.post()
                .uri("/v1/accounts/" + userId + "/orders/sell")
                .bodyValue(sellOrderInput1)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(OrderOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(orderOutput1).isNotNull();
        assertThat(orderOutput1.orderId()).isNotNull();

        // Place second sell order
        SellOrderInput sellOrderInput2 = new SellOrderInput("BTC-EUR", new BigDecimal("0.3"), "sell-ref-2");
        OrderOutput orderOutput2 = webTestClient.post()
                .uri("/v1/accounts/" + userId + "/orders/sell")
                .bodyValue(sellOrderInput2)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(OrderOutput.class)
                .returnResult()
                .getResponseBody();

        assertThat(orderOutput2).isNotNull();
        assertThat(orderOutput2.orderId()).isNotNull();

        // Get open orders
        webTestClient.get()
                .uri("/v1/accounts/" + userId + "/orders")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.openSellOrders").isArray()
                .jsonPath("$.openSellOrders.length()").isEqualTo(2)
                .jsonPath("$.openSellOrders[0].orderId").exists()
                .jsonPath("$.openSellOrders[0].market.id").isEqualTo("BTC-EUR")
                .jsonPath("$.openSellOrders[0].quantity").isEqualTo("0.5")
                .jsonPath("$.openSellOrders[0].clientReference").isEqualTo("sell-ref-1")
                .jsonPath("$.openSellOrders[0].state").exists()
                .jsonPath("$.openSellOrders[1].orderId").exists()
                .jsonPath("$.openSellOrders[1].market.id").isEqualTo("BTC-EUR")
                .jsonPath("$.openSellOrders[1].quantity").isEqualTo("0.3")
                .jsonPath("$.openSellOrders[1].clientReference").isEqualTo("sell-ref-2")
                .jsonPath("$.openSellOrders[1].state").exists();
    }
}
