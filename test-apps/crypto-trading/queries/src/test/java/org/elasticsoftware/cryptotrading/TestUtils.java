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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.springframework.context.ApplicationContextException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class TestUtils {
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
                createTopic("CryptoMarket-Commands", 3),
                createTopic("CryptoMarket-DomainEvents", 3),
                createCompactedTopic("Wallet-AggregateState", 3),
                createCompactedTopic("Account-AggregateState", 3),
                createCompactedTopic("OrderProcessManager-AggregateState", 3),
                createCompactedTopic("CryptoMarket-AggregateState", 3));
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

    public static void prepareAggregateServiceRecords(String bootstrapServers) throws IOException {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        builder.modulesToInstall(new AkcesGDPRModule());
        builder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        ObjectMapper objectMapper = builder.build();
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
            AggregateServiceRecord accountServiceRecord = objectMapper.readValue("{\"aggregateName\":\"Account\",\"commandTopic\":\"Account-Commands\",\"domainEventTopic\":\"Account-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"CreateAccount\",\"version\":1,\"create\":true,\"schemaName\":\"commands.CreateAccount\"}],\"producedEvents\":[{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"},{\"typeName\":\"AccountCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.AccountCreated\"},{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"}],\"consumedEvents\":[]}", AggregateServiceRecord.class);
            AggregateServiceRecord orderProcessManagerServiceRecord = objectMapper.readValue("{\"aggregateName\":\"OrderProcessManager\",\"commandTopic\":\"OrderProcessManager-Commands\",\"domainEventTopic\":\"OrderProcessManager-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"RejectOrder\",\"version\":1,\"create\":false,\"schemaName\":\"commands.RejectOrder\"},{\"typeName\":\"PlaceBuyOrder\",\"version\":1,\"create\":false,\"schemaName\":\"commands.PlaceBuyOrder\"}],\"producedEvents\":[{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"},{\"typeName\":\"UserOrderProcessesCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.UserOrderProcessesCreated\"},{\"typeName\":\"BuyOrderCreated\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BuyOrderCreated\"},{\"typeName\":\"BuyOrderRejected\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BuyOrderRejected\"},{\"typeName\":\"BuyOrderPlaced\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BuyOrderPlaced\"},{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"}],\"consumedEvents\":[{\"typeName\":\"AccountCreated\",\"version\":1,\"create\":true,\"external\":true,\"schemaName\":\"domainevents.AccountCreated\"},{\"typeName\":\"MarketOrderRejected\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.MarketOrderRejected\"},{\"typeName\":\"AmountReserved\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.AmountReserved\"},{\"typeName\":\"InvalidCryptoCurrencyError\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.InvalidCryptoCurrencyError\"},{\"typeName\":\"InsufficientFundsError\",\"version\":1,\"create\":false,\"external\":true,\"schemaName\":\"domainevents.InsufficientFundsError\"}]}", AggregateServiceRecord.class);
            AggregateServiceRecord walletServiceRecord = objectMapper.readValue("{\"aggregateName\":\"Wallet\",\"commandTopic\":\"Wallet-Commands\",\"domainEventTopic\":\"Wallet-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"CreditWallet\",\"version\":1,\"create\":false,\"schemaName\":\"commands.CreditWallet\"},{\"typeName\":\"CreateWallet\",\"version\":1,\"create\":true,\"schemaName\":\"commands.CreateWallet\"},{\"typeName\":\"ReserveAmount\",\"version\":1,\"create\":false,\"schemaName\":\"commands.ReserveAmount\"},{\"typeName\":\"CreateBalance\",\"version\":1,\"create\":false,\"schemaName\":\"commands.CreateBalance\"}],\"producedEvents\":[{\"typeName\":\"InvalidAmountError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.InvalidAmountError\"},{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"},{\"typeName\":\"BalanceAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BalanceAlreadyExistsError\"},{\"typeName\":\"WalletCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.WalletCreated\"},{\"typeName\":\"InvalidCryptoCurrencyError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.InvalidCryptoCurrencyError\"},{\"typeName\":\"BalanceCreated\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.BalanceCreated\"},{\"typeName\":\"InsufficientFundsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.InsufficientFundsError\"},{\"typeName\":\"AmountReserved\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AmountReserved\"},{\"typeName\":\"WalletCredited\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.WalletCredited\"},{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"}],\"consumedEvents\":[{\"typeName\":\"AccountCreated\",\"version\":1,\"create\":true,\"external\":true,\"schemaName\":\"domainevents.AccountCreated\"}]}", AggregateServiceRecord.class);
            AggregateServiceRecord cryptoMarketServiceRecord = objectMapper.readValue("{\"aggregateName\":\"CryptoMarket\",\"commandTopic\":\"CryptoMarket-Commands\",\"domainEventTopic\":\"CryptoMarket-DomainEvents\",\"supportedCommands\":[{\"typeName\":\"CreateCryptoMarket\",\"version\":1,\"create\":true,\"schemaName\":\"commands.CreateCryptoMarket\"},{\"typeName\":\"PlaceMarketOrder\",\"version\":1,\"create\":false,\"schemaName\":\"commands.PlaceMarketOrder\"}],\"producedEvents\":[{\"typeName\":\"CommandExecutionError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.CommandExecutionError\"},{\"typeName\":\"MarketOrderPlaced\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.MarketOrderPlaced\"},{\"typeName\":\"CryptoMarketCreated\",\"version\":1,\"create\":true,\"external\":false,\"schemaName\":\"domainevents.CryptoMarketCreated\"},{\"typeName\":\"AggregateAlreadyExistsError\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.AggregateAlreadyExistsError\"},{\"typeName\":\"MarketOrderRejected\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.MarketOrderRejected\"},{\"typeName\":\"MarketOrderFilled\",\"version\":1,\"create\":false,\"external\":false,\"schemaName\":\"domainevents.MarketOrderFilled\"}],\"consumedEvents\":[]}", AggregateServiceRecord.class);
            controlProducer.beginTransaction();
            for (int partition = 0; partition < 3; partition++) {
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "Account", accountServiceRecord));
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "OrderProcessManager", orderProcessManagerServiceRecord));
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "Wallet", walletServiceRecord));
                controlProducer.send(new ProducerRecord<>("Akces-Control", partition, "CryptoMarket", cryptoMarketServiceRecord));
            }
            controlProducer.commitTransaction();
        }
    }

    public static <D extends DomainEvent> void prepareDomainEventSchemas(String url, List<Class<D>> domainEventClasses) {
        SchemaRegistryClient src = new CachedSchemaRegistryClient(url, 100);
        Jackson2ObjectMapperBuilder objectMapperBuilder = new Jackson2ObjectMapperBuilder();
        objectMapperBuilder.modulesToInstall(new AkcesGDPRModule());
        objectMapperBuilder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(objectMapperBuilder.build(),
                SchemaVersion.DRAFT_7,
                OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS,
                JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(new JacksonModule());
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_FIELDS_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_METHOD_RETURN_VALUES_BY_DEFAULT);
        // we need to override the default behavior of the generator to write BigDecimal as type = number
        configBuilder.forTypesInGeneral().withTypeAttributeOverride((collectedTypeAttributes, scope, context) -> {
            if (scope.getType().getTypeName().equals("java.math.BigDecimal")) {
                JsonNode typeNode = collectedTypeAttributes.get("type");
                if (typeNode.isArray()) {
                    ((ArrayNode) collectedTypeAttributes.get("type")).set(0, "string");
                } else
                    collectedTypeAttributes.put("type", "string");
            }
        });
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator jsonSchemaGenerator = new SchemaGenerator(config);
        try {
            for (Class<D> domainEventClass : domainEventClasses) {
                DomainEventInfo info = domainEventClass.getAnnotation(DomainEventInfo.class);
                src.register("domainevents." + info.type(),
                        new JsonSchema(jsonSchemaGenerator.generateSchema(domainEventClass), List.of(), Map.of(), info.version()),
                        info.version(),
                        -1);
            }
        } catch (IOException | RestClientException e) {
            throw new ApplicationContextException("Problem populating SchemaRegistry", e);
        }
    }
}
