package org.elasticsoftware.akces.kafka;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

@Factory
public class SchemaRegistryClientFactory {
    @Bean
    @Singleton
    @Requires(missingProperty = "kafka.schemaregistry.url")
    public SchemaRegistryClient createMockSchemaRegistryClient() {
        return new MockSchemaRegistryClient();
    }
    @Bean
    @Singleton
    @Requires(property = "kafka.schemaregistry.url")
    public SchemaRegistryClient createSchemaRegistryClient(@Value("${kafka.schemaregistry.url}") String url) {
        return new CachedSchemaRegistryClient(url, 1000);
    }
}
