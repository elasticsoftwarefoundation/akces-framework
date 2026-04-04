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

package org.elasticsoftware.akces.query.reflector;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.module.SimpleModule;
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
import org.elasticsoftware.akces.query.reflector.beans.ReflectorBeanFactoryPostProcessor;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.elasticsoftware.akces.serialization.SchemaRecordSerde;
import org.elasticsoftware.akces.util.EnvironmentPropertiesPrinter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.math.BigDecimal;

@Configuration
@PropertySource("classpath:akces-reflector.properties")
public class AkcesReflectorAutoConfiguration {
    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();

    @Conditional(ReflectorImplementationPresentCondition.class)
    @Bean(name = "reflectorBeanFactoryPostProcessor")
    public static ReflectorBeanFactoryPostProcessor reflectorBeanFactoryPostProcessor() {
        return new ReflectorBeanFactoryPostProcessor();
    }

    @Bean(name = "akcesReflectorJsonCustomizer")
    public JsonMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(BigDecimal.class, new BigDecimalSerializer());
            builder.addModule(new AkcesGDPRModule());
            builder.addModule(module);
        };
    }

    @ConditionalOnBean(ReflectorBeanFactoryPostProcessor.class)
    @Bean(name = "akcesReflectorSchemaRecordSerde")
    public SchemaRecordSerde schemaRecordSerde(ObjectMapper objectMapper) {
        return new SchemaRecordSerde(objectMapper);
    }

    @ConditionalOnBean(ReflectorBeanFactoryPostProcessor.class)
    @Bean(name = "akcesReflectorSchemaConsumerFactory")
    public ConsumerFactory<String, SchemaRecord> schemaConsumerFactory(
            KafkaProperties properties,
            @Qualifier("akcesReflectorSchemaRecordSerde") SchemaRecordSerde serde) {
        return new CustomKafkaConsumerFactory<>(
                properties.buildConsumerProperties(),
                new StringDeserializer(),
                serde.deserializer());
    }

    @ConditionalOnBean(ReflectorBeanFactoryPostProcessor.class)
    @Bean(name = "akcesReflectorConsumerFactory")
    public ConsumerFactory<String, ProtocolRecord> consumerFactory(KafkaProperties properties) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(), new StringDeserializer(), serde.deserializer());
    }

    @ConditionalOnBean(ReflectorBeanFactoryPostProcessor.class)
    @Bean(name = "akcesReflectorProducerFactory")
    public ProducerFactory<String, ProtocolRecord> producerFactory(KafkaProperties properties) {
        CustomKafkaProducerFactory<String, ProtocolRecord> factory =
                new CustomKafkaProducerFactory<>(properties.buildProducerProperties(), new StringSerializer(), serde.serializer());
        factory.setTransactionIdPrefix("");
        return factory;
    }

    @ConditionalOnBean(ReflectorBeanFactoryPostProcessor.class)
    @Bean(name = "akcesReflectorGDPRContextRepositoryFactory")
    public GDPRContextRepositoryFactory gdprContextRepositoryFactory(@Value("${akces.rocksdb.baseDir:/tmp/akces}") String baseDir) {
        return new RocksDBGDPRContextRepositoryFactory(serde, baseDir);
    }

    @ConditionalOnBean(ReflectorBeanFactoryPostProcessor.class)
    @Bean(name = "akcesReflectorControlRecordSerde")
    public AkcesControlRecordSerde akcesControlRecordSerde(ObjectMapper objectMapper) {
        return new AkcesControlRecordSerde(objectMapper);
    }

    @ConditionalOnBean(ReflectorBeanFactoryPostProcessor.class)
    @Bean(name = "akcesReflectorControlConsumerFactory")
    public ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory(KafkaProperties properties,
                                                                               @Qualifier("akcesReflectorControlRecordSerde") AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(), new StringDeserializer(), controlSerde.deserializer());
    }

    @ConditionalOnMissingBean(EnvironmentPropertiesPrinter.class)
    @Bean(name = "environmentPropertiesPrinter")
    public EnvironmentPropertiesPrinter environmentPropertiesPrinter() {
        return new EnvironmentPropertiesPrinter();
    }
}
