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

package org.elasticsoftware.akces;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.beans.AggregateBeanFactoryPostProcessor;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.kafka.CustomKafkaConsumerFactory;
import org.elasticsoftware.akces.kafka.CustomKafkaProducerFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
import org.elasticsoftware.akces.state.RocksDBAggregateStateRepositoryFactory;
import org.elasticsoftware.akces.util.EnvironmentPropertiesPrinter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SpringBootApplication(exclude = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(KafkaProperties.class)
@PropertySource("classpath:akces-aggregateservice.properties")
public class AggregateServiceApplication {
    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AggregateServiceApplication.class);
        if (args.length > 0) {
            // this should be a package to scan
            application.setSources(Set.of(args));
        }
        application.run();
    }

    @Bean(name = "aggregateServiceBeanFactoryPostProcessor")
    public static AggregateBeanFactoryPostProcessor aggregateBeanFactoryPostProcessor() {
        return new AggregateBeanFactoryPostProcessor();
    }

    @Bean(name = "aggregateServiceJsonCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.modulesToInstall(new AkcesGDPRModule());
            builder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        };
    }

    @Bean(name = "aggregateServiceControlRecordSerde")
    public AkcesControlRecordSerde akcesControlRecordSerde(ObjectMapper objectMapper) {
        return new AkcesControlRecordSerde(objectMapper);
    }

    @Bean(name = "aggregateServiceKafkaAdmin")
    public KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    @Bean(name = "aggregateServiceSchemaRegistryClient")
    public SchemaRegistryClient schemaRegistryClient(@Value("${kafka.schemaregistry.url}") String url) {
        return new CachedSchemaRegistryClient(url, 1000, List.of(new JsonSchemaProvider()), null);
    }

    @Bean(name = "aggregateServiceSchemaRegistry")
    public KafkaSchemaRegistry schemaRegistry(@Qualifier("aggregateServiceSchemaRegistryClient") SchemaRegistryClient schemaRegistryClient,
                                              ObjectMapper objectMapper) {
        return new KafkaSchemaRegistry(schemaRegistryClient, objectMapper);
    }

    @Bean(name = "aggregateServiceConsumerFactory")
    public ConsumerFactory<String, ProtocolRecord> consumerFactory(KafkaProperties properties) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), serde.deserializer());
    }

    @Bean(name = "aggregateServiceProducerFactory")
    public ProducerFactory<String, ProtocolRecord> producerFactory(KafkaProperties properties) {
        return new CustomKafkaProducerFactory<>(properties.buildProducerProperties(null), new StringSerializer(), serde.serializer());
    }

    @Bean(name = "aggregateServiceControlConsumerFactory")
    public ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory(KafkaProperties properties,
                                                                              @Qualifier("aggregateServiceControlRecordSerde") AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), controlSerde.deserializer());
    }

    @Bean(name = "aggregateServiceControlProducerFactory")
    public ProducerFactory<String, AkcesControlRecord> controlProducerFactory(KafkaProperties properties,
                                                                              @Qualifier("aggregateServiceControlRecordSerde") AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaProducerFactory<>(properties.buildProducerProperties(null), new StringSerializer(), controlSerde.serializer());
    }

    @Bean(name = "aggregateStateRepositoryFactory")
    public AggregateStateRepositoryFactory aggregateStateRepositoryFactory(@Value("${akces.rocksdb.baseDir}") String baseDir) {
        return new RocksDBAggregateStateRepositoryFactory(serde, baseDir);
    }

    @Bean(name = "EnvironmentPropertiesPrinter")
    public EnvironmentPropertiesPrinter environmentPropertiesPrinter() {
        return new EnvironmentPropertiesPrinter();
    }
}
