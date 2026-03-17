/*
 * Copyright 2022 - 2026 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package org.elasticsoftware.akces.schemas.diff;

/**
 * Represents a difference found when comparing two JSON schemas.
 * This is a replacement for {@code io.confluent.kafka.schemaregistry.json.diff.Difference}.
 *
 * @param type     the type of difference
 * @param jsonPath the JSON path where the difference was found
 */
public record Difference(Type type, String jsonPath) {

    /**
     * Enumeration of all possible difference types that can be detected
     * when comparing two JSON schemas.
     */
    public enum Type {
        // General
        ID_CHANGED,
        DESCRIPTION_CHANGED,
        TITLE_CHANGED,
        DEFAULT_CHANGED,
        SCHEMA_REMOVED,
        SCHEMA_ADDED,

        // Type changes
        TYPE_CHANGED,
        TYPE_EXTENDED,
        TYPE_NARROWED,

        // String constraints
        MAX_LENGTH_ADDED,
        MAX_LENGTH_INCREASED,
        MAX_LENGTH_DECREASED,
        MAX_LENGTH_REMOVED,
        MIN_LENGTH_ADDED,
        MIN_LENGTH_INCREASED,
        MIN_LENGTH_DECREASED,
        MIN_LENGTH_REMOVED,
        PATTERN_ADDED,
        PATTERN_CHANGED,
        PATTERN_REMOVED,

        // Numeric constraints
        MAXIMUM_ADDED,
        MAXIMUM_INCREASED,
        MAXIMUM_DECREASED,
        MAXIMUM_REMOVED,
        MINIMUM_ADDED,
        MINIMUM_INCREASED,
        MINIMUM_DECREASED,
        MINIMUM_REMOVED,
        EXCLUSIVE_MAXIMUM_ADDED,
        EXCLUSIVE_MAXIMUM_INCREASED,
        EXCLUSIVE_MAXIMUM_DECREASED,
        EXCLUSIVE_MAXIMUM_REMOVED,
        EXCLUSIVE_MINIMUM_ADDED,
        EXCLUSIVE_MINIMUM_INCREASED,
        EXCLUSIVE_MINIMUM_DECREASED,
        EXCLUSIVE_MINIMUM_REMOVED,
        MULTIPLE_OF_ADDED,
        MULTIPLE_OF_EXPANDED,
        MULTIPLE_OF_REDUCED,
        MULTIPLE_OF_REMOVED,

        // Required attributes
        REQUIRED_ATTRIBUTE_ADDED,
        REQUIRED_ATTRIBUTE_WITH_DEFAULT_ADDED,
        REQUIRED_ATTRIBUTE_REMOVED,

        // Dependencies
        DEPENDENCY_ARRAY_ADDED,
        DEPENDENCY_ARRAY_EXTENDED,
        DEPENDENCY_ARRAY_NARROWED,
        DEPENDENCY_ARRAY_CHANGED,
        DEPENDENCY_ARRAY_REMOVED,
        DEPENDENCY_SCHEMA_ADDED,
        DEPENDENCY_SCHEMA_CHANGED,
        DEPENDENCY_SCHEMA_REMOVED,

        // Properties (object)
        MAX_PROPERTIES_ADDED,
        MAX_PROPERTIES_INCREASED,
        MAX_PROPERTIES_DECREASED,
        MAX_PROPERTIES_REMOVED,
        MIN_PROPERTIES_ADDED,
        MIN_PROPERTIES_INCREASED,
        MIN_PROPERTIES_DECREASED,
        MIN_PROPERTIES_REMOVED,
        ADDITIONAL_PROPERTIES_ADDED,
        ADDITIONAL_PROPERTIES_REMOVED,
        ADDITIONAL_PROPERTIES_EXTENDED,
        ADDITIONAL_PROPERTIES_NARROWED,
        PROPERTY_WITH_EMPTY_SCHEMA_ADDED_TO_OPEN_CONTENT_MODEL,
        PROPERTY_ADDED_TO_OPEN_CONTENT_MODEL,
        PROPERTY_ADDED_TO_CLOSED_CONTENT_MODEL,
        REQUIRED_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL,
        REQUIRED_PROPERTY_WITH_DEFAULT_ADDED_TO_UNOPEN_CONTENT_MODEL,
        OPTIONAL_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL,
        PROPERTY_ADDED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
        PROPERTY_ADDED_NOT_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
        PROPERTY_WITH_FALSE_REMOVED_FROM_CLOSED_CONTENT_MODEL,
        PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL,
        PROPERTY_REMOVED_FROM_OPEN_CONTENT_MODEL,
        PROPERTY_REMOVED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
        PROPERTY_REMOVED_NOT_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,

        // Array items
        MAX_ITEMS_ADDED,
        MAX_ITEMS_INCREASED,
        MAX_ITEMS_DECREASED,
        MAX_ITEMS_REMOVED,
        MIN_ITEMS_ADDED,
        MIN_ITEMS_INCREASED,
        MIN_ITEMS_DECREASED,
        MIN_ITEMS_REMOVED,
        UNIQUE_ITEMS_ADDED,
        UNIQUE_ITEMS_REMOVED,
        ADDITIONAL_ITEMS_ADDED,
        ADDITIONAL_ITEMS_REMOVED,
        ADDITIONAL_ITEMS_EXTENDED,
        ADDITIONAL_ITEMS_NARROWED,
        ITEM_WITH_EMPTY_SCHEMA_ADDED_TO_OPEN_CONTENT_MODEL,
        ITEM_ADDED_TO_OPEN_CONTENT_MODEL,
        ITEM_ADDED_TO_CLOSED_CONTENT_MODEL,
        ITEM_REMOVED_FROM_OPEN_CONTENT_MODEL,
        ITEM_REMOVED_FROM_CLOSED_CONTENT_MODEL,
        ITEM_WITH_FALSE_REMOVED_FROM_CLOSED_CONTENT_MODEL,
        ITEM_ADDED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
        ITEM_ADDED_NOT_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
        ITEM_REMOVED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
        ITEM_REMOVED_NOT_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,

        // Enum
        ENUM_ARRAY_CHANGED,
        ENUM_ARRAY_EXTENDED,
        ENUM_ARRAY_NARROWED,

        // Combined schemas
        COMBINED_TYPE_CHANGED,
        COMBINED_TYPE_SUBSCHEMAS_CHANGED,
        COMBINED_TYPE_EXTENDED,
        COMBINED_TYPE_NARROWED,

        // Product/Sum/Not
        PRODUCT_TYPE_EXTENDED,
        PRODUCT_TYPE_NARROWED,
        SUM_TYPE_EXTENDED,
        SUM_TYPE_NARROWED,
        NOT_TYPE_EXTENDED,
        NOT_TYPE_NARROWED
    }

    /**
     * Returns the type of this difference.
     *
     * @return the difference type
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the JSON path where this difference was found.
     *
     * @return the JSON path
     */
    public String getJsonPath() {
        return jsonPath;
    }

    @Override
    public String toString() {
        return "Difference{type=" + type + ", jsonPath=" + jsonPath + "}";
    }
}
