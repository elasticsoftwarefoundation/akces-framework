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

package org.elasticsoftware.akces.schemas;

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
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.aggregate.SchemaType;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.storage.KafkaTopicSchemaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class KafkaSchemaRegistry {
    private static final Logger logger = LoggerFactory.getLogger(KafkaSchemaRegistry.class);
    private final SchemaRegistryClient schemaRegistryClient;
    private final KafkaTopicSchemaStorage schemaStorage;
    // schema generator is not thread safe
    private final ThreadLocal<SchemaGenerator> schemaGeneratorTheadLocal;

    /**
     * Creates a KafkaSchemaRegistry using the legacy SchemaRegistryClient.
     * @deprecated Use {@link #KafkaSchemaRegistry(KafkaTopicSchemaStorage, ObjectMapper)} instead
     */
    @Deprecated
    public KafkaSchemaRegistry(SchemaRegistryClient schemaRegistryClient, ObjectMapper objectMapper) {
        this.schemaRegistryClient = schemaRegistryClient;
        this.schemaStorage = null;
        this.schemaGeneratorTheadLocal = ThreadLocal.withInitial(() -> createJsonSchemaGenerator(objectMapper));
    }

    /**
     * Creates a KafkaSchemaRegistry using Kafka topic-based schema storage.
     */
    public KafkaSchemaRegistry(KafkaTopicSchemaStorage schemaStorage, ObjectMapper objectMapper) {
        this.schemaRegistryClient = null;
        this.schemaStorage = schemaStorage;
        this.schemaGeneratorTheadLocal = ThreadLocal.withInitial(() -> createJsonSchemaGenerator(objectMapper));
    }

    public JsonSchema validate(CommandType<?> commandType) throws SchemaException {
        return validate(commandType, true);
    }

    public JsonSchema validate(DomainEventType<?> domainEventType) throws SchemaException {
        return validate(domainEventType, false);
    }

    private JsonSchema validate(SchemaType<?> schemaType, boolean strict) throws SchemaException {
        if (schemaStorage != null) {
            return validateWithStorage(schemaType, strict);
        } else {
            return validateWithRegistryClient(schemaType, strict);
        }
    }

    private JsonSchema validateWithStorage(SchemaType<?> schemaType, boolean strict) throws SchemaException {
        logger.info("Validating schema {} v{}", schemaType.getSchemaName(), schemaType.version());
        JsonSchema localSchema = generateJsonSchema(schemaType);
        
        // Get all registered schemas for this type
        List<SchemaRecord> registeredSchemas = schemaStorage.getSchemas(schemaType.getSchemaName());
        
        if (!registeredSchemas.isEmpty()) {
            logger.trace("Found {} schemas for type {}", registeredSchemas.size(), schemaType.typeName());
            
            // Find the specific version
            SchemaRecord registeredSchemaRecord = registeredSchemas.stream()
                    .filter(record -> record.version() == schemaType.version())
                    .findFirst().orElse(null);
            
            if (registeredSchemaRecord != null) {
                logger.trace("Found schema for type {} v{}", schemaType.typeName(), schemaType.version());
                JsonSchema registeredSchema = registeredSchemaRecord.schema();
                
                // Validate compatibility
                List<Difference> differences = SchemaDiff.compare(registeredSchema.rawSchema(), localSchema.rawSchema());
                if (!differences.isEmpty()) {
                    if (!strict) {
                        // Check if only allowed differences exist
                        List<Difference> violatingDifferences = differences.stream()
                                .filter(difference -> !difference.getType().equals(Difference.Type.PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL))
                                .toList();
                        if (!violatingDifferences.isEmpty()) {
                            throw new IncompatibleSchemaException(
                                    schemaType.getSchemaName(),
                                    schemaType.version(),
                                    schemaType.typeClass(),
                                    violatingDifferences);
                        }
                    } else {
                        throw new IncompatibleSchemaException(
                                schemaType.getSchemaName(),
                                schemaType.version(),
                                schemaType.typeClass(),
                                differences);
                    }
                }
                return localSchema;
            } else {
                throw new SchemaVersionNotFoundException(
                        schemaType.getSchemaName(),
                        schemaType.version(),
                        schemaType.typeClass());
            }
        } else {
            throw new SchemaNotFoundException(
                    schemaType.getSchemaName(),
                    schemaType.typeClass());
        }
    }

    private JsonSchema validateWithRegistryClient(SchemaType<?> schemaType, boolean strict) throws SchemaException {
        try {
            logger.info("Validating schema {} v{}", schemaType.getSchemaName(), schemaType.version());
            JsonSchema localSchema = generateJsonSchema(schemaType);
            // check if the type exists in the registry
            List<ParsedSchema> registeredSchemas = schemaRegistryClient.getSchemas(schemaType.getSchemaName(), false, false);
            if (!registeredSchemas.isEmpty()) {
                logger.trace("Found {} schemas for type {}", registeredSchemas.size(), schemaType.typeName());
                // see if it is an existing schema
                ParsedSchema registeredSchema = registeredSchemas.stream()
                        .filter(parsedSchema -> getSchemaVersion(schemaType, parsedSchema) == schemaType.version())
                        .findFirst().orElse(null);
                if (registeredSchema != null) {
                    logger.trace("Found schema for type {} v{}", schemaType.typeName(), schemaType.version());
                    // localSchema has to be a subset of registeredSchema
                    // TODO: this needs to be implemented to make sure we
                    // TODO: need to check a range of schema's here
                    List<Difference> differences = SchemaDiff.compare(((JsonSchema) registeredSchema).rawSchema(), localSchema.rawSchema());
                    if (!differences.isEmpty()) {
                        if (!strict) {
                            // we need to check if any properties were removed, removed properties are allowed
                            // adding properties is not allowed, as well as changing the type etc
                            List<Difference> violatingDifferences = differences.stream()
                                    .filter(difference -> !difference.getType().equals(Difference.Type.PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL))
                                    .toList();
                            if (!violatingDifferences.isEmpty()) {
                                // our implementaion class is incompatible with the registered schema
                                throw new IncompatibleSchemaException(
                                        schemaType.getSchemaName(),
                                        schemaType.version(),
                                        schemaType.typeClass(),
                                        violatingDifferences);
                            }
                        } else {
                            throw new IncompatibleSchemaException(
                                    schemaType.getSchemaName(),
                                    schemaType.version(),
                                    schemaType.typeClass(),
                                    differences);
                        }
                    }
                    return localSchema;
                } else {
                    // did not find the specific version
                    throw new SchemaVersionNotFoundException(
                            schemaType.getSchemaName(),
                            schemaType.version(),
                            schemaType.typeClass());
                }
            } else {
                // do not find any schemas
                throw new SchemaNotFoundException(
                        schemaType.getSchemaName(),
                        schemaType.typeClass());
            }
        } catch (IOException | RestClientException e) {
            throw new SchemaException(
                    "Unexpected Error while validating schema",
                    schemaType.getSchemaName(),
                    schemaType.typeClass(),
                    e);
        }
    }

    public JsonSchema registerAndValidate(SchemaType<?> schemaType, boolean forceRegisterOnIncompatibleSchema) throws SchemaException {
        if (schemaStorage != null) {
            return registerAndValidateWithStorage(schemaType, forceRegisterOnIncompatibleSchema);
        } else {
            return registerAndValidateWithRegistryClient(schemaType, forceRegisterOnIncompatibleSchema);
        }
    }

    private JsonSchema registerAndValidateWithStorage(SchemaType<?> schemaType, boolean forceRegisterOnIncompatibleSchema) throws SchemaException {
        // generate the local schema version
        JsonSchema localSchema = generateJsonSchema(schemaType);
        String schemaName = schemaType.getSchemaName();
        
        // Get registered schemas
        List<SchemaRecord> registeredSchemas = schemaStorage.getSchemas(schemaName);
        
        if (registeredSchemas.isEmpty()) {
            if (!schemaType.external()) {
                if (schemaType.version() == 1) {
                    // Register first version
                    schemaStorage.registerSchema(schemaName, localSchema, schemaType.version());
                } else {
                    throw new PreviousSchemaVersionMissingException(
                            schemaName,
                            schemaType.version(),
                            schemaType.typeClass());
                }
            } else {
                throw new SchemaNotFoundException(
                        schemaName,
                        schemaType.typeClass());
            }
        } else {
            // Check if this specific version exists
            SchemaRecord registeredSchemaRecord = registeredSchemas.stream()
                    .filter(record -> record.version() == schemaType.version())
                    .findFirst().orElse(null);
            
            if (registeredSchemaRecord != null) {
                JsonSchema registeredSchema = registeredSchemaRecord.schema();
                
                // Validate compatibility based on schema type
                if (schemaType.external() && schemaType.relaxExternalValidation()) {
                    validateRelaxedCompatibility(schemaName, schemaType, localSchema, registeredSchema);
                } else {
                    validateStrictCompatibility(schemaName, schemaType, localSchema, registeredSchema, forceRegisterOnIncompatibleSchema);
                }
            } else if (schemaType.external()) {
                throw new SchemaNotFoundException(
                        schemaName,
                        schemaType.typeClass());
            } else {
                // New version of an existing schema
                registeredSchemas.sort(Comparator.comparingInt(SchemaRecord::version));
                SchemaRecord lastSchema = registeredSchemas.getLast();
                
                if (schemaType.version() != lastSchema.version() + 1) {
                    throw new InvalidSchemaVersionException(
                            schemaName,
                            lastSchema.version(),
                            schemaType.version(),
                            schemaType.typeClass());
                }
                
                // Check backward compatibility
                validateBackwardCompatibility(schemaName, schemaType, localSchema, lastSchema.schema());
                
                // Register new version
                schemaStorage.registerSchema(schemaName, localSchema, schemaType.version());
            }
        }
        
        return localSchema;
    }

    private void validateRelaxedCompatibility(String schemaName, SchemaType<?> schemaType, 
                                             JsonSchema localSchema, JsonSchema registeredSchema) {
        List<Difference> differences = SchemaDiff.compare(registeredSchema.rawSchema(), localSchema.rawSchema());
        if (!differences.isEmpty()) {
            List<Difference> violatingDifferences = differences.stream()
                    .filter(difference -> !difference.getType().equals(Difference.Type.PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL))
                    .toList();
            if (!violatingDifferences.isEmpty()) {
                throw new IncompatibleSchemaException(
                        schemaName,
                        schemaType.version(),
                        schemaType.typeClass(),
                        violatingDifferences);
            }
        }
    }

    private void validateStrictCompatibility(String schemaName, SchemaType<?> schemaType,
                                            JsonSchema localSchema, JsonSchema registeredSchema,
                                            boolean forceRegisterOnIncompatibleSchema) {
        if (!registeredSchema.deepEquals(localSchema)) {
            if (!Objects.equals(registeredSchema.toString(), localSchema.toString())) {
                List<Difference> violatingDifferences = SchemaDiff.compare(registeredSchema.rawSchema(), localSchema.rawSchema());
                
                if (forceRegisterOnIncompatibleSchema) {
                    logger.warn("Found an incompatible schema for {} v{} but forceRegisterOnIncompatibleSchema=true. Overwriting existing entry", 
                            schemaName, schemaType.version());
                    try {
                        schemaStorage.deleteSchema(schemaName, schemaType.version());
                        schemaStorage.registerSchema(schemaName, localSchema, schemaType.version());
                    } catch (SchemaException e) {
                        logger.error("Exception during overwrite of Schema {} with version {}", 
                                schemaName, schemaType.version(), e);
                    }
                } else {
                    throw new IncompatibleSchemaException(
                            schemaName,
                            schemaType.version(),
                            schemaType.typeClass(),
                            violatingDifferences);
                }
            }
        }
    }

    private void validateBackwardCompatibility(String schemaName, SchemaType<?> schemaType,
                                               JsonSchema localSchema, JsonSchema previousSchema) {
        List<Difference> differences = SchemaDiff.compare(previousSchema.rawSchema(), localSchema.rawSchema())
                .stream().filter(diff ->
                        !SchemaDiff.COMPATIBLE_CHANGES.contains(diff.getType()) &&
                                !Difference.Type.REQUIRED_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL.equals(diff.getType()))
                .toList();
        
        if (!differences.isEmpty()) {
            throw new SchemaNotBackwardsCompatibleException(
                    schemaName,
                    previousSchema.version(),
                    schemaType.version(),
                    schemaType.typeClass(),
                    differences);
        }
    }

    private JsonSchema registerAndValidateWithRegistryClient(SchemaType<?> schemaType, boolean forceRegisterOnIncompatibleSchema) throws SchemaException {
        try {
            // generate the local schema version
            JsonSchema localSchema = generateJsonSchema(schemaType);
            // check if the type exists in the registry
            String schemaName = schemaType.getSchemaName();
            List<ParsedSchema> registeredSchemas = schemaRegistryClient.getSchemas(
                    schemaName,
                    false,
                    false);
            if (registeredSchemas.isEmpty()) {
                if (!schemaType.external()) {
                    if (schemaType.version() == 1) {
                        // we need to create the schema and register it (not external ones as they are owned by another aggregate)
                        schemaRegistryClient.register(
                                schemaName,
                                localSchema,
                                schemaType.version(),
                                -1);
                    } else {
                        // we are missing schema(s) for the previous version(s)
                        throw new PreviousSchemaVersionMissingException(
                                schemaName,
                                schemaType.version(),
                                schemaType.typeClass());
                    }
                } else {
                    // this is an error since we cannot find a registered schema for an external event
                    throw new SchemaNotFoundException(
                            schemaName,
                            schemaType.typeClass());
                }
            } else {
                // see if it is an existing schema
                ParsedSchema registeredSchema = registeredSchemas.stream()
                        .filter(parsedSchema -> getSchemaVersion(schemaType, parsedSchema) == schemaType.version())
                        .findFirst().orElse(null);
                if (registeredSchema != null) {
                    // we need to make sure that the schemas are compatible
                    if (schemaType.external() && schemaType.relaxExternalValidation()) {
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
                            if (!violatingDifferences.isEmpty()) {
                                // our implementaion class is incompatible with the registered schema
                                throw new IncompatibleSchemaException(
                                        schemaName,
                                        schemaType.version(),
                                        schemaType.typeClass(),
                                        violatingDifferences);
                            }
                        }
                    } else {
                        // it has to be exactly the same
                        if (!registeredSchema.deepEquals(localSchema)) {
                            // in some weird edge cases Objects.equals(registeredSchema.rawSchema(), localSchema.rawSchema() is false
                            // however Objects.equals(registeredSchema.toString(), localSchema.toString()) is true
                            if (!Objects.equals(registeredSchema.toString(), localSchema.toString())) {
                                List<Difference> violatingDifferences = SchemaDiff.compare(((JsonSchema) registeredSchema).rawSchema(), localSchema.rawSchema());
                                // our implementation class is incompatible with the registered schema
                                if(forceRegisterOnIncompatibleSchema) {
                                    logger.warn("Found an incompatible schema for {} v{} but forceRegisterOnIncompatibleSchema=true. Overwriting existing entry in SchemaRegistry", schemaName, schemaType.version());
                                    try {
                                        // first a soft delete
                                        schemaRegistryClient.deleteSchemaVersion(
                                                schemaName,
                                                "" + schemaType.version());
                                        // then do a hard delete of the version
                                        schemaRegistryClient.deleteSchemaVersion(
                                                schemaName,
                                                "" + schemaType.version(),
                                                true);
                                        // and recreate it
                                        schemaRegistryClient.register(
                                                schemaName,
                                                localSchema,
                                                schemaType.version(),
                                                -1);
                                    } catch (IOException | RestClientException e) {
                                        logger.error(
                                                "Exception during overwrite of Schema {} with version {}",
                                                schemaName,
                                                schemaType.version(),
                                                e);
                                }
                                } else {
                                    throw new IncompatibleSchemaException(
                                            schemaName,
                                            schemaType.version(),
                                            schemaType.typeClass(),
                                            violatingDifferences);
                                }
                            }
                        }
                    }
                } else if (schemaType.external()) {
                    // we did not find any schema with the exact version.
                    // since we are registering the type ourselves, this is an error
                    throw new SchemaNotFoundException(
                            schemaName,
                            schemaType.typeClass());
                } else {
                    // ensure we have an ordered list of schemas
                    registeredSchemas.sort(Comparator.comparingInt(parsedSchema -> getSchemaVersion(schemaType, parsedSchema)));
                    // see if the new version is exactly one higher than the last version
                    if (schemaType.version() != getSchemaVersion(schemaType,registeredSchemas.getLast()) + 1) {
                        throw new InvalidSchemaVersionException(
                                schemaName,
                                registeredSchemas.getLast().version(),
                                schemaType.version(),
                                schemaType.typeClass());
                    }
                    // see if the new schema is backwards compatible with the previous ones
                    List<Difference> differences = SchemaDiff.compare(
                                    ((JsonSchema)registeredSchemas.getLast()).rawSchema(),
                                    localSchema.rawSchema())
                            .stream().filter(diff ->
                                    !SchemaDiff.COMPATIBLE_CHANGES.contains(diff.getType()) &&
                                            !Difference.Type.REQUIRED_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL.equals(diff.getType())).toList();
                    if (differences.isEmpty()) {
                        // register the new schema
                        schemaRegistryClient.register(
                                schemaName,
                                localSchema,
                                schemaType.version(),
                                -1);
                    } else {
                        // incompatible
                        throw new SchemaNotBackwardsCompatibleException(
                                schemaName,
                                getSchemaVersion(schemaType,registeredSchemas.getLast()),
                                schemaType.version(),
                                schemaType.typeClass(),
                                differences);
                    }
                }
            }
            // schema is fine, return
            return localSchema;
        } catch (IOException | RestClientException e) {
            throw new SchemaException(
                    "Unexpected Error while validating schema",
                    schemaType.getSchemaName(),
                    schemaType.typeClass(),
                    e);
        }
    }

    public JsonSchema generateJsonSchema(SchemaType<?> schemaType) {
        return new JsonSchema(schemaGeneratorTheadLocal.get().generateSchema(schemaType.typeClass()), List.of(), Map.of(), schemaType.version());
    }

    private int getSchemaVersion(SchemaType<?> schemaType, ParsedSchema parsedSchema) {
        try {
            return schemaRegistryClient.getVersion(schemaType.getSchemaName(), parsedSchema);
        } catch (IOException | RestClientException e) {
            throw new RuntimeException(e);
        }
    }

    private SchemaGenerator createJsonSchemaGenerator(ObjectMapper objectMapper) {
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
        return new SchemaGenerator(configBuilder.build());
    }

}
