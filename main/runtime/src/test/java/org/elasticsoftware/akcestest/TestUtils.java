package org.elasticsoftware.akcestest;

import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.springframework.context.ApplicationContextException;
import org.springframework.kafka.core.KafkaAdmin;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class TestUtils {
    public static void prepareKafka(String bootstrapServers) {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        kafkaAdmin.createOrModifyTopics(
                createCompactedTopic("Akces-Control", 3),
                createTopic("Wallet-Commands", 3),
                createTopic("Wallet-DomainEvents", 3),
                createTopic("Account-Commands", 3),
                createTopic("Account-DomainEvents", 3),
                createTopic("OrderProcessManager-Commands", 3),
                createTopic("OrderProcessManager-DomainEvents", 3),
                createCompactedTopic("Wallet-AggregateState", 3),
                createCompactedTopic("Account-AggregateState", 3),
                createCompactedTopic("OrderProcessManager-AggregateState", 3));
    }

    private static NewTopic createTopic(String name, int numPartitions) {
        NewTopic topic = new NewTopic(name, numPartitions , Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy","delete",
                "max.message.bytes","10485760",
                "retention.ms", "-1",
                "segment.ms","604800000"));
    }

    private static NewTopic createCompactedTopic(String name, int numPartitions) {
        NewTopic topic = new NewTopic(name, numPartitions , Short.parseShort("1"));
        return topic.configs(Map.of(
                "cleanup.policy","compact",
                "max.message.bytes","20971520",
                "retention.ms", "-1",
                "segment.ms","604800000",
                "min.cleanable.dirty.ratio", "0.1",
                "delete.retention.ms", "604800000",
                "compression.type", "lz4"));
    }

    public static <E extends DomainEvent> void prepareExternalSchemas(String url, List<Class<E>> externalDomainEvents) {
        SchemaRegistryClient src = new CachedSchemaRegistryClient(url, 100);
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS, JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(new JacksonModule());
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_FIELDS_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_METHOD_RETURN_VALUES_BY_DEFAULT);
        SchemaGeneratorConfig config = configBuilder.build();
        SchemaGenerator jsonSchemaGenerator = new SchemaGenerator(config);
        try {
            for(Class<E> eventClass : externalDomainEvents) {
                DomainEventInfo info = eventClass.getAnnotation(DomainEventInfo.class);
                src.register(info.type(),
                        new JsonSchema(jsonSchemaGenerator.generateSchema(AccountCreatedEvent.class), List.of(), Map.of(), info.version()),
                        info.version(),
                        -1);
            }
        } catch (IOException | RestClientException e) {
            throw new ApplicationContextException("Problem populating SchemaRegistry", e);
        }
    }
}
