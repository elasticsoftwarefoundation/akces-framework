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

package org.elasticsoftwarefoundation.cryptotrading;

import jakarta.inject.Inject;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftwarefoundation.cryptotrading.web.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsoftwarefoundation.cryptotrading.TestUtils.prepareKafka;

@SpringBootTest(
        classes = AggregateServiceApplication.class,
        args = {
                "org.elasticsoftwarefoundation.cryptotrading.AggregateConfig",
                "org.elasticsoftwarefoundation.cryptotrading.ClientConfig"
        },
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = CryptoTradingWebApiTest.Initializer.class)
@Testcontainers
public class CryptoTradingWebApiTest {
    private static final String CONFLUENT_PLATFORM_VERSION = "7.8.0";

    private static final Network network = Network.newNetwork();

    @Container
    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:" + CONFLUENT_PLATFORM_VERSION))
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
    @Qualifier("WalletAkcesController")
    AkcesAggregateController walletController;
    @Inject
    @Qualifier("AccountAkcesController")
    AkcesAggregateController accountController;
    @Inject
    @Qualifier("OrderProcessManagerAkcesController")
    AkcesAggregateController prderProcessManagerController;
    @Inject
    @Qualifier("CryptoMarketAkcesController")
    AkcesAggregateController cryptoMarketController;
    @Inject
    AkcesClientController akcesClientController;
    @Inject
    AccountController accountWebController;
    @Inject
    WalletController walletWebController;
    @LocalServerPort
    private int port;
    @Inject
    private WebTestClient webTestClient;

    @AfterAll
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
        assertThat(prderProcessManagerController).isNotNull();
        assertThat(akcesClientController).isNotNull();
        assertThat(cryptoMarketController).isNotNull();

        assertThat(accountWebController).isNotNull();
        assertThat(walletWebController).isNotNull();

        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !prderProcessManagerController.isRunning() ||
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
                !prderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        webTestClient.post()
                .uri("/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(String.class)
                .value(userId -> assertThat(userId).isNotNull());
    }

    @Test
    void testCreateAccountAndCreditWallet() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !prderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        webTestClient.post()
                .uri("/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(String.class)
                .value(userId -> {
                    assertThat(userId).isNotNull();

                    // credit the wallet for this user id with 1 BTC
                    CreditWalletInput creditInput = new CreditWalletInput("EUR", new BigDecimal("1.0"));
                    webTestClient.post()
                            .uri("/wallets/" + userId + "/credit")
                            .bodyValue(creditInput)
                            .exchange()
                            .expectStatus().is2xxSuccessful()
                            .expectBody(CreditWalletOutput.class)
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
                !prderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        webTestClient.post()
                .uri("/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(String.class)
                .value(userId -> {
                    assertThat(userId).isNotNull();

                    // credit the wallet for this user id with 1 ETH
                    CreditWalletInput creditInput = new CreditWalletInput("ETH", new BigDecimal("1.0"));
                    webTestClient.post()
                            .uri("/wallets/" + userId + "/credit")
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
                !prderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        webTestClient.post()
                .uri("/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(String.class)
                .value(userId -> {
                    assertThat(userId).isNotNull();

                    // add a EUR balance to the wallet for this user id
                    CreateBalanceInput createBalanceInput = new CreateBalanceInput("BTC");
                    webTestClient.post()
                            .uri("/wallets/" + userId + "/balances")
                            .bodyValue(createBalanceInput)
                            .exchange()
                            .expectStatus().is2xxSuccessful();
                });
    }

    public static class Initializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "akces.rocksdb.baseDir=/tmp/akces",
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrapServers=" + kafka.getBootstrapServers(),
                    "kafka.schemaregistry.url=http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081)
            );
        }
    }
}
