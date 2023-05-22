package org.elasticsoftware.akces;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.kafka.CustomKafkaConsumerFactory;
import org.elasticsoftware.akces.kafka.CustomKafkaProducerFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
import org.elasticsoftware.akces.state.RocksDBAggregateStateRepositoryFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

@SpringBootApplication(exclude = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(KafkaProperties.class)
public class AggregateServiceApplication {
    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();

    @Bean
    public AkcesControlRecordSerde akcesControlRecordSerde(ObjectMapper objectMapper) {
        return new AkcesControlRecordSerde(objectMapper);
    }

    @Bean
    public KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrapServers}") String bootstrapServers) {
        return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }
    @Bean
    public SchemaRegistryClient schemaRegistryClient(@Value("${kafka.schemaregistry.url}") String url) {
        return new CachedSchemaRegistryClient(url, 1000, List.of(new JsonSchemaProvider()), null);
    }

    @Bean
    public ConsumerFactory<String, ProtocolRecord> consumerFactory(KafkaProperties properties) {
        return new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties(), new StringDeserializer(), serde.deserializer());
    }

    @Bean
    public ProducerFactory<String, ProtocolRecord> producerFactory(KafkaProperties properties) {
        return new CustomKafkaProducerFactory<>(properties.buildProducerProperties(), new StringSerializer(), serde.serializer());
    }

    @Bean
    public ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory(KafkaProperties properties, AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(), new StringDeserializer(), controlSerde.deserializer());
    }

    @Bean
    public ProducerFactory<String, AkcesControlRecord> controlProducerFactory(KafkaProperties properties, AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaProducerFactory<>(properties.buildProducerProperties(), new StringSerializer(), controlSerde.serializer());
    }

    @Bean
    public AggregateStateRepositoryFactory aggregateStateRepositoryFactory(@Value("${akces.rocksdb.baseDir}") String baseDir) {
        return new RocksDBAggregateStateRepositoryFactory(serde, baseDir);
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AggregateServiceApplication.class);
        if(args.length > 0) {
            // this should be a package to scan
            application.setSources(Set.of(args[0]));
        }
        application.run(args);
    }
}
