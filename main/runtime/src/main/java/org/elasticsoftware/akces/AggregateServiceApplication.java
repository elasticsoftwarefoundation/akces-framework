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

package org.elasticsoftware.akces;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.beans.AggregateBeanFactoryPostProcessor;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.gdpr.GDPRContextRepositoryFactory;
import org.elasticsoftware.akces.gdpr.RocksDBGDPRContextRepositoryFactory;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.kafka.CustomKafkaConsumerFactory;
import org.elasticsoftware.akces.kafka.CustomKafkaProducerFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.schemas.storage.KafkaTopicSchemaStorage;
import org.elasticsoftware.akces.schemas.storage.KafkaTopicSchemaStorageImpl;
import org.elasticsoftware.akces.serialization.AkcesControlRecordSerde;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;
import org.elasticsoftware.akces.serialization.SchemaRecordSerde;
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
import org.elasticsoftware.akces.state.RocksDBAggregateStateRepositoryFactory;
import org.elasticsoftware.akces.util.EnvironmentPropertiesPrinter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@SpringBootApplication(exclude = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(KafkaProperties.class)
@PropertySource("classpath:akces-aggregateservice.properties")
@PropertySource("classpath:akces-schemas.properties")
public class AggregateServiceApplication {
    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AggregateServiceApplication.class);
        if (args.length > 0) {
            // this should be a package to scan
            application.setSources(Set.of(args));
        }
        application.run();
    }

    @Bean(name = "aggregateServiceBeanFactoryPostProcessor")
    public static AggregateBeanFactoryPostProcessor aggregateBeanFactoryPostProcessor() {
        return new AggregateBeanFactoryPostProcessor();
    }

    @Bean(name = "aggregateServiceJsonCustomizer")
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            builder.modulesToInstall(new AkcesGDPRModule());
            builder.serializerByType(BigDecimal.class, new BigDecimalSerializer());
        };
    }

    @Bean(name = "aggregateServiceControlRecordSerde")
    public AkcesControlRecordSerde akcesControlRecordSerde(ObjectMapper objectMapper) {
        return new AkcesControlRecordSerde(objectMapper);
    }

    @Bean(name = "aggregateServiceKafkaAdmin")
    public KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    @Bean(name = "aggregateServiceSchemaRecordSerde")
    public SchemaRecordSerde schemaRecordSerde(ObjectMapper objectMapper) {
        return new SchemaRecordSerde(objectMapper);
    }

    @Bean(name = "aggregateServiceSchemaProducer")
    public Producer<String, SchemaRecord> schemaProducer(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Qualifier("aggregateServiceSchemaRecordSerde") SchemaRecordSerde serde) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaProducer<>(props, new StringSerializer(), serde.serializer());
    }

    @Bean(name = "aggregateServiceSchemaConsumer")
    public Consumer<String, SchemaRecord> schemaConsumer(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Qualifier("aggregateServiceSchemaRecordSerde") SchemaRecordSerde serde) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "akces-aggregate-service-schema-consumer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(props, new StringDeserializer(), serde.deserializer());
    }

    @Bean(name = "aggregateServiceSchemaAdminClient")
    public AdminClient schemaAdminClient(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return AdminClient.create(props);
    }

    @Bean(name = "aggregateServiceSchemaStorage", initMethod = "initialize", destroyMethod = "close")
    public KafkaTopicSchemaStorage schemaStorage(
            @Value("${akces.schemas.topic}") String schemasTopic,
            @Value("${akces.schemas.replication.factor}") int replicationFactor,
            @Qualifier("aggregateServiceSchemaProducer") Producer<String, SchemaRecord> producer,
            @Qualifier("aggregateServiceSchemaConsumer") Consumer<String, SchemaRecord> consumer,
            @Qualifier("aggregateServiceSchemaAdminClient") AdminClient adminClient) {
        return new KafkaTopicSchemaStorageImpl(
                schemasTopic,
                producer,
                consumer,
                adminClient,
                replicationFactor
        );
    }

    @Bean(name = "aggregateServiceSchemaRegistry")
    public KafkaSchemaRegistry schemaRegistry(
            @Qualifier("aggregateServiceSchemaStorage") KafkaTopicSchemaStorage schemaStorage,
            ObjectMapper objectMapper) {
        return new KafkaSchemaRegistry(schemaStorage, objectMapper);
    }

    @Bean(name = "aggregateServiceConsumerFactory")
    public ConsumerFactory<String, ProtocolRecord> consumerFactory(KafkaProperties properties) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), serde.deserializer());
    }

    @Bean(name = "aggregateServiceProducerFactory")
    public ProducerFactory<String, ProtocolRecord> producerFactory(KafkaProperties properties) {
        return new CustomKafkaProducerFactory<>(properties.buildProducerProperties(null), new StringSerializer(), serde.serializer());
    }

    @Bean(name = "aggregateServiceControlConsumerFactory")
    public ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory(KafkaProperties properties,
                                                                              @Qualifier("aggregateServiceControlRecordSerde") AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaConsumerFactory<>(properties.buildConsumerProperties(null), new StringDeserializer(), controlSerde.deserializer());
    }

    @Bean(name = "aggregateServiceControlProducerFactory")
    public ProducerFactory<String, AkcesControlRecord> controlProducerFactory(KafkaProperties properties,
                                                                              @Qualifier("aggregateServiceControlRecordSerde") AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaProducerFactory<>(properties.buildProducerProperties(null), new StringSerializer(), controlSerde.serializer());
    }

    @Bean(name = "aggregateServiceAggregateStateRepositoryFactory")
    public AggregateStateRepositoryFactory aggregateStateRepositoryFactory(@Value("${akces.rocksdb.baseDir:/tmp/akces}") String baseDir) {
        return new RocksDBAggregateStateRepositoryFactory(serde, baseDir);
    }

    @Bean(name = "aggregateServiceGDPRContextRepositoryFactory")
    public GDPRContextRepositoryFactory gdprContextRepositoryFactory(@Value("${akces.rocksdb.baseDir:/tmp/akces}") String baseDir) {
        return new RocksDBGDPRContextRepositoryFactory(serde, baseDir);
    }

    @Bean(name = "environmentPropertiesPrinter")
    public EnvironmentPropertiesPrinter environmentPropertiesPrinter() {
        return new EnvironmentPropertiesPrinter();
    }
}
