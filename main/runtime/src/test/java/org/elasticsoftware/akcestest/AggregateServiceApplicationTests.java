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

package org.elasticsoftware.akcestest;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.elasticsoftware.akcestest.TestUtils.*;

@SpringBootTest(
        classes = AggregateServiceApplication.class,
        args = "org.elasticsoftware.akcestest.aggregate.account",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@ContextConfiguration(initializers = AggregateServiceApplicationTests.Initializer.class)
@Testcontainers
@DirtiesContext
public class AggregateServiceApplicationTests {
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
    ApplicationContext applicationContext;
    @Inject
    @Qualifier("AccountAkcesController")
    AkcesAggregateController akcesAggregateController;
    @Inject
    @Qualifier("aggregateServiceConsumerFactory")
    ConsumerFactory<String, ProtocolRecord> consumerFactory;
    @Inject
    @Qualifier("aggregateServiceProducerFactory")
    ProducerFactory<String, ProtocolRecord> producerFactory;
    @Inject
    @Qualifier("aggregateServiceControlConsumerFactory")
    ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory;

    @BeforeAll
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
    public void testAggregateLoading() {
        // we should have a single aggregate (Account) loaded
        Assertions.assertNotNull(applicationContext.getBean("AccountAggregateRuntimeFactory"));
        Assertions.assertNotNull(akcesAggregateController);
        Assertions.assertNotNull(consumerFactory);
        Assertions.assertNotNull(producerFactory);
        Assertions.assertNotNull(controlConsumerFactory);
        Assertions.assertThrows(NoSuchBeanDefinitionException.class, () -> applicationContext.getBean("WalletAggregateRuntimeFactory"));
        Assertions.assertThrows(NoSuchBeanDefinitionException.class, () -> applicationContext.getBean("OrderProcessManagerAggregateRuntimeFactory"));

        Consumer<String, AkcesControlRecord> controlConsumer = controlConsumerFactory.createConsumer("Test-AkcesControl", "test-akces-control");

        controlConsumer.subscribe(List.of("Akces-Control"));
        controlConsumer.poll(Duration.ofMillis(1000));
        controlConsumer.seekToBeginning(controlConsumer.assignment());

        ConsumerRecords<String, AkcesControlRecord> controlRecords = new ConsumerRecords<>(Collections.emptyMap());
        while (controlRecords.isEmpty()) {
            controlRecords = controlConsumer.poll(Duration.ofMillis(1000));
        }

        // TODO: ensure that we see the Wallet command service

        controlConsumer.close();

        // wait until the ackes controller is running
        while (!akcesAggregateController.isRunning()) {
            Thread.onSpinWait();
        }
    }

    public static class Initializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            SchemaRegistryClient src = new CachedSchemaRegistryClient("http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081), 100);
            prepareExternalSchemas(src, List.of(AccountCreatedEvent.class));
            try {
                prepareAggregateServiceRecords(kafka.getBootstrapServers());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            //prepareExternalServices(kafka.getBootstrapServers());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "akces.rocksdb.baseDir=/tmp/akces",
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "kafka.schemaregistry.url=http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081)
            );
        }
    }

}
