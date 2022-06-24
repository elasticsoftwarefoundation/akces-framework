package org.elasticsoftware.akces.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.commands.CreateAggregateCommandHandlerFunction;
import org.elasticsoftware.akces.events.CreateAggregateEventSourcingHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class KafkaAggregateRuntime extends AggregateRuntime {
    private final SchemaRegistryClient schemaRegistryClient;
    private final SchemaGenerator jsonSchemaGenerator;
    private final ObjectMapper objectMapper;

    public KafkaAggregateRuntime(AggregateStateType<?> stateType,
                                 CreateAggregateCommandHandlerFunction<Command, DomainEvent> commandCreateHandler,
                                 CreateAggregateEventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler,
                                 Map<Class<?>, DomainEventType<?>> domainEvents,
                                 Map<String, List<CommandType<?>>> commandTypes,
                                 Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers,
                                 Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers) {
        super(stateType, commandCreateHandler, createStateHandler, domainEvents, commandTypes, commandHandlers, eventSourcingHandlers);
        this.schemaRegistryClient = new CachedSchemaRegistryClient("http://akces-schema-registry.strimzi:8081",100);
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS, JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        SchemaGeneratorConfig config = configBuilder.build();
        this.jsonSchemaGenerator = new SchemaGenerator(config);
        this.objectMapper = new ObjectMapper();
    }

    protected void registerAndValidate(DomainEventType<?> domainEventType) throws Exception {
        // generate the local schema version
        JsonSchema localSchema = new JsonSchema(jsonSchemaGenerator.generateSchema(domainEventType.typeClass()), List.of(), Map.of(), domainEventType.version());
        // check if the type exists in the registy
        Schema registeredSchema = schemaRegistryClient.getByVersion(domainEventType.typeName(), domainEventType.version(), false);
        if(registeredSchema == null) {
            // we need to create the schema and register it
            schemaRegistryClient.register(domainEventType.typeName(), localSchema, domainEventType.version(), -1);
        } else {
            ParsedSchema parsedSchema = schemaRegistryClient.getSchemaById(registeredSchema.getId());
            // we need to make sure that the schemas are compatible
            if(domainEventType.external()) {
                // it has to be backwards compatible

                // TODO: need to check a range of schema's here
            } else {
                // it has to be exactly the same
                if(!parsedSchema.deepEquals(localSchema)) {
                    // TODO: need to throw and exception as the data is not correct
                }
            }
        }
    }

    @Override
    protected Command materialize(CommandRecord commandRecord) throws IOException {
        return objectMapper.readValue(commandRecord.payload(), getCommandType(commandRecord).typeClass());
    }

    @Override
    protected AggregateState materialize(AggregateStateRecord stateRecord) throws IOException {
        return objectMapper.readValue(stateRecord.payload(), getAggregateStateType(stateRecord).typeClass());
    }

    @Override
    protected byte[] serialize(AggregateState state) throws IOException {
        return objectMapper.writeValueAsBytes(state);
    }

    @Override
    protected byte[] serialize(DomainEvent domainEvent) throws IOException {
        return objectMapper.writeValueAsBytes(domainEvent);
    }

    @Override
    protected PayloadEncoding getEncoding(CommandType<?> type) {
        return PayloadEncoding.JSON;
    }

    @Override
    protected PayloadEncoding getEncoding(DomainEventType<?> type) {
        return PayloadEncoding.JSON;
    }

    @Override
    protected PayloadEncoding getEncoding(AggregateStateType<?> type) {
        return PayloadEncoding.JSON;
    }
}
