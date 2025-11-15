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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import io.confluent.kafka.schemaregistry.json.diff.Difference;
import io.confluent.kafka.schemaregistry.json.diff.SchemaDiff;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.aggregate.SchemaType;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.storage.SchemaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

public class KafkaSchemaRegistry implements SchemaRegistry {
    private static final Logger logger = LoggerFactory.getLogger(KafkaSchemaRegistry.class);
    private final SchemaStorage schemaStorage;
    // schema generator is not thread safe
    private final ThreadLocal<SchemaGenerator> schemaGeneratorTheadLocal;

    /**
     * Creates a KafkaSchemaRegistry using Kafka topic-based schema storage.
     */
    public KafkaSchemaRegistry(SchemaStorage schemaStorage, ObjectMapper objectMapper) {
        this.schemaStorage = schemaStorage;
        this.schemaGeneratorTheadLocal = ThreadLocal.withInitial(() -> SchemaRegistry.createJsonSchemaGenerator(objectMapper));
    }

    @Override
    public JsonSchema validate(CommandType<?> commandType) throws SchemaException {
        return validate(commandType, true);
    }

    @Override
    public JsonSchema validate(DomainEventType<?> domainEventType) throws SchemaException {
        return validate(domainEventType, false);
    }

    private JsonSchema validate(SchemaType<?> schemaType, boolean strict) throws SchemaException {
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

    @Override
    public JsonSchema registerAndValidate(SchemaType<?> schemaType, boolean forceRegisterOnIncompatibleSchema) throws SchemaException {
        // generate the local schema version
        JsonSchema localSchema = generateJsonSchema(schemaType);
        String schemaName = schemaType.getSchemaName();
        
        // Get registered schemas
        List<SchemaRecord> registeredSchemas = schemaStorage.getSchemas(schemaName);
        
        if (registeredSchemas.isEmpty()) {
            if (!schemaType.external()) {
                if (schemaType.version() == 1) {
                    // Register first version
                    schemaStorage.saveSchema(schemaName, localSchema, schemaType.version());
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
                // registeredSchemas.sort(Comparator.comparingInt(SchemaRecord::version));
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
                schemaStorage.saveSchema(schemaName, localSchema, schemaType.version());
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
                        schemaStorage.saveSchema(schemaName, localSchema, schemaType.version());
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

    @Override
    public JsonSchema generateJsonSchema(SchemaType<?> schemaType) {
        return SchemaRegistry.generateJsonSchema(schemaType, schemaGeneratorTheadLocal.get());
    }

}
