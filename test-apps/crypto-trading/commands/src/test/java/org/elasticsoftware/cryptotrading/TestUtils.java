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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akces.serialization.SchemaRecordSerde;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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

    public static void ensureAggregateServiceRecordsExist(ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory) {
        try (Consumer<String, AkcesControlRecord> controlConsumer = controlConsumerFactory.createConsumer("Test-AkcesControl", "test-akces-control")) {
            TopicPartition controlPartition = new TopicPartition("Akces-Control", 0);
            controlConsumer.assign(List.of(controlPartition));
            controlConsumer.seekToBeginning(controlConsumer.assignment());

            Map<String, AggregateServiceRecord> serviceRecords = new HashMap<>();

            while (serviceRecords.size() < 4) {
                ConsumerRecords<String, AkcesControlRecord> controlRecords = controlConsumer.poll(Duration.ofMillis(1000));
                if (!controlRecords.isEmpty()) {
                    for (ConsumerRecord<String, AkcesControlRecord> record : controlRecords.records(controlPartition)) {
                        if (record.value() instanceof AggregateServiceRecord aggregateServiceRecord) {
                            serviceRecords.put(record.key(), aggregateServiceRecord);
                        }
                    }
                }
            }

        }
    }

    public static void prepareDomainEventSchemas(String bootstrapServers, String basePackage) {
        Jackson2ObjectMapperBuilder objectMapperBuilder = new Jackson2ObjectMapperBuilder();
        objectMapperBuilder.modulesToInstall(new AkcesGDPRModule());
        objectMapperBuilder.serializerByType(BigDecimal.class, new BigDecimalSerializer());

        // Write schemas to Akces-Schemas topic
        ObjectMapper mapper = objectMapperBuilder.build();
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
                String key = eventType.getSchemaName() + "-v" + info.version();
                ProducerRecord<String, SchemaRecord> producerRecord = new ProducerRecord<>("Akces-Schemas", key, record);
                producer.send(producerRecord).get();
            }
            producer.flush();
        } catch (Exception e) {
            throw new ApplicationContextException("Problem populating SchemaRegistry", e);
        }
    }

    public static void prepareCommandSchemas(String bootstrapServers, String basePackage) {
        Jackson2ObjectMapperBuilder objectMapperBuilder = new Jackson2ObjectMapperBuilder();
        objectMapperBuilder.modulesToInstall(new AkcesGDPRModule());
        objectMapperBuilder.serializerByType(BigDecimal.class, new BigDecimalSerializer());

        // Write schemas to Akces-Schemas topic
        ObjectMapper mapper = objectMapperBuilder.build();
        SchemaRecordSerde serde = new SchemaRecordSerde(mapper);
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");

        SchemaGenerator jsonSchemaGenerator = SchemaRegistry.createJsonSchemaGenerator(mapper);

        // Scan for classes annotated with @CommandInfo
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(CommandInfo.class));

        try (Producer<String, SchemaRecord> producer = new KafkaProducer<>(producerProps, new StringSerializer(), serde.serializer())) {
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(basePackage)) {
                @SuppressWarnings("unchecked")
                Class<? extends Command> commandClass = (Class<? extends Command>) Class.forName(beanDefinition.getBeanClassName());
                CommandInfo info = commandClass.getAnnotation(CommandInfo.class);
                CommandType<?> eventType = new CommandType<>(info.type(), info.version(), commandClass, false, true, false);
                JsonSchema schema = SchemaRegistry.generateJsonSchema(eventType, jsonSchemaGenerator);
                SchemaRecord record = new SchemaRecord(eventType.getSchemaName(), eventType.version(), schema, System.currentTimeMillis());
                String key = eventType.getSchemaName() + "-v" + info.version();
                ProducerRecord<String, SchemaRecord> producerRecord = new ProducerRecord<>("Akces-Schemas", key, record);
                producer.send(producerRecord).get();
            }
            producer.flush();
        } catch (Exception e) {
            throw new ApplicationContextException("Problem populating SchemaRegistry", e);
        }
    }
}
