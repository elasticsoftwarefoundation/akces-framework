package org.elasticsoftware.akces.aggregate.wallet;

import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "org.elasticsoftware.akces.kafka",
        "org.elasticsoftware.akces.aggregate",
        "org.elasticsoftware.akces.beans"
})
public class WalletConfiguration {
    @Bean
    public SchemaRegistryClient createSchemaRegistryClient() {
        return new MockSchemaRegistryClient();
    }
}
