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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.gdpr.RocksDBGDPRContextRepositoryFactory;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.kafka.CustomKafkaConsumerFactory;
import org.elasticsoftware.akces.kafka.CustomKafkaProducerFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.query.database.beans.DatabaseModelBeanFactoryPostProcessor;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.elasticsoftware.akces.schemas.storage.KafkaTopicSchemaStorage;
import org.elasticsoftware.akces.schemas.storage.SchemaStorage;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.elasticsoftware.akces.serialization.SchemaRecordSerde;
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
import org.springframework.kafka.core.ProducerFactory;

import java.math.BigDecimal;

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
    @Bean(name = "akcesDatabaseModelSchemaRecordSerde")
    public SchemaRecordSerde schemaRecordSerde(ObjectMapper objectMapper) {
        return new SchemaRecordSerde(objectMapper);
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelSchemaProducerFactory")
    public ProducerFactory<String, SchemaRecord> schemaProducerFactory(
            KafkaProperties properties,
            @Qualifier("akcesDatabaseModelSchemaRecordSerde") SchemaRecordSerde serde) {
        return new CustomKafkaProducerFactory<>(
                properties.buildProducerProperties(null),
                new StringSerializer(),
                serde.serializer());
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelSchemaConsumerFactory")
    public ConsumerFactory<String, SchemaRecord> schemaConsumerFactory(
            KafkaProperties properties,
            @Qualifier("akcesDatabaseModelSchemaRecordSerde") SchemaRecordSerde serde) {
        return new CustomKafkaConsumerFactory<>(
                properties.buildConsumerProperties(null),
                new StringDeserializer(),
                serde.deserializer());
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelSchemaStorage", initMethod = "initialize", destroyMethod = "close")
    public SchemaStorage schemaStorage(
            @Qualifier("akcesDatabaseModelSchemaProducerFactory") ProducerFactory<String, SchemaRecord> producerFactory,
            @Qualifier("akcesDatabaseModelSchemaConsumerFactory") ConsumerFactory<String, SchemaRecord> consumerFactory) {
        return new KafkaTopicSchemaStorage(
                null, // read-only mode - no producer needed
                null,
                consumerFactory.createConsumer("akces-database-model-schema", "schema-storage")
        );
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelSchemaRegistry")
    public SchemaRegistry createSchemaRegistry(
            @Qualifier("akcesDatabaseModelSchemaStorage") SchemaStorage schemaStorage,
            ObjectMapper objectMapper) {
        return new KafkaSchemaRegistry(schemaStorage, objectMapper);
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelConsumerFactory")
    public ConsumerFactory<String, ProtocolRecord> consumerFactory(KafkaProperties properties) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), serde.deserializer());
    }

    @ConditionalOnBean(DatabaseModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesDatabaseModelGDPRContextRepositoryFactory")
    public GDPRContextRepositoryFactory gdprContextRepositoryFactory(@Value("${akces.rocksdb.baseDir:/tmp/akces}") String baseDir) {
        return new RocksDBGDPRContextRepositoryFactory(serde, baseDir);
        //return new InMemoryGDPRContextRepositoryFactory();
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

    @ConditionalOnMissingBean(EnvironmentPropertiesPrinter.class)
    @Bean(name = "environmentPropertiesPrinter")
    public EnvironmentPropertiesPrinter environmentPropertiesPrinter() {
        return new EnvironmentPropertiesPrinter();
    }
}
