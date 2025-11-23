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

package org.elasticsoftware.akces.query.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.gdpr.RocksDBGDPRContextRepositoryFactory;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.kafka.CustomKafkaConsumerFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.query.models.beans.QueryModelBeanFactoryPostProcessor;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.elasticsoftware.akces.serialization.SchemaRecordSerde;
import org.elasticsoftware.akces.util.EnvironmentPropertiesPrinter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.math.BigDecimal;
import java.util.Map;

@Configuration
@PropertySource("classpath:akces-querymodel.properties")
public class AkcesQueryModelAutoConfiguration {
    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();

    @Conditional(QueryModelImplementationPresentCondition.class)
    @Bean(name = "queryModelBeanFactoryPostProcessor")
    public static QueryModelBeanFactoryPostProcessor queryModelBeanFactoryPostProcessor() {
        return new QueryModelBeanFactoryPostProcessor();
    }

    @Bean(name = "akcesQueryModelJsonCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.modulesToInstall(new AkcesGDPRModule());
            builder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        };
    }

    @ConditionalOnBean(QueryModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesQueryModelSchemaRecordSerde")
    public SchemaRecordSerde schemaRecordSerde(ObjectMapper objectMapper) {
        return new SchemaRecordSerde(objectMapper);
    }

    @ConditionalOnBean(QueryModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesQueryModelSchemaConsumerFactory")
    public ConsumerFactory<String, SchemaRecord> schemaConsumerFactory(
            KafkaProperties properties,
            @Qualifier("akcesQueryModelSchemaRecordSerde") SchemaRecordSerde serde) {
        return new CustomKafkaConsumerFactory<>(
                properties.buildConsumerProperties(),
                new StringDeserializer(),
                serde.deserializer());
    }

    @ConditionalOnBean(QueryModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesQueryModelConsumerFactory")
    public ConsumerFactory<String, ProtocolRecord> consumerFactory(KafkaProperties properties) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(), new StringDeserializer(), serde.deserializer());
    }

    @ConditionalOnBean(QueryModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesQueryModelGDPRContextRepositoryFactory")
    public GDPRContextRepositoryFactory gdprContextRepositoryFactory(@Value("${akces.rocksdb.baseDir:/tmp/akces}") String baseDir) {
        return new RocksDBGDPRContextRepositoryFactory(serde, baseDir);
        //return new InMemoryGDPRContextRepositoryFactory();
    }

    @ConditionalOnBean(QueryModelBeanFactoryPostProcessor.class)
    @Bean(name = "akcesQueryModelKafkaAdmin")
    public KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    @ConditionalOnBean(QueryModelBeanFactoryPostProcessor.class)
    @Bean(name = "ackesQueryModelController", initMethod = "start", destroyMethod = "close")
    public AkcesQueryModelController queryModelRuntimes(@Qualifier("akcesQueryModelKafkaAdmin") KafkaAdmin kafkaAdmin,
                                                        @Qualifier("akcesQueryModelConsumerFactory") ConsumerFactory<String, ProtocolRecord> consumerFactory,
                                                        @Qualifier("akcesQueryModelSchemaConsumerFactory") ConsumerFactory<String, SchemaRecord> schemaConsumerFactory,
                                                        @Qualifier("akcesQueryModelGDPRContextRepositoryFactory") GDPRContextRepositoryFactory gdprContextRepositoryFactory,
                                                        ObjectMapper objectMapper) {
        return new AkcesQueryModelController(kafkaAdmin, consumerFactory, schemaConsumerFactory, gdprContextRepositoryFactory, objectMapper);
    }

    @ConditionalOnMissingBean(EnvironmentPropertiesPrinter.class)
    @Bean(name = "environmentPropertiesPrinter")
    public EnvironmentPropertiesPrinter environmentPropertiesPrinter() {
        return new EnvironmentPropertiesPrinter();
    }
}
