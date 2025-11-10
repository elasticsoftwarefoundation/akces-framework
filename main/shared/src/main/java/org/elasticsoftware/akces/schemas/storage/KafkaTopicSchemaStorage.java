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

package org.elasticsoftware.akces.schemas.storage;

import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.SchemaException;

import java.util.List;
import java.util.Optional;

/**
 * Storage interface for managing JSON schemas in a Kafka topic.
 * This interface provides methods to register and retrieve schemas.
 * Schemas cannot be deleted, only overwritten by registering a new version.
 * Implementations are expected to use a Kafka compacted topic for persistence
 * and maintain an in-memory cache for performance.
 */
public interface KafkaTopicSchemaStorage {
    
    /**
     * Registers a new schema version in the storage.
     * 
     * @param schemaName the name of the schema (e.g., "CreateWalletCommand")
     * @param schema the JSON schema definition
     * @param version the version number of the schema
     * @throws SchemaException if the schema cannot be registered
     */
    void registerSchema(String schemaName, JsonSchema schema, int version) throws SchemaException;
    
    /**
     * Retrieves all versions of a schema.
     * 
     * @param schemaName the name of the schema
     * @return list of all registered versions of the schema, ordered by version
     * @throws SchemaException if there's an error retrieving the schemas
     */
    List<SchemaRecord> getSchemas(String schemaName) throws SchemaException;
    
    /**
     * Retrieves a specific version of a schema.
     * 
     * @param schemaName the name of the schema
     * @param version the version number
     * @return an Optional containing the schema if found, empty otherwise
     * @throws SchemaException if there's an error retrieving the schema
     */
    Optional<SchemaRecord> getSchema(String schemaName, int version) throws SchemaException;
    
    /**
     * Initializes the storage and loading the initial cache.
     * 
     * @throws SchemaException if initialization fails
     */
    void initialize() throws SchemaException;
    
    /**
     * Closes the storage, releasing all resources.
     */
    void close();
}
