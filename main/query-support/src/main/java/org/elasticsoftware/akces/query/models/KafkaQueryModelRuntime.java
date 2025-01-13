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

package org.elasticsoftware.akces.query.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import io.confluent.kafka.schemaregistry.json.diff.Difference;
import io.confluent.kafka.schemaregistry.json.diff.SchemaDiff;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelEventHandlerFunction;
import org.elasticsoftware.akces.query.QueryModelState;
import org.elasticsoftware.akces.query.QueryModelStateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KafkaQueryModelRuntime<S extends QueryModelState> implements QueryModelRuntime<S> {
    private static final Logger logger = LoggerFactory.getLogger(KafkaQueryModelRuntime.class);
    private final SchemaRegistryClient schemaRegistryClient;
    private final SchemaGenerator jsonSchemaGenerator;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends DomainEvent>, JsonSchema> domainEventSchemas = new HashMap<>();
    private final QueryModelStateType<?> type;
    private final Class<? extends QueryModel<S>> queryModelClass;
    private final Map<Class<?>,DomainEventType<?>> domainEvents;
    private final QueryModelEventHandlerFunction<S, DomainEvent> createStateHandler;
    private final Map<DomainEventType<?>, QueryModelEventHandlerFunction<S, DomainEvent>> queryModelEventHandlers;

    private KafkaQueryModelRuntime(SchemaRegistryClient schemaRegistryClient,
                                   ObjectMapper objectMapper,
                                   QueryModelStateType<?> type,
                                   Class<? extends QueryModel<S>> queryModelClass,
                                   QueryModelEventHandlerFunction<S, DomainEvent> createStateHandler,
                                   Map<Class<?>,DomainEventType<?>> domainEvents,
                                   Map<DomainEventType<?>, QueryModelEventHandlerFunction<S, DomainEvent>> queryModelEventHandlers) {
        this.schemaRegistryClient = schemaRegistryClient;
        this.objectMapper = objectMapper;
        this.type = type;
        this.queryModelClass = queryModelClass;
        this.domainEvents = domainEvents;
        this.createStateHandler = createStateHandler;
        this.queryModelEventHandlers = queryModelEventHandlers;
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(objectMapper,
                SchemaVersion.DRAFT_7,
                OptionPreset.PLAIN_JSON);
        configBuilder.with(new JakartaValidationModule(JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS,
                JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED));
        configBuilder.with(new JacksonModule());
        configBuilder.with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_FIELDS_BY_DEFAULT);
        configBuilder.with(Option.NULLABLE_METHOD_RETURN_VALUES_BY_DEFAULT);
        // we need to override the default behavior of the generator to write BigDecimal as type = number
        configBuilder.forTypesInGeneral().withTypeAttributeOverride((collectedTypeAttributes, scope, context) -> {
            if (scope.getType().getTypeName().equals("java.math.BigDecimal")) {
                JsonNode typeNode = collectedTypeAttributes.get("type");
                if (typeNode.isArray()) {
                    ((ArrayNode) collectedTypeAttributes.get("type")).set(0, "string");
                } else
                    collectedTypeAttributes.put("type", "string");
            }
        });
        SchemaGeneratorConfig config = configBuilder.build();
        this.jsonSchemaGenerator = new SchemaGenerator(config);
    }

    @Override
    public String getName() {
        return type.typeName();
    }

    @Override
    public String getIndexName() {
        return type.indexName();
    }

    @Override
    public Class<? extends QueryModel<S>> getQueryModelClass() {
        return queryModelClass;
    }

    @Override
    public S apply(List<DomainEventRecord> eventRecords, S currentState) throws IOException {
        S state = currentState;
        for (DomainEventRecord eventRecord : eventRecords) {
            // determine the type to use for the external event
            DomainEventType<?> domainEventType = getDomainEventType(eventRecord);
            // with external domainevents we should look at the handler and not at the type of the external event
            if(domainEventType != null) {
                if (state == null && createStateHandler != null && createStateHandler.getEventType().equals(domainEventType) ) {
                    state = createStateHandler.apply(materialize(domainEventType, eventRecord), null);
                } else if(state != null) { // we first need to see the create handler
                    // only process the event if we have a handler for it
                    QueryModelEventHandlerFunction<S, DomainEvent> eventHandler = queryModelEventHandlers.get(domainEventType);
                    if(eventHandler != null) {
                        state = eventHandler.apply(materialize(domainEventType, eventRecord), state);
                    }
                }
            } // ignore if we don't have an external domainevent registered
        }
        return state;
    }

    @Override
    public void validateDomainEventSchemas() {
        for (DomainEventType<?> domainEventType : domainEvents.values()) {
            try {
                logger.info("Validating schema for domain event {}", domainEventType.typeName());
                JsonSchema localSchema = generateJsonSchema(domainEventType);
                // check if the type exists in the registry
                List<ParsedSchema> registeredSchemas = schemaRegistryClient.getSchemas("domainevents." + domainEventType.typeName(), false, false);
                if (!registeredSchemas.isEmpty()) {
                    logger.trace("Found {} schemas for domain event {}", registeredSchemas.size(), domainEventType.typeName());
                    // see if it is an existing schema
                    ParsedSchema registeredSchema = registeredSchemas.stream()
                            .filter(parsedSchema -> getSchemaVersion(domainEventType, parsedSchema) == domainEventType.version())
                            .findFirst().orElse(null);
                    if (registeredSchema != null) {
                        logger.trace("Found schema for domain event {} version {}", domainEventType.typeName(), domainEventType.version());
                        // localSchema has to be a subset of registeredSchema
                        // TODO: this needs to be implemented to make sure we
                        // TODO: need to check a range of schema's here
                        List<Difference> differences = SchemaDiff.compare(((JsonSchema) registeredSchema).rawSchema(), localSchema.rawSchema());
                        if (!differences.isEmpty()) {
                            // we need to check if any properties were removed, removed properties are allowed
                            // adding properties is not allowed, as well as changing the type etc
                            List<Difference> violatingDifferences = differences.stream()
                                    .filter(difference -> !difference.getType().equals(Difference.Type.PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL))
                                    .toList();
                            if(!violatingDifferences.isEmpty()) {
                               // our implementaion class is incompatible with the registered schema
                                throw new IncompatibleSchemaException(
                                        "domainevents." + domainEventType.typeName(),
                                        domainEventType.version(),
                                        domainEventType.typeClass(),
                                        violatingDifferences);
                            }
                        }
                    } else {
                        // did not find the specific version
                        throw new SchemaVersionNotFoundException(
                                "domainevents." + domainEventType.typeName(),
                                domainEventType.version(),
                                domainEventType.typeClass());
                    }
                } else {
                    // do not find any schemas
                    throw new SchemaNotFoundException(
                            "domainevents." + domainEventType.typeName(),
                            domainEventType.typeClass());
                }
            } catch (IOException | RestClientException | RuntimeException e) {
                logger.error("Error generating schema for domain event {}", domainEventType.typeName(), e);
            }
        }
    }

    private JsonSchema generateJsonSchema(DomainEventType<?> domainEventType) {
        return new JsonSchema(jsonSchemaGenerator.generateSchema(domainEventType.typeClass()), List.of(), Map.of(), domainEventType.version());
    }

    private int getSchemaVersion(DomainEventType<?> domainEventType, ParsedSchema parsedSchema) {
        try {
            return schemaRegistryClient.getVersion("domainevents."+domainEventType.typeName(), parsedSchema);
        } catch (IOException | RestClientException e) {
            throw new RuntimeException(e);
        }
    }

    private DomainEvent materialize(DomainEventType<?> domainEventType, DomainEventRecord eventRecord) throws IOException {
        return objectMapper.readValue(eventRecord.payload(), domainEventType.typeClass());
    }

    private DomainEventType<?> getDomainEventType(DomainEventRecord eventRecord) {
        // because it is an external event we need to find the highest version that is smaller than the eventRecord version
        return domainEvents.entrySet().stream()
                .filter(entry -> entry.getValue().external())
                .filter(entry -> entry.getValue().typeName().equals(eventRecord.name()))
                .filter(entry -> entry.getValue().version() <= eventRecord.version())
                .max(Comparator.comparingInt(entry -> entry.getValue().version()))
                .map(Map.Entry::getValue).orElse(null);
    }

    public static class Builder {
        private SchemaRegistryClient schemaRegistryClient;
        private ObjectMapper objectMapper;
        private QueryModelStateType<?> stateType;
        private Class<? extends QueryModel> queryModelClass;
        private QueryModelEventHandlerFunction<QueryModelState, DomainEvent> createStateHandler;
        private final Map<Class<?>, DomainEventType<?>> domainEvents = new HashMap<>();
        private final Map<DomainEventType<?>, QueryModelEventHandlerFunction<QueryModelState, DomainEvent>> queryModelEventHandlers = new HashMap<>();

        public Builder setSchemaRegistryClient(SchemaRegistryClient schemaRegistryClient) {
            this.schemaRegistryClient = schemaRegistryClient;
            return this;
        }

        public Builder setObjectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder setStateType(QueryModelStateType<?> stateType) {
            this.stateType = stateType;
            return this;
        }

        public Builder setQueryModelClass(Class<? extends QueryModel> queryModelClass) {
            this.queryModelClass = queryModelClass;
            return this;
        }

        public Builder setCreateHandler(QueryModelEventHandlerFunction<QueryModelState, DomainEvent> createStateHandler) {
            this.createStateHandler = createStateHandler;
            return this;
        }

        public Builder addDomainEvent(DomainEventType<?> domainEvent) {
            this.domainEvents.put(domainEvent.typeClass(), domainEvent);
            return this;
        }

        public Builder addQueryModelEventHandler(DomainEventType<?> eventType,
                                                 QueryModelEventHandlerFunction<QueryModelState, DomainEvent> eventSourcingHandler) {
            this.queryModelEventHandlers.put(eventType, eventSourcingHandler);
            return this;
        }

        public KafkaQueryModelRuntime build() {
            return new KafkaQueryModelRuntime(schemaRegistryClient,
                    objectMapper,
                    stateType,
                    queryModelClass,
                    createStateHandler,
                    domainEvents,
                    queryModelEventHandlers);
        }
    }
}
