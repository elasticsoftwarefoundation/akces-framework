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

package org.elasticsoftware.akces.schemas;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Objects;

/**
 * Stores a JSON Schema definition and provides validation and comparison functionality.
 * <p>
 * This is a replacement for {@code io.confluent.kafka.schemaregistry.json.JsonSchema}.
 * It wraps a Jackson {@link JsonNode} representation of the schema and an Everit
 * {@link Schema} used for validation.
 */
public final class JsonSchema {
    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new JsonMapper();

    private final ObjectMapper objectMapper;
    private final JsonNode schemaNode;
    private final Schema rawSchema;
    private final Integer version;

    /**
     * Creates a JsonSchema by parsing a JSON string, with no version.
     * Uses the default static ObjectMapper.
     *
     * @param schemaJson the JSON Schema as a string
     * @throws IllegalArgumentException if the JSON string cannot be parsed
     */
    public JsonSchema(String schemaJson) {
        this(schemaJson, null, DEFAULT_OBJECT_MAPPER);
    }

    /**
     * Creates a JsonSchema by parsing a JSON string, with a specific version.
     * Uses the default static ObjectMapper.
     *
     * @param schemaJson the JSON Schema as a string
     * @param version    the schema version, may be {@code null}
     * @throws IllegalArgumentException if the JSON string cannot be parsed or is not a valid JSON Schema
     */
    public JsonSchema(String schemaJson, Integer version) {
        this(schemaJson, version, DEFAULT_OBJECT_MAPPER);
    }

    /**
     * Creates a JsonSchema by parsing a JSON string, with a specific version and a custom ObjectMapper.
     *
     * @param schemaJson   the JSON Schema as a string
     * @param version      the schema version, may be {@code null}
     * @param objectMapper the ObjectMapper to use for JSON processing
     * @throws IllegalArgumentException if the JSON string cannot be parsed or is not a valid JSON Schema
     */
    public JsonSchema(String schemaJson, Integer version, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try {
            this.schemaNode = objectMapper.readTree(schemaJson);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid JSON schema: " + schemaJson, e);
        }
        this.version = version;
        this.rawSchema = loadSchema(this.schemaNode, objectMapper);
    }

    /**
     * Creates a JsonSchema from a Jackson {@link JsonNode}, with a specific version.
     * Uses the default static ObjectMapper.
     *
     * @param schemaNode the JSON Schema as a Jackson JsonNode
     * @param version    the schema version, may be {@code null}
     * @throws IllegalArgumentException if schemaNode is {@code null} or not a valid JSON Schema
     */
    public JsonSchema(JsonNode schemaNode, Integer version) {
        this(schemaNode, version, DEFAULT_OBJECT_MAPPER);
    }

    /**
     * Creates a JsonSchema from a Jackson {@link JsonNode}, with a specific version and a custom ObjectMapper.
     *
     * @param schemaNode   the JSON Schema as a Jackson JsonNode
     * @param version      the schema version, may be {@code null}
     * @param objectMapper the ObjectMapper to use for JSON processing
     * @throws IllegalArgumentException if schemaNode is {@code null} or not a valid JSON Schema
     */
    public JsonSchema(JsonNode schemaNode, Integer version, ObjectMapper objectMapper) {
        if (schemaNode == null) {
            throw new IllegalArgumentException("schemaNode cannot be null");
        }
        this.objectMapper = objectMapper;
        this.schemaNode = schemaNode;
        this.version = version;
        this.rawSchema = loadSchema(schemaNode, objectMapper);
    }

    private static Schema loadSchema(JsonNode schemaNode, ObjectMapper objectMapper) {
        try {
            String schemaString = objectMapper.writeValueAsString(schemaNode);
            JSONObject jsonObject = new JSONObject(schemaString);
            return SchemaLoader.load(jsonObject);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to serialize schema node to JSON string", e);
        }
    }

    /**
     * Validates the given data against this JSON Schema.
     *
     * @param data the data to validate as a Jackson {@link JsonNode}
     * @return the input data node on success
     * @throws ValidationException if the data does not conform to this schema
     */
    public JsonNode validate(JsonNode data) throws ValidationException {
        Object jsonData = new JSONTokener(data.toString()).nextValue();
        rawSchema.validate(jsonData);
        return data;
    }

    /**
     * Converts a Java object to a Jackson {@link JsonNode} using the internal ObjectMapper.
     *
     * @param object the object to convert
     * @return the JSON representation as a JsonNode
     */
    public JsonNode toJson(Object object) {
        return objectMapper.valueToTree(object);
    }

    /**
     * Returns the JSON Schema as a Jackson {@link JsonNode}.
     *
     * @return the schema node
     */
    public JsonNode toJsonNode() {
        return schemaNode;
    }

    /**
     * Returns the parsed Everit {@link Schema} object, for use by {@code SchemaDiff}.
     *
     * @return the raw Everit schema
     */
    public Schema rawSchema() {
        return rawSchema;
    }

    /**
     * Returns the schema version.
     *
     * @return the version, may be {@code null}
     */
    public Integer version() {
        return version;
    }

    /**
     * Performs a deep comparison of this schema with another {@link JsonSchema}.
     *
     * @param other the other schema to compare with
     * @return {@code true} if both schemas have equal JSON representations
     */
    public boolean deepEquals(JsonSchema other) {
        if (this == other) return true;
        if (other == null) return false;
        return schemaNode.equals(other.schemaNode);
    }

    @Override
    public String toString() {
        try {
            return objectMapper.writeValueAsString(schemaNode);
        } catch (JacksonException e) {
            return schemaNode.toString();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JsonSchema that)) return false;
        return Objects.equals(schemaNode, that.schemaNode)
                && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaNode, version);
    }
}
