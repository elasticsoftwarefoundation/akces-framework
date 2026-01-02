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

package org.elasticsoftware.akces.query.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.elasticsoftware.akces.AggregateServiceApplication;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.client.AkcesClientController;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akcestest.aggregate.account.CreateAccountCommand;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
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
import java.util.Map;

import static org.elasticsoftware.akces.query.TestUtils.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
@SpringBootTest(
        classes = AggregateServiceApplication.class,  // we need to use AggregateServiceApplication in order to load the Aggregate beeans for the test
        args = "org.elasticsoftware.akces.query.database.DatabaseModelTestConfiguration",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@EnableJpaRepositories
@ContextConfiguration(initializers = DatabaseModelRuntimeTests.ContextInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
public class DatabaseModelRuntimeTests {
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
            .withDatabaseName("akces")
            .withUsername("akces")
            .withPassword("akces")
            .withNetwork(network)
            .withNetworkAliases("postgresql");

    @Inject
    @Qualifier("aggregateServiceKafkaAdmin")
    KafkaAdmin adminClient;

    @Inject
    @Qualifier("akcesDatabaseModelConsumerFactory")
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

    @Inject //@Qualifier("Defaultv1AkcesDatabaseModelController")
    AkcesDatabaseModelController defaultModelController;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ApplicationContext applicationContext;

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
                    "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "spring.datasource.url=" + postgresql.getJdbcUrl(),
                    "spring.datasource.username=akces",
                    "spring.datasource.password=akces",
                    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
            );
        }
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
        assertNotNull(consumerFactory);
        assertNotNull(objectMapper);
        assertNotNull(walletController);
        assertNotNull(accountController);
        assertNotNull(orderProcessManagerController);
        assertNotNull(akcesClientController);
        assertNotNull(defaultModelController);

        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !defaultModelController.isRunning()) {
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
                !defaultModelController.isRunning()) {
            Thread.onSpinWait();
        }
        assertNotNull(applicationContext);
        assertEquals(1, applicationContext.getBeansOfType(DatabaseModelRuntime.class).size());
    }

    @Test
    @Order(3)
    public void testCreateAccount() throws Exception {
        while (!walletController.isRunning() ||
                !accountController.isRunning() ||
                !orderProcessManagerController.isRunning() ||
                !akcesClientController.isRunning() ||
                !defaultModelController.isRunning()) {
            Thread.onSpinWait();
        }
        // Given
        String userId = "d0daa4ab-ed08-436a-884e-0da1ed7b1f05";
        CreateAccountCommand command = new CreateAccountCommand(
            userId,
            "NL",
            "John",
            "Doe",
            "john.doe@example.com"
        );

        // When
        akcesClientController.sendAndForget("Default", userId, command);

        // Sleep briefly to allow event processing
        Thread.sleep(1000);

        // Then
        // Verify database state directly using JDBC
        JdbcTemplate jdbcTemplate = applicationContext.getBean(JdbcTemplate.class);
        Map<String, Object> result = jdbcTemplate.queryForMap(
            "SELECT * FROM account WHERE user_id = ?",
            userId
        );

        assertEquals(userId, result.get("user_id"));
        assertEquals("NL", result.get("country"));
        assertEquals("John", result.get("first_name"));
        assertEquals("Doe", result.get("last_name"));
        assertEquals("john.doe@example.com", result.get("email"));
    }
}
