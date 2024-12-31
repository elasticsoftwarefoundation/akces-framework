/*
 * Copyright 2022 - 2023 The Original Authors
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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.kafka.CustomKafkaConsumerFactory;
import org.elasticsoftware.akces.kafka.CustomKafkaProducerFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
import org.elasticsoftware.akces.state.RocksDBAggregateStateRepositoryFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;

import java.util.List;
import java.util.Map;

@Configuration
@EnableAutoConfiguration
@EnableConfigurationProperties(KafkaProperties.class)
@ComponentScan(basePackages = {
        "org.elasticsoftware.akcestest.aggregate",
        "org.elasticsoftware.akces.beans",
})
@PropertySource("classpath:akces-aggregateservice.properties")
public class RuntimeConfiguration {
    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();

    @Bean(name = "aggregateServiceControlRecordSerde")
    public AkcesControlRecordSerde controlSerde(ObjectMapper objectMapper) {
        return new AkcesControlRecordSerde(objectMapper);
    }

    @Bean(name = "aggregateServiceJsonCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder.modulesToInstall(new AkcesGDPRModule());
    }


    @Bean(name = "aggregateServiceKafkaAdmin")
    public KafkaAdmin createKafkaAdmin(@Value("${spring.kafka.bootstrapServers}") String bootstrapServers) {
        return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }
    @Bean(name = "aggregateServiceSchemaRegistryClient")
    public SchemaRegistryClient createSchemaRegistryClient(@Value("${kafka.schemaregistry.url}") String url) {
        return new CachedSchemaRegistryClient(url, 1000, List.of(new JsonSchemaProvider()), null);
    }

    @Bean(name = "aggregateServiceConsumerFactory")
    public ConsumerFactory<String, ProtocolRecord> consumerFactory(KafkaProperties properties) {
        return new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), serde.deserializer());
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
    public AggregateStateRepositoryFactory aggregateStateRepositoryFactory() {
        //return new InMemoryAggregateStateRepositoryFactory();
        return new RocksDBAggregateStateRepositoryFactory(serde, "/tmp/akces");
    }
}
