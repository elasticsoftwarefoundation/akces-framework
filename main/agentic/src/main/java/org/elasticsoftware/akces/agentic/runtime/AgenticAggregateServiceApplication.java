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

package org.elasticsoftware.akces.agentic.runtime;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.elasticsoftware.akces.agentic.beans.AgenticAggregateBeanFactoryPostProcessor;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.kafka.CustomKafkaConsumerFactory;
import org.elasticsoftware.akces.kafka.CustomKafkaProducerFactory;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.protocol.SchemaRecord;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Spring Boot application entry point for an Akces Agentic Aggregate service.
 *
 * <p>This application class is analogous to
 * {@link org.elasticsoftware.akces.AggregateServiceApplication} but is tailored for
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate} implementations.  Key
 * differences are:
 * <ul>
 *   <li>Loads {@code akces-agenticservice.properties} defaults from the classpath.</li>
 *   <li>Does not use the standard Spring Kafka auto-configuration — all Kafka factories
 *       are wired manually so that transactional producers are configured correctly.</li>
 *   <li>Registers the {@link BigDecimalSerializer} and GDPR Jackson modules.</li>
 * </ul>
 *
 * <p>To start an agentic aggregate service, call:
 * <pre>{@code
 * AgenticAggregateServiceApplication.main(args);
 * }</pre>
 * or supply one or more package names as {@code args} to enable additional component scanning.
 */
