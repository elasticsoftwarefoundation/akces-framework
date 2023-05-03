package org.elasticsoftware.akces.kafka;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

//@Configuration
public class SchemaRegistryClientConfiguration {
    @Bean
    @ConditionalOnProperty(name = "kafka.schemaregistry.url", matchIfMissing = true)
    public SchemaRegistryClient createMockSchemaRegistryClient() {
        return new MockSchemaRegistryClient();
    }
    @Bean
    @ConditionalOnProperty(name = "kafka.schemaregistry.url", havingValue = "", matchIfMissing = false)
    public SchemaRegistryClient createSchemaRegistryClient(@Value("${kafka.schemaregistry.url}") String url) {
        return new CachedSchemaRegistryClient(url, 1000);
    }
}
