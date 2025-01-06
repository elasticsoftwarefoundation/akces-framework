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
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.cryptotrading.aggregates.account.CreateAccountCommand;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.CoinbaseService;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.Product;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.commands.CreateCryptoMarketCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.CryptoMarket;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.PlaceBuyOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.CreateBalanceCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.CreditWalletCommand;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsoftware.cryptotrading.TestUtils.prepareKafka;

@SpringBootTest(
        classes = AggregateServiceApplication.class,
        args = "org.elasticsoftware.cryptotrading.AggregateConfig",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = CryptoTradingApplicationTest.Initializer.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CryptoTradingApplicationTest {
    private static final String CONFLUENT_PLATFORM_VERSION = "7.8.0";

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
                    "spring.kafka.bootstrapServers="+kafka.getBootstrapServers(),
                    "kafka.schemaregistry.url=http://"+schemaRegistry.getHost()+":"+schemaRegistry.getMappedPort(8081)
            );
        }
    }

    @AfterAll
    @BeforeAll
    public static void cleanUp() throws IOException {
        if(Files.exists(Paths.get("/tmp/akces"))) {
            // clean up the rocksdb directory
            Files.walk(Paths.get("/tmp/akces"))
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

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

    @Inject @Qualifier("aggregateServiceConsumerFactory")
    ConsumerFactory<String, ProtocolRecord> consumerFactory;

    private final static String counterPartyId = "337f335d-caf1-4f85-9440-6bc3c0ebbb77";

    @Test
    @Order(1)
    void contextLoads() {
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

        // create a counterparty account
        akcesClientController.sendAndForget("TEST",
                new CreateAccountCommand(counterPartyId,
                "EU",
                "Coinbase",
                "Limited",
                "no-reply@coinbase.com" ));

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
                        new CryptoMarket("BTC-EUR","BTC", "EUR") ,
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
            while (count < 16) {
                for (ConsumerRecord<String, ProtocolRecord> record : testConsumer.poll(Duration.ofMillis(100))) {
                    System.out.println(record.topic() + " : " + record.value().name() + "=" + new String(record.value().payload()));
                    count++;
                }
            }
        }

        System.out.println("Waiting for 10 seconds");

        try {
            Thread.sleep(10 * 1000);
        } catch (InterruptedException e) {
            // ignore
        }
    }

}
