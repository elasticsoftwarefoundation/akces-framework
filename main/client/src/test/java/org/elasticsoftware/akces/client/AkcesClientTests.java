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

package org.elasticsoftware.akces.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.client.commands.CreateAccountCommand;
import org.elasticsoftware.akces.client.commands.InvalidCommand;
import org.elasticsoftware.akces.client.commands.UnroutableCommand;
import org.elasticsoftware.akces.client.events.AccountCreatedEvent;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.AggregateServiceCommandType;
import org.elasticsoftware.akces.control.AggregateServiceDomainEventType;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.EncryptingGDPRContext;
import org.elasticsoftware.akces.gdpr.GDPRKeyUtils;
import org.elasticsoftware.akces.protocol.*;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Testcontainers
@SpringBootTest(classes = AkcesClientTestConfiguration.class, properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ContextConfiguration(initializers = AkcesClientTests.ContextInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AkcesClientTests {
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

    @Inject
    KafkaAdmin adminClient;

    @Inject
    SchemaRegistryClient schemaRegistryClient;

    @Inject
    AkcesClientController akcesClient;

    @Inject
    ConsumerFactory<String, ProtocolRecord> consumerFactory;

    @Inject
    ProducerFactory<String, ProtocolRecord> producerFactory;

    @Inject
    ObjectMapper objectMapper;

    public static class ContextInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            // initialize kafka topics
            prepareKafka(kafka.getBootstrapServers());
            prepareCommandSchemas("http://"+schemaRegistry.getHost()+":"+schemaRegistry.getMappedPort(8081), List.of(CreateAccountCommand.class));
            prepareDomainEventSchemas("http://"+schemaRegistry.getHost()+":"+schemaRegistry.getMappedPort(8081), List.of(AccountCreatedEvent.class));
            prepareExternalServices(kafka.getBootstrapServers());
            //prepareExternalServices(kafka.getBootstrapServers());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    applicationContext,
                    "spring.kafka.enabled=true",
                    "spring.kafka.bootstrapServers="+kafka.getBootstrapServers(),
                    "kafka.schemaregistry.url=http://"+schemaRegistry.getHost()+":"+schemaRegistry.getMappedPort(8081)
            );
        }
    }

    public static void prepareKafka(String bootstrapServers) {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        kafkaAdmin.createOrModifyTopics(
                createCompactedTopic("Akces-Control", 3),
                createTopic("Akces-CommandResponses", 3, 604800000L),
                createCompactedTopic("Akces-GDPRKeys", 3),
                createTopic("Wallet-Commands", 3),
                createTopic("Wallet-DomainEvents", 3),
                createTopic("Account-Commands", 3),
                createTopic("Account-DomainEvents", 3),
                createTopic("OrderProcessManager-Commands", 3),
                createTopic("OrderProcessManager-DomainEvents", 3),
                createCompactedTopic("Wallet-AggregateState", 3),
                createCompactedTopic("Account-AggregateState", 3),
                createCompactedTopic("OrderProcessManager-AggregateState", 3));
    }

    private static NewTopic createTopic(String name, int numPartitions) {
        return createTopic(name, numPartitions, -1L);
    }

    private static NewTopic createTopic(String name, int numPartitions, long retentionMs) {
        NewTopic topic = new NewTopic(name, numPartitions , Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy","delete",
                "max.message.bytes","20971520",
                "retention.ms", Long.toString(retentionMs),
                "segment.ms","604800000"));
    }

    private static NewTopic createCompactedTopic(String name, int numPartitions) {
        NewTopic topic = new NewTopic(name, numPartitions , Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy","compact",
                "max.message.bytes","20971520",
                "retention.ms", "-1",
                "segment.ms","604800000",
                "min.cleanable.dirty.ratio", "0.1",
                "delete.retention.ms", "604800000",
                "compression.type", "lz4"));
    }

    public static <C extends Command> void prepareCommandSchemas(String url, List<Class<C>> commandClasses) {
        SchemaRegistryClient src = new CachedSchemaRegistryClient(url, 100);
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS, JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(new JacksonModule());
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_FIELDS_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_METHOD_RETURN_VALUES_BY_DEFAULT);
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator jsonSchemaGenerator = new SchemaGenerator(config);
        try {
            for(Class<C> commandClass : commandClasses) {
                CommandInfo info = commandClass.getAnnotation(CommandInfo.class);
                src.register("commands."+info.type(),
                        new JsonSchema(jsonSchemaGenerator.generateSchema(commandClass), List.of(), Map.of(), info.version()),
                        info.version(),
                        -1);
            }
        } catch (IOException | RestClientException e) {
            throw new ApplicationContextException("Problem populating SchemaRegistry", e);
        }
    }

    public static <D extends DomainEvent> void prepareDomainEventSchemas(String url, List<Class<D>> domainEventClasses) {
        SchemaRegistryClient src = new CachedSchemaRegistryClient(url, 100);
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS, JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(new JacksonModule());
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_FIELDS_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_METHOD_RETURN_VALUES_BY_DEFAULT);
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator jsonSchemaGenerator = new SchemaGenerator(config);
        try {
            for(Class<D> domainEventClass : domainEventClasses) {
                DomainEventInfo info = domainEventClass.getAnnotation(DomainEventInfo.class);
                src.register("domainevents."+info.type(),
                        new JsonSchema(jsonSchemaGenerator.generateSchema(domainEventClass), List.of(), Map.of(), info.version()),
                        info.version(),
                        -1);
            }
        } catch (IOException | RestClientException e) {
            throw new ApplicationContextException("Problem populating SchemaRegistry", e);
        }
    }

    public static void prepareExternalServices(String bootstrapServers) {
        AkcesControlRecordSerde controlSerde = new AkcesControlRecordSerde(new ObjectMapper());
        Map<String, Object> controlProducerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true",
                ProducerConfig.LINGER_MS_CONFIG, "0",
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1",
                ProducerConfig.RETRIES_CONFIG, "2147483647",
                ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "0",
                ProducerConfig.TRANSACTIONAL_ID_CONFIG, "Test-AkcesControllerProducer",
                ProducerConfig.CLIENT_ID_CONFIG, "Test-AkcesControllerProducer");
        try (Producer<String,AkcesControlRecord> controlProducer = new KafkaProducer<>(controlProducerProps, new StringSerializer(), controlSerde.serializer())) {
            controlProducer.initTransactions();
            AggregateServiceRecord aggregateServiceRecord = new AggregateServiceRecord(
                    "Account",
                    "Account-Commands",
                    "Account-DomainEvents",
                    List.of(new AggregateServiceCommandType("CreateAccount",1, true,"commands.CreateAccount")),
                    List.of(new AggregateServiceDomainEventType("AccountCreated", 1, true, false, "domainevents.AccountCreated")),
                    List.of());
            controlProducer.beginTransaction();
            for (int partition = 0; partition < 3; partition++) {
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "Account", aggregateServiceRecord));
            }
            controlProducer.commitTransaction();
        }
    }

    @Test
    public void testUnroutableCommand() {
        Assertions.assertNotNull(akcesClient);
        // make sure it's running
        while(!akcesClient.isRunning()) {
            Thread.onSpinWait();
        }
        // since we didn't register any services this should give a unroutable error
        Assertions.assertThrows(UnroutableCommandException.class, () -> akcesClient.send("TEST_TENANT",new UnroutableCommand(UUID.randomUUID().toString())));
    }

    @Test
    public void testInvalidCommand() {
        while(!akcesClient.isRunning()) {
            Thread.onSpinWait();
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> akcesClient.send("TEST_TENANT",new InvalidCommand(UUID.randomUUID().toString())));
    }

    @Test
    public void testValidationError() {
        // make sure it's running
        while(!akcesClient.isRunning()) {
            Thread.onSpinWait();
        }
        // since we didn't register any services this should give a unroutable error
        Assertions.assertThrows(CommandValidationException.class, () -> akcesClient.send("TEST_TENANT",new CreateAccountCommand(UUID.randomUUID().toString(), "NL", "Aike","Christianen",null)));
    }

    @Test
    public void testSendCommand() throws InterruptedException, JsonProcessingException {
        // make sure it's running
        while(!akcesClient.isRunning()) {
            Thread.onSpinWait();
        }
        String userId = "deb2b2c1-847c-44f3-a2a4-c81bc5ce795d";
        CompletionStage<List<DomainEvent>> result = akcesClient.send("TEST_TENANT",
                new CreateAccountCommand(userId,
                        "NL",
                        "Aike",
                        "Christianen",
                        "aike.christianen@gmail.com"));

        // send the command response
        try (
                Producer<String, ProtocolRecord> testProducer = producerFactory.createProducer("test");
                Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test", "test")
        ) {
            // we need to get the command to know the commandId
            TopicPartition commandResponsesPartition = akcesClient.getCommandResponsePartition();
            TopicPartition commandPartition = new TopicPartition("Account-Commands", akcesClient.resolvePartition(userId));
            testConsumer.assign(List.of(commandPartition));
            testConsumer.seekToBeginning(List.of(commandPartition));
            ConsumerRecords<String, ProtocolRecord> records = testConsumer.poll(Duration.ofMillis(250));
            List<ProtocolRecord> allRecords = new ArrayList<>();
            // we should have 1 record
            while(allRecords.isEmpty()) {
                records.forEach(record -> allRecords.add(record.value()));
                // wait for the events to be produced
                records = testConsumer.poll(Duration.ofMillis(250));
            }
            Assertions.assertEquals(1, allRecords.size());
            CommandRecord cr = (CommandRecord) allRecords.getFirst();

            testProducer.beginTransaction();
            List<DomainEventRecord> events = List.of(new DomainEventRecord("TEST_TENANT","AccountCreated",1,objectMapper.writeValueAsBytes(new AccountCreatedEvent(userId,"NL", "Aike","Christianen","aike.christianen@gmail.com")), PayloadEncoding.JSON,userId,null,0));
            CommandResponseRecord crr = new CommandResponseRecord("TEST_TENANT",userId,cr.correlationId(),cr.id(),events,null);

            testProducer.send(new ProducerRecord<>(commandResponsesPartition.topic(), commandResponsesPartition.partition(), crr.commandId(), crr));
            testProducer.commitTransaction();
        }

        CountDownLatch waitLatch = new CountDownLatch(1);
        result.whenComplete((s, throwable) -> waitLatch.countDown());
        Assertions.assertTrue(waitLatch.await(10, TimeUnit.SECONDS));
        Assertions.assertTrue(result.toCompletableFuture().isDone());
        Assertions.assertFalse(result.toCompletableFuture().isCompletedExceptionally());
        Assertions.assertNotNull(result.toCompletableFuture().getNow(null));
        Assertions.assertEquals(1, result.toCompletableFuture().getNow(null).size());
        AccountCreatedEvent ace = (AccountCreatedEvent) result.toCompletableFuture().getNow(null).getFirst();
        Assertions.assertEquals(userId, ace.userId());
        Assertions.assertEquals("NL", ace.country());
        Assertions.assertEquals("Aike", ace.firstName());
        Assertions.assertEquals("Christianen", ace.lastName());
        Assertions.assertEquals("aike.christianen@gmail.com", ace.email());
    }

    @Test
    public void testSendCommandWithCorrelationId() throws InterruptedException, JsonProcessingException {
        // make sure it's running
        while(!akcesClient.isRunning()) {
            Thread.onSpinWait();
        }
        String correlationId = UUID.randomUUID().toString();
        String userId = "dff9901f-6ccb-486a-9a79-5b7e300925c5";
        CompletionStage<List<DomainEvent>> result = akcesClient.send("TEST_TENANT",
                correlationId,
                new CreateAccountCommand(
                        userId,
                        "NL",
                        "Aike",
                        "Christianen",
                        "aike.christianen@gmail.com"));

        // send the command response
        try (
                Producer<String, ProtocolRecord> testProducer = producerFactory.createProducer("test");
                Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test", "test")
        ) {
            // we need to get the command to know the commandId
            TopicPartition commandResponsesPartition = akcesClient.getCommandResponsePartition();
            TopicPartition commandPartition = new TopicPartition("Account-Commands", akcesClient.resolvePartition(userId));
            testConsumer.assign(List.of(commandPartition));
            testConsumer.seekToBeginning(List.of(commandPartition));

            ConsumerRecords<String, ProtocolRecord> records = testConsumer.poll(Duration.ofMillis(250));
            List<ProtocolRecord> allRecords = new ArrayList<>();
            // we should have 1 record
            while(allRecords.isEmpty()) {
                records.forEach(record -> allRecords.add(record.value()));
                // wait for the events to be produced
                records = testConsumer.poll(Duration.ofMillis(250));
            }
            Assertions.assertEquals(1, allRecords.size());
            CommandRecord cr = (CommandRecord) allRecords.getFirst();
            Assertions.assertEquals(correlationId, cr.correlationId());

            testProducer.beginTransaction();
            List<DomainEventRecord> events = List.of(new DomainEventRecord("TEST_TENANT","AccountCreated",1,objectMapper.writeValueAsBytes(new AccountCreatedEvent(userId,"NL", "Aike","Christianen","aike.christianen@gmail.com")), PayloadEncoding.JSON,userId,null,0));
            CommandResponseRecord crr = new CommandResponseRecord("TEST_TENANT",userId,cr.correlationId(),cr.id(),events,null);

            testProducer.send(new ProducerRecord<>(commandResponsesPartition.topic(), commandResponsesPartition.partition(), crr.commandId(), crr));
            testProducer.commitTransaction();
        }

        CountDownLatch waitLatch = new CountDownLatch(1);
        result.whenComplete((s, throwable) -> waitLatch.countDown());
        Assertions.assertTrue(waitLatch.await(10, TimeUnit.SECONDS));
        Assertions.assertTrue(result.toCompletableFuture().isDone());
        Assertions.assertFalse(result.toCompletableFuture().isCompletedExceptionally());
        Assertions.assertNotNull(result.toCompletableFuture().getNow(null));
        Assertions.assertEquals(1, result.toCompletableFuture().getNow(null).size());
        AccountCreatedEvent ace = (AccountCreatedEvent) result.toCompletableFuture().getNow(null).getFirst();
        Assertions.assertEquals(userId, ace.userId());
        Assertions.assertEquals("NL", ace.country());
        Assertions.assertEquals("Aike", ace.firstName());
        Assertions.assertEquals("Christianen", ace.lastName());
        Assertions.assertEquals("aike.christianen@gmail.com", ace.email());
    }

    @Test
    public void testGDPRDecryption() throws InterruptedException, JsonProcessingException {
        // make sure it's running
        while(!akcesClient.isRunning()) {
            Thread.onSpinWait();
        }
        String userId = "4da5341a-1f69-4995-b5dc-ecd492e940a0";
        CompletionStage<List<DomainEvent>> result = akcesClient.send("TEST_TENANT",
                new CreateAccountCommand(userId,
                        "NL",
                        "Aike",
                        "Christianen",
                        "aike.christianen@gmail.com"));

        // send the command response
        try (
                Producer<String, ProtocolRecord> testProducer = producerFactory.createProducer("test");
                Consumer<String, ProtocolRecord> testConsumer = consumerFactory.createConsumer("Test", "test")
        ) {
            // we need to get the command to know the commandId
            TopicPartition commandResponsesPartition = akcesClient.getCommandResponsePartition();
            TopicPartition commandPartition = new TopicPartition("Account-Commands", akcesClient.resolvePartition(userId));
            testConsumer.assign(List.of(commandPartition));
            testConsumer.seekToBeginning(List.of(commandPartition));

            ConsumerRecords<String, ProtocolRecord> records = testConsumer.poll(Duration.ofMillis(250));
            List<ProtocolRecord> allRecords = new ArrayList<>();
            // we should have 1 record
            while(allRecords.isEmpty()) {
                records.forEach(record -> allRecords.add(record.value()));
                // wait for the events to be produced
                records = testConsumer.poll(Duration.ofMillis(250));
            }
            Assertions.assertEquals(1, allRecords.size());
            CommandRecord cr = (CommandRecord) allRecords.getFirst();

            SecretKeySpec secretKeySpec = GDPRKeyUtils.createKey();
            EncryptingGDPRContext gdprContext = new EncryptingGDPRContext(userId,secretKeySpec.getEncoded(),GDPRKeyUtils.isUUID(userId));
            String encryptedFirstName = gdprContext.encrypt("Aike");
            String encryptedLastName = gdprContext.encrypt("Christianen");
            String encryptedEmail = gdprContext.encrypt("aike.christianen@gmail.com");

            testProducer.beginTransaction();
            List<DomainEventRecord> events = List.of(new DomainEventRecord("TEST_TENANT","AccountCreated",1,objectMapper.writeValueAsBytes(new AccountCreatedEvent(userId,"NL", encryptedFirstName,encryptedLastName,encryptedEmail)), PayloadEncoding.JSON,userId,null,0));
            CommandResponseRecord crr = new CommandResponseRecord("TEST_TENANT",userId,cr.correlationId(),cr.id(),events,secretKeySpec.getEncoded());

            testProducer.send(new ProducerRecord<>(commandResponsesPartition.topic(), commandResponsesPartition.partition(), crr.commandId(), crr));
            testProducer.commitTransaction();
        }

        CountDownLatch waitLatch = new CountDownLatch(1);
        result.whenComplete((s, throwable) -> waitLatch.countDown());
        Assertions.assertTrue(waitLatch.await(10, TimeUnit.SECONDS));
        Assertions.assertTrue(result.toCompletableFuture().isDone());
        Assertions.assertFalse(result.toCompletableFuture().isCompletedExceptionally());
        Assertions.assertNotNull(result.toCompletableFuture().getNow(null));
        Assertions.assertEquals(1, result.toCompletableFuture().getNow(null).size());
        AccountCreatedEvent ace = (AccountCreatedEvent) result.toCompletableFuture().getNow(null).getFirst();
        Assertions.assertEquals(userId, ace.userId());
        Assertions.assertEquals("NL", ace.country());
        Assertions.assertEquals("Aike", ace.firstName());
        Assertions.assertEquals("Christianen", ace.lastName());
        Assertions.assertEquals("aike.christianen@gmail.com", ace.email());
    }
}
