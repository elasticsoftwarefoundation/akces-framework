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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.cryptotrading.aggregates.account.commands.CreateAccountCommand;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.commands.CreateCryptoMarketCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.data.CryptoMarket;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.PlaceBuyOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.CreateBalanceCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.CreditWalletCommand;
import org.elasticsoftware.cryptotrading.services.coinbase.CoinbaseService;
import org.elasticsoftware.cryptotrading.services.coinbase.Product;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsoftware.cryptotrading.TestUtils.prepareAggregateServiceRecords;
import static org.elasticsoftware.cryptotrading.TestUtils.prepareKafka;

@SpringBootTest(
        classes = AggregateServiceApplication.class,
        args = "org.elasticsoftware.cryptotrading.AggregateConfig",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = CryptoTradingApplicationTest.Initializer.class)
@Testcontainers
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CryptoTradingApplicationTest {
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
    private static final GenericContainer<?> schemaRegistry =
            new GenericContainer<>(DockerImageName.parse("confluentinc/cp-schema-registry:" + CONFLUENT_PLATFORM_VERSION))
                    .withNetwork(network)
                    .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:9092")
                    .withEnv("SCHEMA_REGISTRY_HOST_NAME", "localhost")
                    .withExposedPorts(8081)
                    .withNetworkAliases("schema-registry")
                    .dependsOn(kafka);
    private final static String counterPartyId = "337f335d-caf1-4f85-9440-6bc3c0ebbb77";
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
    CoinbaseService coinbaseService;
    @Inject
    @Qualifier("aggregateServiceConsumerFactory")
    ConsumerFactory<String, ProtocolRecord> consumerFactory;
    @Inject
    @Qualifier("aggregateServiceControlConsumerFactory")
    ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory;
    @LocalServerPort
    private int port;
    @Inject
    private WebTestClient webTestClient;
    @Inject
    private ObjectMapper objectMapper;

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
    @Order(1)
    void contextLoads() throws IOException {
        assertThat(walletController).isNotNull();
        assertThat(accountController).isNotNull();
        assertThat(prderProcessManagerController).isNotNull();
        assertThat(akcesClientController).isNotNull();
        assertThat(cryptoMarketController).isNotNull();

        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !prderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }

        try (Consumer<String, AkcesControlRecord> controlConsumer = controlConsumerFactory.createConsumer("Test-AkcesControl", "test-akces-control")) {
            TopicPartition controlPartition = new TopicPartition("Akces-Control", 0);
            controlConsumer.assign(List.of(controlPartition));
            controlConsumer.seekToBeginning(controlConsumer.assignment());
            Map<TopicPartition, Long> endOffsets = controlConsumer.endOffsets(controlConsumer.assignment());

            Map<String, AggregateServiceRecord> serviceRecords = new HashMap<>();

            while (endOffsets.getOrDefault(controlPartition, 0L) > controlConsumer.position(controlPartition)) {
                ConsumerRecords<String, AkcesControlRecord> controlRecords = controlConsumer.poll(Duration.ofMillis(1000));
                if (!controlRecords.isEmpty()) {
                    for (ConsumerRecord<String, AkcesControlRecord> record : controlRecords.records(controlPartition)) {
                        if (record.value() instanceof AggregateServiceRecord aggregateServiceRecord) {
                            System.out.println(objectMapper.writeValueAsString(aggregateServiceRecord));
                            serviceRecords.put(record.key(), aggregateServiceRecord);
                        }
                    }
                }
            }

            Assertions.assertEquals(4, serviceRecords.size());

        }
    }

    @Test
    @Order(2)
    void testCreateAllEURMarketsAndMakeATrade() {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !prderProcessManagerController.isRunning() ||
                !cryptoMarketController.isRunning() ||
                !akcesClientController.isRunning()) {
            Thread.onSpinWait();
        }
        // these are the events in the order we expect
        String[] eventTypes = new String[]{
                "AccountCreated",
                "AccountCreated",
                "WalletCreated",
                "BalanceCreated",
                "UserOrderProcessesCreated",
                "WalletCreated",
                "BalanceCreated",
                "BalanceCreated",
                "WalletCredited",
                "AmountReserved",
                "UserOrderProcessesCreated",
                "BuyOrderCreated",
                "CryptoMarketCreated",
                "BuyOrderPlaced",
                "MarketOrderPlaced",
                "MarketOrderFilled",
                "BuyOrderFilled"
        };

        // create a counterparty account
        akcesClientController.sendAndForget("TEST",
                new CreateAccountCommand(counterPartyId,
                        "EU",
                        "Coinbase",
                        "Limited",
                        "no-reply@coinbase.com"));

        // create all EUR markets
//        coinbaseService.getProducts().stream().filter(product -> product.quoteCurrency().equals("EUR")).forEach(product -> {
//            akcesClientController.sendAndForget("TEST", new CreateCryptoMarketCommand(
//                    product.id(),
//                    product.baseCurrency(),
//                    product.quoteCurrency(),
//                    product.baseIncrement(),
//                    product.quoteIncrement(),
//                    counterPartyId
//            ));
//        });
        Product product = coinbaseService.getProduct("BTC-EUR");
        akcesClientController.sendAndForget("TEST", new CreateCryptoMarketCommand(
                product.id(),
                product.baseCurrency(),
                product.quoteCurrency(),
                product.baseIncrement(),
                product.quoteIncrement(),
                counterPartyId));

        // create an account to trade
        String accountId = "2254b8cb-f272-4695-82cf-306ba0149829";
        Mono.fromCompletionStage(akcesClientController.send("TEST", new CreateAccountCommand(accountId,
                "NL",
                "John",
                "Doe",
                "john.doe@example.com"))).block();

        // create a BTC balance (EUR balance should already be created
        Mono.fromCompletionStage(akcesClientController.send("TEST",
                new CreateBalanceCommand(accountId, "BTC"))).block();
        // credit the user with EUR to make a BTC buy
        Mono.fromCompletionStage(akcesClientController.send("TEST",
                new CreditWalletCommand(accountId,
                        "EUR",
                        new BigDecimal("1000")))).block();
        ;

        // place a buy order on BTC-EUR market
        String clientOrderId = "479ab2a4-d19e-4116-9f7e-cf13dca5763a";
        Mono.fromCompletionStage(akcesClientController.send("TEST",
                new PlaceBuyOrderCommand(accountId,
                        new CryptoMarket("BTC-EUR", "BTC", "EUR"),
                        new BigDecimal("250"),
                        clientOrderId))).block();

        // now we need to wait until we get the order filled event
        try (
                Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test", "test")
        ) {
            testConsumer.subscribe(Pattern.compile(".*-DomainEvents$"), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    testConsumer.seekToBeginning(partitions);
                }
            });
            int count = 0;
            while (count < 17) {
                for (ConsumerRecord<String, ProtocolRecord> record : testConsumer.poll(Duration.ofMillis(100))) {
                    Assertions.assertEquals(eventTypes[count],record.value().name());
                    count++;
                }
            }
        }
    }

    public static class Initializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
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

}
