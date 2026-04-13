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

package org.elasticsoftware.akces.agentic.testfixtures;

import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.agentic.commands.AssignTaskCommand;
import org.elasticsoftware.akces.agentic.runtime.AkcesAgenticAggregateController;
import org.elasticsoftware.akces.agentic.runtime.AgenticAggregateServiceApplication;
import org.elasticsoftware.akces.aggregate.HumanRequestingParty;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the TradingAdvisor agentic aggregate.
 *
 * <p>These tests exercise the full agentic aggregate lifecycle including:
 * <ul>
 *   <li>Kafka topic creation and controller startup</li>
 *   <li>Auto-create of singleton aggregate state</li>
 *   <li>Task assignment via {@link AssignTaskCommand}</li>
 *   <li>Agent processing via the Embabel framework with Anthropic LLM</li>
 *   <li>Agent task completion with domain events</li>
 * </ul>
 *
 * <p><strong>These tests are only enabled when the {@code ANTHROPIC_API_KEY} environment
 * variable is set.</strong> The tests require a running Docker daemon for Testcontainers
 * and access to the Anthropic API for LLM-based agent processing.
 */
@SpringBootTest(
        classes = AgenticAggregateServiceApplication.class,
        args = "org.elasticsoftware.akces.agentic.testfixtures.TradingAdvisorConfiguration",
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@Testcontainers
@ContextConfiguration(initializers = TradingAdvisorIntegrationTest.DataSourceInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+",
        disabledReason = "ANTHROPIC_API_KEY environment variable must be set to run agentic integration tests")
public class TradingAdvisorIntegrationTest {

    private static final String CONFLUENT_PLATFORM_VERSION = "7.8.7";

    private static final Network network = Network.newNetwork();

    @Container
    private static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:" + CONFLUENT_PLATFORM_VERSION))
                    .withKraft()
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                    .withNetwork(network)
                    .withNetworkAliases("kafka");

    @Inject
    @Qualifier("agenticServiceKafkaAdmin")
    KafkaAdmin adminClient;

    @Inject
    @Qualifier("TradingAdvisorAgenticRuntime")
    AkcesAgenticAggregateController tradingAdvisorController;

    @Inject
    @Qualifier("agenticServiceConsumerFactory")
    ConsumerFactory<String, ProtocolRecord> consumerFactory;

    @Inject
    @Qualifier("agenticServiceProducerFactory")
    ProducerFactory<String, ProtocolRecord> producerFactory;

    @Inject
    ObjectMapper objectMapper;

    @BeforeAll
    @AfterAll
    public static void cleanUp() throws IOException {
        // clean up the rocksdb directory
        if (Files.exists(Paths.get("/tmp/akces"))) {
            Files.walk(Paths.get("/tmp/akces"))
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .filter(File::isDirectory)
                    .forEach(file -> System.out.println(file.getAbsolutePath()));
            Files.walk(Paths.get("/tmp/akces"))
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Kafka admin client should be available and topics should exist")
    void testKafkaAdminClient() {
        assertNotNull(adminClient);
        var topics = adminClient.describeTopics(
                "Akces-Control",
                "TradingAdvisor-Commands",
                "TradingAdvisor-DomainEvents",
                "TradingAdvisor-AggregateState");
        assertNotNull(topics);
        assertFalse(topics.isEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("TradingAdvisor controller should start and reach PROCESSING state")
    void waitForControllerToStart() {
        assertNotNull(tradingAdvisorController);

        // Wait for the controller to fully start (auto-create included)
        long deadline = System.currentTimeMillis() + 60_000;
        while (!tradingAdvisorController.isRunning()) {
            if (System.currentTimeMillis() > deadline) {
                fail("TradingAdvisor controller did not start within 60 seconds");
            }
            Thread.onSpinWait();
        }
        assertTrue(tradingAdvisorController.isRunning());
    }

    @Test
    @Order(3)
    @DisplayName("Auto-created state should produce TradingAdvisorCreated event in DomainEvents topic")
    void testAutoCreateState() {
        // The controller auto-creates the singleton state on startup.
        // Verify the TradingAdvisorCreated event is in the DomainEvents topic.
        TopicPartition domainEventsPartition = new TopicPartition("TradingAdvisor-DomainEvents", 0);

        Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test-AutoCreate", "test-auto-create");
        testConsumer.assign(List.of(domainEventsPartition));
        testConsumer.seekToBeginning(testConsumer.assignment());

        List<DomainEventRecord> domainEvents = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 30_000;
        while (domainEvents.isEmpty() && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, ProtocolRecord> records = testConsumer.poll(Duration.ofMillis(500));
            records.records(domainEventsPartition).forEach(record -> {
                if (record.value() instanceof DomainEventRecord der) {
                    domainEvents.add(der);
                }
            });
        }
        testConsumer.close();

        assertFalse(domainEvents.isEmpty(), "Expected at least one domain event from auto-create");
        // The first event should be TradingAdvisorCreated
        DomainEventRecord firstEvent = domainEvents.getFirst();
        assertEquals("TradingAdvisorCreated", firstEvent.name());
    }

    @Test
    @Order(4)
    @DisplayName("AssignTask command should produce AgentTaskAssigned event")
    void testAssignTaskCommand() {
        Producer<String, ProtocolRecord> testProducer = producerFactory.createProducer("test-assign");

        // Send an AssignTask command
        AssignTaskCommand command = new AssignTaskCommand(
                "TradingAdvisor",
                "Analyze the BTC/USD market and provide a brief trend analysis",
                new HumanRequestingParty("test-user", "analyst"),
                Map.of("market", "BTC/USD"));

        CommandRecord commandRecord = new CommandRecord(
                null,
                "AssignTask",
                1,
                objectMapper.writeValueAsBytes(command),
                PayloadEncoding.JSON,
                command.getAggregateId(),
                null,
                null);

        testProducer.beginTransaction();
        testProducer.send(new ProducerRecord<>(
                "TradingAdvisor-Commands", 0, commandRecord.aggregateId(), commandRecord));
        testProducer.commitTransaction();
        testProducer.close();

        // Wait for events to appear on the DomainEvents topic
        TopicPartition domainEventsPartition = new TopicPartition("TradingAdvisor-DomainEvents", 0);
        Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test-AssignTask", "test-assign-task");
        testConsumer.assign(List.of(domainEventsPartition));
        testConsumer.seekToBeginning(testConsumer.assignment());

        List<DomainEventRecord> allEvents = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, ProtocolRecord> records = testConsumer.poll(Duration.ofMillis(500));
            records.records(domainEventsPartition).forEach(record -> {
                if (record.value() instanceof DomainEventRecord der) {
                    allEvents.add(der);
                }
            });
            // Check if we have the AgentTaskAssigned event
            if (allEvents.stream().anyMatch(e -> "AgentTaskAssigned".equals(e.name()))) {
                break;
            }
        }
        testConsumer.close();

        assertTrue(allEvents.stream().anyMatch(e -> "AgentTaskAssigned".equals(e.name())),
                "Expected AgentTaskAssigned event. Events received: " +
                        allEvents.stream().map(DomainEventRecord::name).toList());
    }

    @Test
    @Order(5)
    @DisplayName("Agent should complete the task and produce AgentTaskFinished event with analysis")
    void testAgentTaskCompletion() {
        // Wait for the agent to complete the task assigned in the previous test.
        // The agent process is ticked during the partition's idle-poll cycle.
        TopicPartition domainEventsPartition = new TopicPartition("TradingAdvisor-DomainEvents", 0);
        Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test-TaskCompletion", "test-task-completion");
        testConsumer.assign(List.of(domainEventsPartition));
        testConsumer.seekToBeginning(testConsumer.assignment());

        List<DomainEventRecord> allEvents = new ArrayList<>();
        // Agent processing may take longer due to LLM calls — allow up to 120 seconds
        long deadline = System.currentTimeMillis() + 120_000;
        boolean taskFinished = false;
        boolean analysisCompleted = false;

        while (System.currentTimeMillis() < deadline && !taskFinished) {
            ConsumerRecords<String, ProtocolRecord> records = testConsumer.poll(Duration.ofMillis(1000));
            records.records(domainEventsPartition).forEach(record -> {
                if (record.value() instanceof DomainEventRecord der) {
                    allEvents.add(der);
                    System.out.println("Event received: " + der.name());
                }
            });
            taskFinished = allEvents.stream().anyMatch(e -> "AgentTaskFinished".equals(e.name()));
            analysisCompleted = allEvents.stream().anyMatch(e -> "MarketAnalysisCompleted".equals(e.name()));
        }
        testConsumer.close();

        List<String> eventTypes = allEvents.stream().map(DomainEventRecord::name).toList();
        assertTrue(taskFinished,
                "Expected AgentTaskFinished event within 120s. Events received: " + eventTypes);
        assertTrue(analysisCompleted,
                "Expected MarketAnalysisCompleted event from agent. Events received: " + eventTypes);
    }

    /**
     * Context initializer that sets up Kafka topics and Spring Boot properties
     * before the application context starts.
     */
    public static class DataSourceInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // Initialize Kafka topics
            TradingAdvisorTestUtils.prepareKafka(kafka.getBootstrapServers());

            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "akces.aggregate.schemas.forceRegister=true",
                    "akces.rocksdb.baseDir=/tmp/akces",
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                    "spring.ai.anthropic.api-key=" + (apiKey != null ? apiKey : "")
            );
        }
    }
}
