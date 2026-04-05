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

package org.elasticsoftware.akces.query.reflector;

import jakarta.inject.Inject;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.CreateWalletCommand;
import org.elasticsoftware.akcestest.aggregate.wallet.WalletCreatedEvent;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsoftware.akces.query.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(
        classes = AggregateServiceApplication.class,
        args = "org.elasticsoftware.akces.query.reflector.ReflectorTestConfiguration",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.elasticsoftware.akces.query.database.AkcesDatabaseModelAutoConfiguration,org.elasticsoftware.akces.query.models.AkcesQueryModelAutoConfiguration")
@ContextConfiguration(initializers = ReflectorIntegrationTest.ContextInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
public class ReflectorIntegrationTest {

    private static final String CONFLUENT_PLATFORM_VERSION = "7.8.7";

    @Container
    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:" + CONFLUENT_PLATFORM_VERSION))
                    .withKraft()
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

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
    @Qualifier("TestNotificationReflectorAkcesReflectorController")
    AkcesReflectorController reflectorController;

    @BeforeAll
    @AfterAll
    public static void cleanUp() throws IOException {
        if (Files.exists(Paths.get("/tmp/akces"))) {
            Files.walk(Paths.get("/tmp/akces"))
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @BeforeEach
    public void resetReflector() {
        TestNotificationReflector.reset();
    }

    @Test
    @Order(1)
    void testContextLoads() {
        assertNotNull(walletController);
        assertNotNull(accountController);
        assertNotNull(orderProcessManagerController);
        assertNotNull(akcesClientController);
        assertNotNull(reflectorController);

        long timeoutNanos = TimeUnit.SECONDS.toNanos(30);
        long deadline = System.nanoTime() + timeoutNanos;

        while (!walletController.isRunning() ||
               !accountController.isRunning() ||
               !orderProcessManagerController.isRunning() ||
               !akcesClientController.isRunning() ||
               !reflectorController.isRunning()) {
            if (System.nanoTime() >= deadline) {
                fail("Timed out waiting for controllers to reach RUNNING state within 30 seconds. "
                     + "walletController=" + walletController.isRunning()
                     + ", accountController=" + accountController.isRunning()
                     + ", orderProcessManagerController=" + orderProcessManagerController.isRunning()
                     + ", akcesClientController=" + akcesClientController.isRunning()
                     + ", reflectorController=" + reflectorController.isRunning());
            }
            Thread.onSpinWait();
        }

        assertTrue(walletController.isRunning());
        assertTrue(accountController.isRunning());
        assertTrue(orderProcessManagerController.isRunning());
        assertTrue(akcesClientController.isRunning());
        assertTrue(reflectorController.isRunning());
    }

    @Test
    @Order(2)
    void testEndToEndHappyPath() throws Exception {
        while (!walletController.isRunning() ||
               !accountController.isRunning() ||
               !orderProcessManagerController.isRunning() ||
               !akcesClientController.isRunning() ||
               !reflectorController.isRunning()) {
            Thread.onSpinWait();
        }

        String walletId = "test-wallet-happy-" + System.currentTimeMillis();
        CreateWalletCommand command = new CreateWalletCommand(walletId, "EUR");
        List<DomainEvent> events = akcesClientController.send("TEST_TENANT", command)
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        assertNotNull(events);
        assertFalse(events.isEmpty());
        assertInstanceOf(WalletCreatedEvent.class, events.getFirst());

        // Wait for the reflector to process the event (it subscribes to Wallet-DomainEvents)
        long deadline = System.currentTimeMillis() + 30_000;
        while (TestNotificationReflector.successResults.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertFalse(TestNotificationReflector.successResults.isEmpty(),
                "Reflector should have processed the WalletCreatedEvent");
        String result = TestNotificationReflector.successResults.peek();
        assertNotNull(result);
        assertTrue(result.contains(walletId), "Result should contain wallet id: " + walletId);
        assertEquals(1, TestNotificationReflector.processedEvents.size());
        assertEquals(walletId, TestNotificationReflector.processedEvents.peek().id());
    }

    @Test
    @Order(3)
    void testEndToEndFailure() throws Exception {
        while (!walletController.isRunning() ||
               !accountController.isRunning() ||
               !orderProcessManagerController.isRunning() ||
               !akcesClientController.isRunning() ||
               !reflectorController.isRunning()) {
            Thread.onSpinWait();
        }

        // Configure reflector to fail
        TestNotificationReflector.shouldFail.set(true);

        String walletId = "test-wallet-fail-" + System.currentTimeMillis();
        CreateWalletCommand command = new CreateWalletCommand(walletId, "EUR");
        List<DomainEvent> events = akcesClientController.send("TEST_TENANT", command)
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        assertNotNull(events);
        assertFalse(events.isEmpty());
        assertInstanceOf(WalletCreatedEvent.class, events.getFirst());

        // Wait for the reflector to exhaust retries and call onFailure.
        // maxRetries=2, so it tries 3 times total (initial attempt + 2 retries), then calls onFailure.
        long deadline = System.currentTimeMillis() + 60_000;
        while (TestNotificationReflector.failureExceptions.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertFalse(TestNotificationReflector.failureExceptions.isEmpty(),
                "Reflector should have called onFailure after exhausting retries");
        Exception exception = TestNotificationReflector.failureExceptions.peek();
        assertNotNull(exception);
        assertTrue(TestNotificationReflector.successResults.isEmpty(),
                "onSuccess should not have been called");
        // callCount should be maxRetries+1 = 3 (initial + 2 retries)
        assertEquals(3, TestNotificationReflector.callCount.get(),
                "Handler should have been called maxRetries+1 times");
    }

    public static class ContextInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            prepareKafka(kafka.getBootstrapServers());
            prepareDomainEventSchemas(kafka.getBootstrapServers(), "org.elasticsoftware.akcestest.aggregate");
            prepareAggregateServiceRecords(kafka.getBootstrapServers());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "akces.rocksdb.baseDir=/tmp/akces",
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers()
            );
        }
    }
}