@SpringBootApplication(exclude = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(KafkaProperties.class)
@PropertySource("classpath:akces-agenticservice.properties")
public class AgenticAggregateServiceApplication {

    private final ProtocolRecordSerde serde = new ProtocolRecordSerde();

    /**
     * Application entry point.
     *
     * @param args optional package names to include in component scanning
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AgenticAggregateServiceApplication.class);
        if (args.length > 0) {
            application.setSources(Set.of(args));
        }
        application.run();
    }

    /**
     * Registers the {@link AgenticAggregateBeanFactoryPostProcessor} that discovers and wires
     * aggregate beans annotated with {@link org.elasticsoftware.akces.annotations.AgenticAggregateInfo}.
     *
     * @return the agentic aggregate bean factory post-processor
     */
    @Bean(name = "agenticServiceBeanFactoryPostProcessor")
    public static AgenticAggregateBeanFactoryPostProcessor aggregateBeanFactoryPostProcessor() {
        return new AgenticAggregateBeanFactoryPostProcessor();
    }

    /**
     * Customises the Jackson {@code ObjectMapper} with the {@link BigDecimalSerializer}
     * (serialises {@link BigDecimal} as a JSON string to avoid precision loss) and the
     * GDPR encryption module.
     *
     * @return the Jackson mapper builder customiser
     */
    @Bean(name = "agenticServiceJsonCustomizer")
    public JsonMapperBuilderCustomizer jsonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(BigDecimal.class, new BigDecimalSerializer());
            builder.addModule(new AkcesGDPRModule());
            builder.addModule(module);
        };
    }

    /**
     * Creates the {@link AkcesControlRecordSerde} used to serialise and deserialise
     * {@code Akces-Control} topic messages.
     *
     * @param objectMapper the application {@link tools.jackson.databind.ObjectMapper}
     * @return the control-record serde
     */
    @Bean(name = "agenticServiceControlRecordSerde")
    public AkcesControlRecordSerde akcesControlRecordSerde(tools.jackson.databind.ObjectMapper objectMapper) {
        return new AkcesControlRecordSerde(objectMapper);
    }

    /**
     * Creates the {@link KafkaAdmin} instance used for topic management.
     *
     * @param bootstrapServers comma-separated list of Kafka bootstrap servers
     * @return the Kafka admin client
     */
    @Bean(name = "agenticServiceKafkaAdmin")
    public KafkaAdmin kafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    /**
     * Creates the serde for {@link SchemaRecord} messages stored on the schema topic.
     *
     * @param objectMapper the application {@link tools.jackson.databind.ObjectMapper}
     * @return the schema-record serde
     */
    @Bean(name = "agenticServiceSchemaRecordSerde")
    public SchemaRecordSerde schemaRecordSerde(tools.jackson.databind.ObjectMapper objectMapper) {
        return new SchemaRecordSerde(objectMapper);
    }

    /**
     * Creates the {@link ProducerFactory} for {@link SchemaRecord} messages.
     *
     * @param properties Kafka properties loaded from application configuration
     * @param serde      the schema-record serde
     * @return the schema producer factory
     */
    @Bean(name = "agenticServiceSchemaProducerFactory")
    public ProducerFactory<String, SchemaRecord> schemaProducerFactory(
            KafkaProperties properties,
            @Qualifier("agenticServiceSchemaRecordSerde") SchemaRecordSerde serde) {
        return new CustomKafkaProducerFactory<>(
                properties.buildProducerProperties(),
                new StringSerializer(),
                serde.serializer());
    }

    /**
     * Creates the {@link ConsumerFactory} for {@link SchemaRecord} messages.
     *
     * @param properties Kafka properties loaded from application configuration
     * @param serde      the schema-record serde
     * @return the schema consumer factory
     */
    @Bean(name = "agenticServiceSchemaConsumerFactory")
    public ConsumerFactory<String, SchemaRecord> schemaConsumerFactory(
            KafkaProperties properties,
            @Qualifier("agenticServiceSchemaRecordSerde") SchemaRecordSerde serde) {
        return new CustomKafkaConsumerFactory<>(
                properties.buildConsumerProperties(),
                new StringDeserializer(),
                serde.deserializer());
    }

    /**
     * Creates the main {@link ConsumerFactory} for {@link ProtocolRecord} messages
     * (commands, domain events, aggregate state).
     *
     * @param properties Kafka properties loaded from application configuration
     * @return the protocol-record consumer factory
     */
    @Bean(name = "agenticServiceConsumerFactory")
    public ConsumerFactory<String, ProtocolRecord> consumerFactory(KafkaProperties properties) {
        return new CustomKafkaConsumerFactory<>(
                properties.buildConsumerProperties(),
                new StringDeserializer(),
                serde.deserializer());
    }

    /**
     * Creates the main {@link ProducerFactory} for {@link ProtocolRecord} messages.
     *
     * @param properties Kafka properties loaded from application configuration
     * @return the protocol-record producer factory
     */
    @Bean(name = "agenticServiceProducerFactory")
    public ProducerFactory<String, ProtocolRecord> producerFactory(KafkaProperties properties) {
        return new CustomKafkaProducerFactory<>(
                properties.buildProducerProperties(),
                new StringSerializer(),
                serde.serializer());
    }

    /**
     * Creates the {@link ConsumerFactory} for {@link AkcesControlRecord} messages.
     *
     * @param properties  Kafka properties loaded from application configuration
     * @param controlSerde the control-record serde
     * @return the control-record consumer factory
     */
    @Bean(name = "agenticServiceControlConsumerFactory")
    public ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory(
            KafkaProperties properties,
            @Qualifier("agenticServiceControlRecordSerde") AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaConsumerFactory<>(
                properties.buildConsumerProperties(),
                new StringDeserializer(),
                controlSerde.deserializer());
    }

    /**
     * Creates the {@link ProducerFactory} for {@link AkcesControlRecord} messages.
     *
     * @param properties  Kafka properties loaded from application configuration
     * @param controlSerde the control-record serde
     * @return the control-record producer factory
     */
    @Bean(name = "agenticServiceControlProducerFactory")
    public ProducerFactory<String, AkcesControlRecord> controlProducerFactory(
            KafkaProperties properties,
            @Qualifier("agenticServiceControlRecordSerde") AkcesControlRecordSerde controlSerde) {
        return new CustomKafkaProducerFactory<>(
                properties.buildProducerProperties(),
                new StringSerializer(),
                controlSerde.serializer());
    }

    /**
     * Creates the {@link AggregateStateRepositoryFactory} backed by RocksDB.
     *
     * @param baseDir base directory for RocksDB storage
     * @return the state repository factory
     */
    @Bean(name = "agenticServiceAggregateStateRepositoryFactory")
    public AggregateStateRepositoryFactory aggregateStateRepositoryFactory(
            @Value("${akces.rocksdb.baseDir:/tmp/akces}") String baseDir) {
        return new RocksDBAggregateStateRepositoryFactory(serde, baseDir);
    }

    /**
     * Creates an {@link EnvironmentPropertiesPrinter} that logs all resolved environment
     * properties at startup for diagnostics.
     *
     * @return the environment properties printer
     */
    @Bean(name = "agenticEnvironmentPropertiesPrinter")
    public EnvironmentPropertiesPrinter environmentPropertiesPrinter() {
        return new EnvironmentPropertiesPrinter();
    }
}
