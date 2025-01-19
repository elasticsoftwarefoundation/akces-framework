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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.kafka.CustomKafkaConsumerFactory;
import org.elasticsoftware.akces.kafka.CustomKafkaProducerFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaAdminOperations;
import org.springframework.kafka.core.ProducerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Configuration
@PropertySource("classpath:akces-client.properties")
public class AkcesClientAutoConfiguration {
    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();

    public AkcesClientAutoConfiguration() {
    }

    @Bean(name = "akcesClientControlSerde")
    public AkcesControlRecordSerde controlSerde(ObjectMapper objectMapper) {
        return new AkcesControlRecordSerde(objectMapper);
    }

    @Bean(name = "akcesClientJsonCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.modulesToInstall(new AkcesGDPRModule());
            builder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        };
    }

    @Bean(name = "akcesClientKafkaAdmin")
    public KafkaAdmin createKafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    @Bean(name = "akcesClientSchemaRegistryClient")
    public SchemaRegistryClient createSchemaRegistryClient(@Value("${kafka.schemaregistry.url}") String url) {
        return new CachedSchemaRegistryClient(url, 1000, List.of(new JsonSchemaProvider()), null);
    }

    @Bean(name = "akcesClientSchemaRegistry")
    public KafkaSchemaRegistry createSchemaRegistry(@Qualifier("akcesClientSchemaRegistryClient") SchemaRegistryClient schemaRegistryClient, ObjectMapper objectMapper) {
        return new KafkaSchemaRegistry(schemaRegistryClient, objectMapper);
    }

    @Bean(name = "akcesClientProducerFactory")
    public ProducerFactory<String, ProtocolRecord> producerFactory(KafkaProperties properties) {
        return new CustomKafkaProducerFactory<>(properties.buildProducerProperties(null), new StringSerializer(), serde.serializer());
    }

    @Bean(name = "akcesClientControlConsumerFactory")
    public ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory(KafkaProperties properties,
                                                                              @Qualifier("akcesClientControlSerde") AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), controlSerde.deserializer());
    }

    @Bean(name = "akcesClientCommandResponseConsumerFactory")
    public ConsumerFactory<String, ProtocolRecord> commandResponseConsumerFactory(KafkaProperties properties) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), serde.deserializer());
    }

    @Bean(name = "domainEventScanner")
    public ClassPathScanningCandidateComponentProvider domainEventScanner(Environment environment) {
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(DomainEventInfo.class));
        provider.setEnvironment(environment);
        return provider;
    }

    @Bean(name = "akcesClient", initMethod = "start", destroyMethod = "close")
    public AkcesClientController akcesClient(@Qualifier("akcesClientProducerFactory") ProducerFactory<String, ProtocolRecord> producerFactory,
                                             @Qualifier("akcesClientControlConsumerFactory") ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory,
                                             @Qualifier("akcesClientCommandResponseConsumerFactory") ConsumerFactory<String, ProtocolRecord> commandResponseConsumerFactory,
                                             @Qualifier("akcesClientKafkaAdmin") KafkaAdminOperations kafkaAdmin,
                                             @Qualifier("akcesClientSchemaRegistry") KafkaSchemaRegistry schemaRegistry,
                                             ObjectMapper objectMapper,
                                             @Qualifier("domainEventScanner") ClassPathScanningCandidateComponentProvider domainEventScanner,
                                             @Value("${akces.client.domainEventsPackage}") String domainEventsPackage) {
        return new AkcesClientController(producerFactory,
                controlConsumerFactory,
                commandResponseConsumerFactory,
                kafkaAdmin,
                schemaRegistry,
                objectMapper,
                domainEventScanner,
                domainEventsPackage);
    }
}
