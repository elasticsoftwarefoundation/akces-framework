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

package org.elasticsoftware.akces.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akces.serialization.SchemaRecordSerde;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.kafka.core.KafkaAdmin;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

public class TestUtils {
    public static void prepareKafka(String bootstrapServers) {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        kafkaAdmin.createOrModifyTopics(
                createCompactedTopic("Akces-Control", 3),
                createTopic("Akces-CommandResponses", 3, 604800000L),
                createCompactedTopic("Akces-GDPRKeys", 3),
                createCompactedTopic("Akces-Schemas", 1),
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
        NewTopic topic = new NewTopic(name, numPartitions, Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy", "delete",
                "max.message.bytes", "20971520",
                "retention.ms", Long.toString(retentionMs),
                "segment.ms", "604800000"));
    }

    private static NewTopic createCompactedTopic(String name, int numPartitions) {
        NewTopic topic = new NewTopic(name, numPartitions, Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy", "compact",
                "max.message.bytes", "20971520",
                "retention.ms", "-1",
                "segment.ms", "604800000",
                "min.cleanable.dirty.ratio", "0.1",
                "delete.retention.ms", "604800000",
                "compression.type", "lz4"));
    }

    public static void prepareDomainEventSchemas(String bootstrapServers, String basePackage) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new AkcesGDPRModule());
        mapper.registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
            .addSerializer(BigDecimal.class, new BigDecimalSerializer()));
        SchemaRecordSerde serde = new SchemaRecordSerde(mapper);
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

       SchemaGenerator jsonSchemaGenerator = SchemaRegistry.createJsonSchemaGenerator(mapper);

        // Scan for classes annotated with @DomainEventInfo
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(DomainEventInfo.class));
        
        try (Producer<String, SchemaRecord> producer = new KafkaProducer<>(producerProps, new StringSerializer(), serde.serializer())) {
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(basePackage)) {
                @SuppressWarnings("unchecked")
                Class<? extends DomainEvent> eventClass = (Class<? extends DomainEvent>) Class.forName(beanDefinition.getBeanClassName());
                DomainEventInfo info = eventClass.getAnnotation(DomainEventInfo.class);
                DomainEventType<?> eventType = new DomainEventType<>(info.type(), info.version(), eventClass, false, true, false, false);
                JsonSchema schema = SchemaRegistry.generateJsonSchema(eventType, jsonSchemaGenerator);
                SchemaRecord record = new SchemaRecord(eventType.getSchemaName(), eventType.version(), schema, System.currentTimeMillis());
                String key = "domainevents." + info.type() + "-v" + info.version();
                ProducerRecord<String, SchemaRecord> producerRecord = new ProducerRecord<>("Akces-Schemas", key, record);
                producer.send(producerRecord).get();
            }
            producer.flush();
        } catch (Exception e) {
            throw new ApplicationContextException("Problem populating SchemaRegistry", e);
        }
    }

    public static void prepareAggregateServiceRecords(String bootstrapServers) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new AkcesGDPRModule());
        objectMapper.registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
            .addSerializer(BigDecimal.class, new BigDecimalSerializer()));
        AkcesControlRecordSerde controlSerde = new AkcesControlRecordSerde(objectMapper);
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
        try (Producer<String, AkcesControlRecord> controlProducer = new KafkaProducer<>(controlProducerProps, new StringSerializer(), controlSerde.serializer())) {
            controlProducer.initTransactions();
            AggregateServiceRecord accountServiceRecord = objectMapper.readValue("{\"aggregateName\":\"Account\",\"commandTopic\":\"Account-Commands\",\"domainEventTopic\":\"Account-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"CreateAccount\",\"version\":1,\"create\":true,\"schemaName\":\"commands.CreateAccount\"}],\"producedEvents\":[{\"typeName\":\"AccountCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.AccountCreated\"},{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"},{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"}],\"consumedEvents\":[]}", AggregateServiceRecord.class);
            AggregateServiceRecord orderProcessManagerServiceRecord = objectMapper.readValue("{\"aggregateName\":\"OrderProcessManager\",\"commandTopic\":\"OrderProcessManager-Commands\",\"domainEventTopic\":\"OrderProcessManager-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"PlaceBuyOrder\",\"version\":1,\"create\":false,\"schemaName\":\"commands.PlaceBuyOrder\"}],\"producedEvents\":[{\"typeName\":\"BuyOrderRejected\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BuyOrderRejected\"},{\"typeName\":\"BuyOrderCreated\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BuyOrderCreated\"},{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"},{\"typeName\":\"UserOrderProcessesCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.UserOrderProcessesCreated\"},{\"typeName\":\"BuyOrderPlaced\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BuyOrderPlaced\"},{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"}],\"consumedEvents\":[{\"typeName\":\"InsufficientFundsError\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.InsufficientFundsError\"},{\"typeName\":\"AccountCreated\",\"version\":1,\"create\":true,\"external\":true,\"schemaName\":\"domainevents.AccountCreated\"},{\"typeName\":\"InvalidCurrencyError\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.InvalidCurrencyError\"},{\"typeName\":\"AmountReserved\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.AmountReserved\"}]}", AggregateServiceRecord.class);
            AggregateServiceRecord walletServiceRecord = objectMapper.readValue("{\"aggregateName\":\"Wallet\",\"commandTopic\":\"Wallet-Commands\",\"domainEventTopic\":\"Wallet-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"ReserveAmount\",\"version\":1,\"create\":false,\"schemaName\":\"commands.ReserveAmount\"},{\"typeName\":\"CreateWallet\",\"version\":1,\"create\":true,\"schemaName\":\"commands.CreateWallet\"},{\"typeName\":\"CreateBalance\",\"version\":1,\"create\":false,\"schemaName\":\"commands.CreateBalance\"},{\"typeName\":\"CreditWallet\",\"version\":1,\"create\":false,\"schemaName\":\"commands.CreditWallet\"}],\"producedEvents\":[{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"},{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"},{\"typeName\":\"BalanceCreated\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BalanceCreated\"},{\"typeName\":\"AmountReserved\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AmountReserved\"},{\"typeName\":\"BalanceAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BalanceAlreadyExistsError\"},{\"typeName\":\"WalletCredited\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.WalletCredited\"},{\"typeName\":\"InsufficientFundsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.InsufficientFundsError\"},{\"typeName\":\"WalletCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.WalletCreated\"},{\"typeName\":\"InvalidCurrencyError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.InvalidCurrencyError\"},{\"typeName\":\"InvalidAmountError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.InvalidAmountError\"}],\"consumedEvents\":[{\"typeName\":\"AccountCreated\",\"version\":1,\"create\":true,\"external\":true,\"schemaName\":\"domainevents.AccountCreated\"}]}", AggregateServiceRecord.class);
            controlProducer.beginTransaction();
            for (int partition = 0; partition < 3; partition++) {
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "Account", accountServiceRecord));
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "OrderProcessManager", orderProcessManagerServiceRecord));
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "Wallet", walletServiceRecord));
            }
            controlProducer.commitTransaction();
        }
    }
}
