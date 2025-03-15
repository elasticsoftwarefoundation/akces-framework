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

package org.elasticsoftware.akcestest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.elasticsoftware.akces.beans.AggregateBeanFactoryPostProcessor;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.math.BigDecimal;

@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "org.elasticsoftware.akcestest.aggregate.account",
})
public class AccountConfiguration {
    @Bean(name = "aggregateServiceBeanFactoryPostProcessor")
    public AggregateBeanFactoryPostProcessor aggregateBeanFactoryPostProcessor() {
        return new AggregateBeanFactoryPostProcessor();
    }

    @Bean(name = "aggregateServiceJsonCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.modulesToInstall(new AkcesGDPRModule());
            builder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        };
    }

    @Bean(name = "aggregateServiceSchemaRegistryClient")
    public SchemaRegistryClient createSchemaRegistryClient() {
        return new MockSchemaRegistryClient();
    }

    @Bean(name = "aggregateServiceSchemaRegistry")
    public KafkaSchemaRegistry createSchemaRegistry(@Qualifier("aggregateServiceSchemaRegistryClient") SchemaRegistryClient src,
                                                    ObjectMapper objectMapper) {
        return new KafkaSchemaRegistry(src, objectMapper);
    }
}
