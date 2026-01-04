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

package org.elasticsoftware.cryptotrading;

import jakarta.inject.Inject;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.cryptotrading.web.dto.AccountInput;
import org.elasticsoftware.cryptotrading.web.dto.CreditWalletInput;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.core.ConsumerFactory;
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

import static org.elasticsoftware.cryptotrading.TestUtils.*;

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
@ContextConfiguration(initializers = ControllerBigDecimalSerializationTest.Initializer.class)
@Testcontainers
@DirtiesContext
public class ControllerBigDecimalSerializationTest {
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
    private WebTestClient webTestClient;
    @Inject
    @Qualifier("aggregateServiceControlConsumerFactory")
    ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory;

    public static class Initializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            prepareDomainEventSchemas(kafka.getBootstrapServers(), "org.elasticsoftware.cryptotrading.aggregates");
            prepareCommandSchemas(kafka.getBootstrapServers(), "org.elasticsoftware.cryptotrading.aggregates");
            // This is still required for the CryptoMarketsService to function properly
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
    void testQueryControllerSerializesBigDecimalAsString() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }
        ensureAggregateServiceRecordsExist(controlConsumerFactory);

        // Create account and credit wallet
        AccountInput accountInput = new AccountInput("BE", "Query", "Tester", "query.test@example.com");
        webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.userId").exists()
                .jsonPath("$.userId").value(userId -> {
                    // Credit wallet with precise BigDecimal value
                    CreditWalletInput creditInput = new CreditWalletInput(new BigDecimal("987.654321"));
                    webTestClient.post()
                            .uri("/v1/accounts/" + userId + "/wallet/balances/EUR/credit")
                            .bodyValue(creditInput)
                            .exchange()
                            .expectStatus().is2xxSuccessful();

                    // Give query model time to update
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    // Query the wallet and verify BigDecimal serialization
                    webTestClient.get()
                            .uri("/v1/accounts/" + userId + "/wallet")
                            .exchange()
                            .expectStatus().isOk()
                            .expectBody()
                            .jsonPath("$.walletId").isEqualTo(userId.toString())
                            .jsonPath("$.balances[0].amount").isEqualTo("987.654321")
                            .consumeWith(response -> {
                                String body = new String(response.getResponseBody());
                                // Ensure BigDecimal is serialized as string in query response
                                assert body.contains("\"amount\":\"987.654321\"") :
                                    "Expected amount to be serialized as string in query response, but got: " + body;
                                // Ensure NOT serialized as number
                                assert !body.contains("\"amount\":987.654321") :
                                    "Amount should not be serialized as number in query response";
                            });
                });
    }

    @Test
    void testQueryControllerSerializesBigDecimalWithoutScientificNotation() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }
        ensureAggregateServiceRecordsExist(controlConsumerFactory);

        // Create account and credit wallet with very small value
        AccountInput accountInput = new AccountInput("FR", "Small", "Value", "small.value@example.com");
        webTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.userId").exists()
                .jsonPath("$.userId").value(userId -> {
                    // Credit with very small value that could trigger scientific notation
                    CreditWalletInput creditInput = new CreditWalletInput(new BigDecimal("0.00000123"));
                    webTestClient.post()
                            .uri("/v1/accounts/" + userId + "/wallet/balances/EUR/credit")
                            .bodyValue(creditInput)
                            .exchange()
                            .expectStatus().is2xxSuccessful();

                    // Give query model time to update
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    // Query and verify no scientific notation is used
                    webTestClient.get()
                            .uri("/v1/accounts/" + userId + "/wallet")
                            .exchange()
                            .expectStatus().isOk()
                            .expectBody()
                            .jsonPath("$.balances[0].amount").isEqualTo("0.00000123")
                            .consumeWith(response -> {
                                String body = new String(response.getResponseBody());
                                // Ensure no scientific notation
                                assert !body.contains("E-") && !body.contains("e-") && !body.contains("E+") && !body.contains("e+") :
                                    "BigDecimal should not use scientific notation in query response, but got: " + body;
                            });
                });
    }
}
