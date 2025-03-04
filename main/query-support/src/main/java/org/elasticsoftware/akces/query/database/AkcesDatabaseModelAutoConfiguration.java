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

package org.elasticsoftware.akces.query.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.gdpr.RocksDBGDPRContextRepositoryFactory;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.kafka.CustomKafkaConsumerFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.query.database.beans.DatabaseModelBeanFactoryPostProcessor;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.elasticsoftware.akces.util.EnvironmentPropertiesPrinter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.core.ConsumerFactory;

import java.math.BigDecimal;
import java.util.List;

@Configuration
@PropertySource("classpath:akces-databasemodel.properties")
public class AkcesDatabaseModelAutoConfiguration {
    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();

    @Conditional(DatabaseModelImplementationPresentCondition.class)
    @Bean(name = "databaseModelBeanFactoryPostProcessor")
    public static DatabaseModelBeanFactoryPostProcessor databaseModelBeanFactoryPostProcessor() {
        return new DatabaseModelBeanFactoryPostProcessor();
    }

    @Bean(name = "akcesDatabaseModelJsonCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.modulesToInstall(new AkcesGDPRModule());
            builder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        };
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelSchemaRegistryClient")
    public SchemaRegistryClient createSchemaRegistryClient(@Value("${akces.schemaregistry.url:http://localhost:8081}") String url) {
        return new CachedSchemaRegistryClient(url, 1000, List.of(new JsonSchemaProvider()), null);
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelSchemaRegistry")
    public KafkaSchemaRegistry createSchemaRegistry(@Qualifier("akcesDatabaseModelSchemaRegistryClient") SchemaRegistryClient schemaRegistryClient, ObjectMapper objectMapper) {
        return new KafkaSchemaRegistry(schemaRegistryClient, objectMapper);
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelConsumerFactory")
    public ConsumerFactory<String, ProtocolRecord> consumerFactory(KafkaProperties properties) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), serde.deserializer());
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelControlRecordSerde")
    public AkcesControlRecordSerde akcesControlRecordSerde(ObjectMapper objectMapper) {
        return new AkcesControlRecordSerde(objectMapper);
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelControlConsumerFactory")
    public ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory(KafkaProperties properties,
                                                                              @Qualifier("akcesDatabaseModelControlRecordSerde") AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), controlSerde.deserializer());
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelGDPRContextRepositoryFactory")
    public GDPRContextRepositoryFactory gdprContextRepositoryFactory(@Value("${akces.rocksdb.baseDir:/tmp/akces}") String baseDir) {
        return new RocksDBGDPRContextRepositoryFactory(serde, baseDir);
    }

    @ConditionalOnMissingBean(EnvironmentPropertiesPrinter.class)
    @Bean(name = "environmentPropertiesPrinter")
    public EnvironmentPropertiesPrinter environmentPropertiesPrinter() {
        return new EnvironmentPropertiesPrinter();
    }
}
