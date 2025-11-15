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
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.elasticsoftware.akces.beans.AggregateBeanFactoryPostProcessor;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.elasticsoftware.akces.schemas.storage.SchemaStorage;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    @Bean(name = "aggregateServiceSchemaStorage")
    public SchemaStorage createSchemaStorage() {
        // Mock implementation for tests
        return new SchemaStorage() {
            private final Map<String, SchemaRecord> schemas = new ConcurrentHashMap<>();
            
            @Override
            public void saveSchema(String schemaName, JsonSchema schema, int version) {
                String key = schemaName + "-v" + version;
                schemas.put(key, new SchemaRecord(schemaName, version, schema, System.currentTimeMillis()));
            }

            @Override
            public List<SchemaRecord> getSchemas(String schemaName) {
                return schemas.values().stream()
                        .filter(r -> r.schemaName().equals(schemaName))
                        .sorted((a, b) -> Integer.compare(a.version(), b.version()))
                        .toList();
            }

            @Override
            public Optional<SchemaRecord> getSchema(String schemaName, int version) {
                String key = schemaName + "-v" + version;
                return Optional.ofNullable(schemas.get(key));
            }

            @Override
            public void initialize() {
                // No-op for mock
            }

            @Override
            public void process() {
                // No-op for mock
            }

            @Override
            public void close() {
                // No-op for mock
            }
        };
    }

    @Bean(name = "aggregateServiceSchemaRegistry")
    public SchemaRegistry createSchemaRegistry(@Qualifier("aggregateServiceSchemaStorage") SchemaStorage storage,
                                               ObjectMapper objectMapper) {
        return new KafkaSchemaRegistry(storage, objectMapper);
    }
}
