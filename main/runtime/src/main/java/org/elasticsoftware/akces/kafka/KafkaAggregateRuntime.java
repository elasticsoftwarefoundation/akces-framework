package org.elasticsoftware.akces.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import io.confluent.kafka.schemaregistry.json.diff.Difference;
import io.confluent.kafka.schemaregistry.json.diff.SchemaDiff;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventHandlerFunction;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.everit.json.schema.Schema;

import java.io.IOException;
import java.util.*;

import static java.lang.String.format;

public class KafkaAggregateRuntime extends AggregateRuntimeBase {
    private final SchemaRegistryClient schemaRegistryClient;
    private final SchemaGenerator jsonSchemaGenerator;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends DomainEvent>, JsonSchema> domainEventSchemas = new HashMap<>();

    private KafkaAggregateRuntime(SchemaRegistryClient schemaRegistryClient,
                                 ObjectMapper objectMapper,
                                 AggregateStateType<?> stateType,
                                 Class<? extends Aggregate> aggregateClass,
                                 CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler,
                                 EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler,
                                 EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler,
                                 Map<Class<?>, DomainEventType<?>> domainEvents,
                                 Map<String, List<CommandType<?>>> commandTypes,
                                 Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers,
                                 Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers,
                                 Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers) {
        super(stateType, aggregateClass, commandCreateHandler, eventCreateHandler, createStateHandler, domainEvents, commandTypes, commandHandlers, eventHandlers, eventSourcingHandlers);
        this.schemaRegistryClient = schemaRegistryClient;
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS, JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        SchemaGeneratorConfig config = configBuilder.build();
        this.jsonSchemaGenerator = new SchemaGenerator(config);
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonSchema generateJsonSchema(DomainEventType<?> domainEventType) {
        return new JsonSchema(jsonSchemaGenerator.generateSchema(domainEventType.typeClass()), List.of(), Map.of(), domainEventType.version());
    }

    @Override
    public void registerAndValidate(DomainEventType<?> domainEventType) throws Exception {
        // generate the local schema version
        JsonSchema localSchema = generateJsonSchema(domainEventType);
        // check if the type exists in the registry
        List<ParsedSchema> registeredSchemas = schemaRegistryClient.getSchemas(domainEventType.typeName(), false, false);
        if(registeredSchemas.isEmpty()) {
            if(!domainEventType.external()) {
                if(domainEventType.version() == 1) {
                    // we need to create the schema and register it (not external ones as they are owned by another aggregate)
                    schemaRegistryClient.register(domainEventType.typeName(), localSchema, domainEventType.version(), -1);
                } else {
                    // we are missing schema(s) for the previous version(s)
                    throw new IllegalStateException(format("Missing schema(s) for previous version(s) for DomainEvent [%s:%d]", domainEventType.typeName(), domainEventType.version()));
                }
            } else {
                // this is an error since we cannot find a registered schema for an external event
                throw new IllegalStateException(format("No Schemas found for External DomainEvent [%s:%d]", domainEventType.typeName(), domainEventType.version()));
            }
        } else {
            // see if it is an existing schema
            ParsedSchema registeredSchema = registeredSchemas.stream()
                    .filter(parsedSchema -> getSchemaVersion(domainEventType, parsedSchema) == domainEventType.version())
                    .findFirst().orElse(null);
            if(registeredSchema != null) {
                // we need to make sure that the schemas are compatible
                if(domainEventType.external()) {
                    // localSchema has to be a subset of registeredSchema
                    // TODO: this needs to be implemented to make sure we
                    // TODO: need to check a range of schema's here
                    List<Difference> differences = SchemaDiff.compare(((JsonSchema)registeredSchema).rawSchema(), localSchema.rawSchema());
                    if(!differences.isEmpty()) {
                        // we need to check if any properties were removed, removed properties are allowed
                        // adding properties is not allowed, as well as chaning the type etc
                        for(Difference difference : differences) {
                            // TODO: see if we need to ignore other types
                            if(!difference.getType().equals(Difference.Type.PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL)) {
                                throw new IllegalStateException(format("Schema is not compatible with Registered Schema for External DomainEvent [%s:%d]", domainEventType.typeName(), domainEventType.version()));
                            }
                        }
                    }
                } else {
                    // it has to be exactly the same
                    if(!registeredSchema.deepEquals(localSchema)) {
                        throw new IllegalStateException("Registered Schema does not match Local Schema");
                    }
                }
            } else if(domainEventType.external()) {
                throw new IllegalStateException(format("No Schema found for External DomainEvent [%s:%d]", domainEventType.typeName(), domainEventType.version()));
            } else {
                // ensure we have an ordered list of schemas
                registeredSchemas.sort(Comparator.comparingInt(parsedSchema -> ((JsonSchema)parsedSchema).version()));
                // see if the new version is exactly one higher than the last version
                if(domainEventType.version() != ((JsonSchema)registeredSchemas.get(registeredSchemas.size() - 1)).version() + 1) {
                    throw new IllegalStateException(format("New Schema version is not exactly one higher than the last version for DomainEvent [%s:%d]", domainEventType.typeName(), domainEventType.version()));
                }
                // see if the new schema is backwards compatible with the previous ones
                if(localSchema.isCompatible(CompatibilityLevel.BACKWARD_TRANSITIVE, registeredSchemas).isEmpty()) {
                    // register the new schema
                    schemaRegistryClient.register(domainEventType.typeName(), localSchema, domainEventType.version(), -1);
                } else {
                    throw new IllegalStateException(format("New Schema is not backwards compatible with previous versions for DomainEvent [%s:%d]", domainEventType.typeName(), domainEventType.version()));
                }
            }
        }
        // schema is fine, add to map
        domainEventSchemas.put(domainEventType.typeClass(), localSchema);
    }

    private int getSchemaVersion(DomainEventType<?> domainEventType, ParsedSchema parsedSchema) {
        try {
            return schemaRegistryClient.getVersion(domainEventType.typeName(), parsedSchema);
        } catch (IOException | RestClientException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Command materialize(CommandType<?> type, CommandRecord commandRecord) throws IOException {
        return objectMapper.readValue(commandRecord.payload(), type.typeClass());
    }

    @Override
    protected DomainEvent materialize(DomainEventType<?> domainEventType, DomainEventRecord eventRecord) throws IOException {
        return objectMapper.readValue(eventRecord.payload(), domainEventType.typeClass());
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
        JsonNode jsonNode = objectMapper.convertValue(domainEvent, JsonNode.class);
        domainEventSchemas.get(domainEvent.getClass()).validate(jsonNode);
        return objectMapper.writeValueAsBytes(jsonNode);
    }

    @Override
    public byte[] serialize(Command command) throws IOException {
        return objectMapper.writeValueAsBytes(command);
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

    public static class Builder {
        private SchemaRegistryClient schemaRegistryClient;
        private ObjectMapper objectMapper;
        private AggregateStateType<?> stateType;
        private Class<? extends Aggregate> aggregateClass;
        private CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler;
        private EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler;
        private EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler;
        private Map<Class<?>, DomainEventType<?>> domainEvents = new HashMap<>();
        private Map<String, List<CommandType<?>>> commandTypes = new HashMap<>();
        private Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers = new HashMap<>();
        private Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers = new HashMap<>();
        private Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers = new HashMap<>();

        public Builder setSchemaRegistryClient(SchemaRegistryClient schemaRegistryClient) {
            this.schemaRegistryClient = schemaRegistryClient;
            return this;
        }

        public Builder setObjectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder setStateType(AggregateStateType<?> stateType) {
            this.stateType = stateType;
            return this;
        }

        public Builder setAggregateClass(Class<? extends Aggregate> aggregateClass) {
            this.aggregateClass = aggregateClass;
            return this;
        }

        public Builder setCommandCreateHandler(CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler) {
            this.commandCreateHandler = commandCreateHandler;
            return this;
        }

        public Builder setEventCreateHandler(EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler) {
            this.eventCreateHandler = eventCreateHandler;
            return this;
        }

        public Builder setEventSourcingCreateHandler(EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler) {
            this.createStateHandler = createStateHandler;
            return this;
        }

        public Builder addDomainEvent(DomainEventType<?> domainEvent) {
            this.domainEvents.put(domainEvent.typeClass(), domainEvent);
            return this;
        }

        public Builder addCommand(CommandType<?> commandType) {
            this.commandTypes.computeIfAbsent(commandType.typeName(), s -> new ArrayList<>()).add(commandType);
            return this;
        }

        public Builder addCommandHandler(CommandType<?> commandType, CommandHandlerFunction<AggregateState, Command, DomainEvent> commandHandler) {
            this.commandHandlers.put(commandType, commandHandler);
            return this;
        }

        public Builder addExternalEventHandler(DomainEventType<?> eventType, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventHandler) {
            this.eventHandlers.put(eventType, eventHandler);
            return this;
        }

        public Builder addEventSourcingHandler(DomainEventType<?> eventType, EventSourcingHandlerFunction<AggregateState, DomainEvent> eventSourcingHandler) {
            this.eventSourcingHandlers.put(eventType, eventSourcingHandler);
            return this;
        }

        public KafkaAggregateRuntime build() {
            return new KafkaAggregateRuntime(schemaRegistryClient,
                    objectMapper,
                    stateType,
                    aggregateClass,
                    commandCreateHandler,
                    eventCreateHandler,
                    createStateHandler,
                    domainEvents,
                    commandTypes,
                    commandHandlers,
                    eventHandlers,
                    eventSourcingHandlers);
        }
    }
}
