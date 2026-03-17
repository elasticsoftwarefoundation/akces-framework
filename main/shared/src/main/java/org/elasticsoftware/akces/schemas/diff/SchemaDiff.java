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

import org.everit.json.schema.ArraySchema;
import org.everit.json.schema.CombinedSchema;
import org.everit.json.schema.EmptySchema;
import org.everit.json.schema.EnumSchema;
import org.everit.json.schema.FalseSchema;
import org.everit.json.schema.NotSchema;
import org.everit.json.schema.NumberSchema;
import org.everit.json.schema.ObjectSchema;
import org.everit.json.schema.ReferenceSchema;
import org.everit.json.schema.Schema;
import org.everit.json.schema.StringSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.elasticsoftware.akces.schemas.diff.Difference.Type.*;

/**
 * Compares two JSON schemas (Everit representation) and produces a list of differences.
 * <p>
 * This is a replacement for {@code io.confluent.kafka.schemaregistry.json.diff.SchemaDiff}.
 * It walks the Everit schema trees recursively and reports all structural and constraint
 * differences found between the original and updated schemas.
 */
public final class SchemaDiff {

    private SchemaDiff() {
        // utility class
    }

    /**
     * Set of difference types that are considered backward-compatible under strict mode.
     * Changes of these types do not break consumers reading data produced with the original schema.
     */
    public static final Set<Difference.Type> COMPATIBLE_CHANGES_STRICT = Collections.unmodifiableSet(EnumSet.of(
            ID_CHANGED,
            DESCRIPTION_CHANGED,
            TITLE_CHANGED,
            DEFAULT_CHANGED,
            SCHEMA_REMOVED,
            TYPE_EXTENDED,
            MAX_LENGTH_INCREASED,
            MAX_LENGTH_REMOVED,
            MIN_LENGTH_DECREASED,
            MIN_LENGTH_REMOVED,
            PATTERN_REMOVED,
            MAXIMUM_INCREASED,
            MAXIMUM_REMOVED,
            MINIMUM_DECREASED,
            MINIMUM_REMOVED,
            EXCLUSIVE_MAXIMUM_INCREASED,
            EXCLUSIVE_MAXIMUM_REMOVED,
            EXCLUSIVE_MINIMUM_DECREASED,
            EXCLUSIVE_MINIMUM_REMOVED,
            MULTIPLE_OF_REDUCED,
            MULTIPLE_OF_REMOVED,
            REQUIRED_ATTRIBUTE_WITH_DEFAULT_ADDED,
            REQUIRED_ATTRIBUTE_REMOVED,
            DEPENDENCY_ARRAY_NARROWED,
            DEPENDENCY_ARRAY_REMOVED,
            DEPENDENCY_SCHEMA_REMOVED,
            MAX_PROPERTIES_INCREASED,
            MAX_PROPERTIES_REMOVED,
            MIN_PROPERTIES_DECREASED,
            MIN_PROPERTIES_REMOVED,
            ADDITIONAL_PROPERTIES_ADDED,
            ADDITIONAL_PROPERTIES_EXTENDED,
            PROPERTY_WITH_EMPTY_SCHEMA_ADDED_TO_OPEN_CONTENT_MODEL,
            REQUIRED_PROPERTY_WITH_DEFAULT_ADDED_TO_UNOPEN_CONTENT_MODEL,
            OPTIONAL_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL,
            PROPERTY_WITH_FALSE_REMOVED_FROM_CLOSED_CONTENT_MODEL,
            PROPERTY_REMOVED_FROM_OPEN_CONTENT_MODEL,
            PROPERTY_ADDED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
            PROPERTY_REMOVED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
            MAX_ITEMS_INCREASED,
            MAX_ITEMS_REMOVED,
            MIN_ITEMS_DECREASED,
            MIN_ITEMS_REMOVED,
            UNIQUE_ITEMS_REMOVED,
            ADDITIONAL_ITEMS_ADDED,
            ADDITIONAL_ITEMS_EXTENDED,
            ITEM_WITH_EMPTY_SCHEMA_ADDED_TO_OPEN_CONTENT_MODEL,
            ITEM_ADDED_TO_CLOSED_CONTENT_MODEL,
            ITEM_WITH_FALSE_REMOVED_FROM_CLOSED_CONTENT_MODEL,
            ITEM_REMOVED_FROM_OPEN_CONTENT_MODEL,
            ITEM_ADDED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
            ITEM_REMOVED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
            ENUM_ARRAY_EXTENDED,
            COMBINED_TYPE_EXTENDED,
            PRODUCT_TYPE_NARROWED,
            SUM_TYPE_EXTENDED,
            NOT_TYPE_NARROWED
    ));

    /**
     * Compares two JSON schemas and returns an unmodifiable list of differences found.
     *
     * @param original the original (older) schema
     * @param updated  the updated (newer) schema
     * @return an unmodifiable list of differences between the two schemas
     */
    public static List<Difference> compare(Schema original, Schema updated) {
        List<Difference> differences = new ArrayList<>();
        compareSchemas("#", original, updated, differences);
        return Collections.unmodifiableList(differences);
    }

    // --- Core comparison ---

    private static Schema resolveSchema(Schema schema) {
        if (schema instanceof ReferenceSchema ref) {
            return resolveSchema(ref.getReferredSchema());
        }
        return schema;
    }

    private static void compareSchemas(String path, Schema original, Schema updated, List<Difference> diffs) {
        if (original == null && updated == null) {
            return;
        }
        if (original != null && updated == null) {
            diffs.add(new Difference(SCHEMA_REMOVED, path));
            return;
        }
        if (original == null) {
            diffs.add(new Difference(SCHEMA_ADDED, path));
            return;
        }

        Schema orig = resolveSchema(original);
        Schema upd = resolveSchema(updated);

        compareMetadata(path, orig, upd, diffs);

        if (orig.getClass().equals(upd.getClass())) {
            compareMatchingTypes(path, orig, upd, diffs);
        } else {
            reportTypeMismatch(path, orig, upd, diffs);
        }
    }

    private static void compareMatchingTypes(String path, Schema orig, Schema upd, List<Difference> diffs) {
        switch (orig) {
            case ObjectSchema o -> compareObjectSchemas(path, o, (ObjectSchema) upd, diffs);
            case StringSchema s -> compareStringSchemas(path, s, (StringSchema) upd, diffs);
            case NumberSchema n -> compareNumberSchemas(path, n, (NumberSchema) upd, diffs);
            case ArraySchema a -> compareArraySchemas(path, a, (ArraySchema) upd, diffs);
            case EnumSchema e -> compareEnumSchemas(path, e, (EnumSchema) upd, diffs);
            case CombinedSchema c -> compareCombinedSchemas(path, c, (CombinedSchema) upd, diffs);
            case NotSchema n -> compareNotSchemas(path, n, (NotSchema) upd, diffs);
            default -> {
                // BooleanSchema, NullSchema, EmptySchema, FalseSchema - leaf types with no constraints
            }
        }
    }

    private static void reportTypeMismatch(String path, Schema orig, Schema upd, List<Difference> diffs) {
        if (isTypeExtension(orig, upd)) {
            diffs.add(new Difference(TYPE_EXTENDED, path));
        } else if (isTypeNarrowing(orig, upd)) {
            diffs.add(new Difference(TYPE_NARROWED, path));
        } else {
            diffs.add(new Difference(TYPE_CHANGED, path));
        }
    }

    /**
     * Detects type extension: e.g. {@code "type": "string"} → {@code "type": ["string", "null"]}
     * (a plain type becoming a oneOf/anyOf combined type that includes the original type).
     */
    private static boolean isTypeExtension(Schema orig, Schema upd) {
        if (upd instanceof CombinedSchema combined && isOneOfOrAnyOf(combined)) {
            return combined.getSubschemas().stream()
                    .map(SchemaDiff::resolveSchema)
                    .anyMatch(sub -> sub.getClass().equals(orig.getClass()));
        }
        return false;
    }

    /**
     * Detects type narrowing: e.g. {@code "type": ["string", "null"]} → {@code "type": "string"}
     * (a oneOf/anyOf combined type becoming a single plain type).
     */
    private static boolean isTypeNarrowing(Schema orig, Schema upd) {
        if (orig instanceof CombinedSchema combined && isOneOfOrAnyOf(combined)) {
            return combined.getSubschemas().stream()
                    .map(SchemaDiff::resolveSchema)
                    .anyMatch(sub -> sub.getClass().equals(upd.getClass()));
        }
        return false;
    }

    private static boolean isOneOfOrAnyOf(CombinedSchema schema) {
        var criterion = schema.getCriterion();
        return criterion == CombinedSchema.ONE_CRITERION || criterion == CombinedSchema.ANY_CRITERION;
    }

    // --- Metadata comparison ---

    private static void compareMetadata(String path, Schema orig, Schema upd, List<Difference> diffs) {
        if (!Objects.equals(orig.getId(), upd.getId())) {
            diffs.add(new Difference(ID_CHANGED, path));
        }
        if (!Objects.equals(orig.getTitle(), upd.getTitle())) {
            diffs.add(new Difference(TITLE_CHANGED, path));
        }
        if (!Objects.equals(orig.getDescription(), upd.getDescription())) {
            diffs.add(new Difference(DESCRIPTION_CHANGED, path));
        }
        boolean origHasDefault = orig.hasDefaultValue();
        boolean updHasDefault = upd.hasDefaultValue();
        if (origHasDefault != updHasDefault
                || (origHasDefault && !Objects.equals(orig.getDefaultValue(), upd.getDefaultValue()))) {
            diffs.add(new Difference(DEFAULT_CHANGED, path));
        }
    }

    // --- Object Schema ---

    private static void compareObjectSchemas(String path, ObjectSchema orig, ObjectSchema upd, List<Difference> diffs) {
        Map<String, Schema> origProps = orig.getPropertySchemas();
        Map<String, Schema> updProps = upd.getPropertySchemas();
        Set<String> updRequired = new HashSet<>(upd.getRequiredProperties());

        // Removed properties (in original but not in updated)
        for (Map.Entry<String, Schema> entry : origProps.entrySet()) {
            if (!updProps.containsKey(entry.getKey())) {
                reportPropertyRemoval(path + "/properties/" + entry.getKey(), entry.getValue(), upd, diffs);
            }
        }

        // Added properties (in updated but not in original)
        for (Map.Entry<String, Schema> entry : updProps.entrySet()) {
            if (!origProps.containsKey(entry.getKey())) {
                reportPropertyAddition(
                        path + "/properties/" + entry.getKey(),
                        entry.getValue(),
                        updRequired.contains(entry.getKey()),
                        orig,
                        diffs
                );
            }
        }

        // Common properties: recursively compare sub-schemas
        for (Map.Entry<String, Schema> entry : origProps.entrySet()) {
            String key = entry.getKey();
            if (updProps.containsKey(key)) {
                compareSchemas(path + "/properties/" + key, entry.getValue(), updProps.get(key), diffs);
            }
        }

        // Required attribute changes (for properties present in both schemas)
        compareRequiredAttributes(path, orig, upd, origProps, updProps, diffs);

        // additionalProperties changes
        compareAdditionalProperties(path, orig, upd, diffs);

        // maxProperties / minProperties changes
        compareOptionalInteger(path, orig.getMaxProperties(), upd.getMaxProperties(),
                MAX_PROPERTIES_ADDED, MAX_PROPERTIES_REMOVED, MAX_PROPERTIES_INCREASED, MAX_PROPERTIES_DECREASED, diffs);
        compareOptionalInteger(path, orig.getMinProperties(), upd.getMinProperties(),
                MIN_PROPERTIES_ADDED, MIN_PROPERTIES_REMOVED, MIN_PROPERTIES_DECREASED, MIN_PROPERTIES_INCREASED, diffs);
    }

    private static void reportPropertyRemoval(String propPath, Schema propertySchema, ObjectSchema updatedParent, List<Difference> diffs) {
        if (updatedParent.permitsAdditionalProperties()) {
            Schema additionalSchema = updatedParent.getSchemaOfAdditionalProperties();
            if (additionalSchema == null || additionalSchema instanceof EmptySchema) {
                diffs.add(new Difference(PROPERTY_REMOVED_FROM_OPEN_CONTENT_MODEL, propPath));
            } else {
                // Partially open content model
                diffs.add(new Difference(PROPERTY_REMOVED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL, propPath));
            }
        } else {
            // Closed content model
            if (resolveSchema(propertySchema) instanceof FalseSchema) {
                diffs.add(new Difference(PROPERTY_WITH_FALSE_REMOVED_FROM_CLOSED_CONTENT_MODEL, propPath));
            } else {
                diffs.add(new Difference(PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL, propPath));
            }
        }
    }

    private static void reportPropertyAddition(String propPath, Schema propertySchema, boolean isRequired,
                                                ObjectSchema originalParent, List<Difference> diffs) {
        if (!originalParent.permitsAdditionalProperties()) {
            // Unopen (closed) content model
            if (isRequired) {
                if (hasDefaultValue(propertySchema)) {
                    diffs.add(new Difference(REQUIRED_PROPERTY_WITH_DEFAULT_ADDED_TO_UNOPEN_CONTENT_MODEL, propPath));
                } else {
                    diffs.add(new Difference(REQUIRED_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL, propPath));
                }
            } else {
                diffs.add(new Difference(OPTIONAL_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL, propPath));
            }
        } else {
            Schema additionalSchema = originalParent.getSchemaOfAdditionalProperties();
            if (additionalSchema == null || additionalSchema instanceof EmptySchema) {
                // Fully open content model
                if (resolveSchema(propertySchema) instanceof EmptySchema) {
                    diffs.add(new Difference(PROPERTY_WITH_EMPTY_SCHEMA_ADDED_TO_OPEN_CONTENT_MODEL, propPath));
                } else {
                    diffs.add(new Difference(PROPERTY_ADDED_TO_OPEN_CONTENT_MODEL, propPath));
                }
            } else {
                // Partially open content model
                diffs.add(new Difference(PROPERTY_ADDED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL, propPath));
            }
        }
    }

    private static void compareRequiredAttributes(String path, ObjectSchema orig, ObjectSchema upd,
                                                   Map<String, Schema> origProps, Map<String, Schema> updProps,
                                                   List<Difference> diffs) {
        Set<String> origRequired = new HashSet<>(orig.getRequiredProperties());
        Set<String> updRequired = new HashSet<>(upd.getRequiredProperties());

        // Required attributes added (property exists in both schemas, newly required)
        for (String key : updRequired) {
            if (!origRequired.contains(key) && origProps.containsKey(key) && updProps.containsKey(key)) {
                Schema propSchema = updProps.get(key);
                if (hasDefaultValue(propSchema)) {
                    diffs.add(new Difference(REQUIRED_ATTRIBUTE_WITH_DEFAULT_ADDED, path + "/properties/" + key));
                } else {
                    diffs.add(new Difference(REQUIRED_ATTRIBUTE_ADDED, path + "/properties/" + key));
                }
            }
        }

        // Required attributes removed (property exists in both schemas, no longer required)
        for (String key : origRequired) {
            if (!updRequired.contains(key) && origProps.containsKey(key) && updProps.containsKey(key)) {
                diffs.add(new Difference(REQUIRED_ATTRIBUTE_REMOVED, path + "/properties/" + key));
            }
        }
    }

    private static void compareAdditionalProperties(String path, ObjectSchema orig, ObjectSchema upd, List<Difference> diffs) {
        boolean origPermits = orig.permitsAdditionalProperties();
        boolean updPermits = upd.permitsAdditionalProperties();

        if (!origPermits && updPermits) {
            diffs.add(new Difference(ADDITIONAL_PROPERTIES_ADDED, path));
        } else if (origPermits && !updPermits) {
            diffs.add(new Difference(ADDITIONAL_PROPERTIES_REMOVED, path));
        } else if (origPermits) {
            // Both permit additional properties — compare their schemas
            Schema origSchema = orig.getSchemaOfAdditionalProperties();
            Schema updSchema = upd.getSchemaOfAdditionalProperties();
            if (origSchema == null && updSchema != null) {
                // Unrestricted → specific schema: narrowed
                diffs.add(new Difference(ADDITIONAL_PROPERTIES_NARROWED, path));
            } else if (origSchema != null && updSchema == null) {
                // Specific schema → unrestricted: extended
                diffs.add(new Difference(ADDITIONAL_PROPERTIES_EXTENDED, path));
            } else if (origSchema != null) {
                // Both have specific schemas — recurse
                compareSchemas(path + "/additionalProperties", origSchema, updSchema, diffs);
            }
        }
    }

    // --- String Schema ---

    private static void compareStringSchemas(String path, StringSchema orig, StringSchema upd, List<Difference> diffs) {
        compareOptionalInteger(path, orig.getMaxLength(), upd.getMaxLength(),
                MAX_LENGTH_ADDED, MAX_LENGTH_REMOVED, MAX_LENGTH_INCREASED, MAX_LENGTH_DECREASED, diffs);

        compareOptionalInteger(path, orig.getMinLength(), upd.getMinLength(),
                MIN_LENGTH_ADDED, MIN_LENGTH_REMOVED, MIN_LENGTH_DECREASED, MIN_LENGTH_INCREASED, diffs);

        Pattern origPattern = orig.getPattern();
        Pattern updPattern = upd.getPattern();
        String origPatternStr = origPattern != null ? origPattern.pattern() : null;
        String updPatternStr = updPattern != null ? updPattern.pattern() : null;
        if (!Objects.equals(origPatternStr, updPatternStr)) {
            if (origPattern == null) {
                diffs.add(new Difference(PATTERN_ADDED, path));
            } else if (updPattern == null) {
                diffs.add(new Difference(PATTERN_REMOVED, path));
            } else {
                diffs.add(new Difference(PATTERN_CHANGED, path));
            }
        }
    }

    // --- Number Schema ---

    private static void compareNumberSchemas(String path, NumberSchema orig, NumberSchema upd, List<Difference> diffs) {
        // integer vs number type change
        if (orig.requiresInteger() != upd.requiresInteger()) {
            if (orig.requiresInteger()) {
                // integer → number: type extended (more values allowed)
                diffs.add(new Difference(TYPE_EXTENDED, path));
            } else {
                // number → integer: type narrowed (fewer values allowed)
                diffs.add(new Difference(TYPE_NARROWED, path));
            }
        }

        compareOptionalNumber(path, orig.getMaximum(), upd.getMaximum(),
                MAXIMUM_ADDED, MAXIMUM_REMOVED, MAXIMUM_INCREASED, MAXIMUM_DECREASED, diffs);

        compareOptionalNumber(path, orig.getMinimum(), upd.getMinimum(),
                MINIMUM_ADDED, MINIMUM_REMOVED, MINIMUM_DECREASED, MINIMUM_INCREASED, diffs);

        compareOptionalNumber(path, orig.getExclusiveMaximumLimit(), upd.getExclusiveMaximumLimit(),
                EXCLUSIVE_MAXIMUM_ADDED, EXCLUSIVE_MAXIMUM_REMOVED,
                EXCLUSIVE_MAXIMUM_INCREASED, EXCLUSIVE_MAXIMUM_DECREASED, diffs);

        compareOptionalNumber(path, orig.getExclusiveMinimumLimit(), upd.getExclusiveMinimumLimit(),
                EXCLUSIVE_MINIMUM_ADDED, EXCLUSIVE_MINIMUM_REMOVED,
                EXCLUSIVE_MINIMUM_DECREASED, EXCLUSIVE_MINIMUM_INCREASED, diffs);

        compareOptionalNumber(path, orig.getMultipleOf(), upd.getMultipleOf(),
                MULTIPLE_OF_ADDED, MULTIPLE_OF_REMOVED, MULTIPLE_OF_REDUCED, MULTIPLE_OF_EXPANDED, diffs);
    }

    // --- Array Schema ---

    private static void compareArraySchemas(String path, ArraySchema orig, ArraySchema upd, List<Difference> diffs) {
        compareOptionalInteger(path, orig.getMaxItems(), upd.getMaxItems(),
                MAX_ITEMS_ADDED, MAX_ITEMS_REMOVED, MAX_ITEMS_INCREASED, MAX_ITEMS_DECREASED, diffs);

        compareOptionalInteger(path, orig.getMinItems(), upd.getMinItems(),
                MIN_ITEMS_ADDED, MIN_ITEMS_REMOVED, MIN_ITEMS_DECREASED, MIN_ITEMS_INCREASED, diffs);

        if (orig.needsUniqueItems() != upd.needsUniqueItems()) {
            diffs.add(new Difference(upd.needsUniqueItems() ? UNIQUE_ITEMS_ADDED : UNIQUE_ITEMS_REMOVED, path));
        }

        // additionalItems changes
        compareAdditionalItems(path, orig, upd, diffs);

        // Single items schema (applies to all items)
        Schema origAllItems = orig.getAllItemSchema();
        Schema updAllItems = upd.getAllItemSchema();
        if (origAllItems != null || updAllItems != null) {
            compareSchemas(path + "/items", origAllItems, updAllItems, diffs);
        }

        // Tuple item schemas (positional)
        List<Schema> origItems = orig.getItemSchemas();
        List<Schema> updItems = upd.getItemSchemas();
        if (origItems != null && updItems != null) {
            compareTupleItems(path, origItems, updItems, orig, upd, diffs);
        } else if (origItems != null) {
            // Tuple items removed
            for (int i = 0; i < origItems.size(); i++) {
                reportItemRemoval(path + "/items/" + i, upd, diffs);
            }
        } else if (updItems != null) {
            // Tuple items added
            for (int i = 0; i < updItems.size(); i++) {
                reportItemAddition(path + "/items/" + i, updItems.get(i), orig, diffs);
            }
        }
    }

    private static void compareTupleItems(String path, List<Schema> origItems, List<Schema> updItems,
                                           ArraySchema orig, ArraySchema upd, List<Difference> diffs) {
        int commonCount = Math.min(origItems.size(), updItems.size());
        for (int i = 0; i < commonCount; i++) {
            compareSchemas(path + "/items/" + i, origItems.get(i), updItems.get(i), diffs);
        }
        // Added items
        for (int i = commonCount; i < updItems.size(); i++) {
            reportItemAddition(path + "/items/" + i, updItems.get(i), orig, diffs);
        }
        // Removed items
        for (int i = commonCount; i < origItems.size(); i++) {
            reportItemRemoval(path + "/items/" + i, upd, diffs);
        }
    }

    private static void reportItemAddition(String itemPath, Schema itemSchema, ArraySchema originalParent, List<Difference> diffs) {
        if (!originalParent.permitsAdditionalItems()) {
            // Closed content model — any item addition is to a closed model
            diffs.add(new Difference(ITEM_ADDED_TO_CLOSED_CONTENT_MODEL, itemPath));
        } else {
            Schema additionalSchema = originalParent.getSchemaOfAdditionalItems();
            if (additionalSchema == null || additionalSchema instanceof EmptySchema) {
                if (resolveSchema(itemSchema) instanceof EmptySchema) {
                    diffs.add(new Difference(ITEM_WITH_EMPTY_SCHEMA_ADDED_TO_OPEN_CONTENT_MODEL, itemPath));
                } else {
                    diffs.add(new Difference(ITEM_ADDED_TO_OPEN_CONTENT_MODEL, itemPath));
                }
            } else {
                diffs.add(new Difference(ITEM_ADDED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL, itemPath));
            }
        }
    }

    private static void reportItemRemoval(String itemPath, ArraySchema updatedParent, List<Difference> diffs) {
        if (updatedParent.permitsAdditionalItems()) {
            Schema additionalSchema = updatedParent.getSchemaOfAdditionalItems();
            if (additionalSchema == null || additionalSchema instanceof EmptySchema) {
                diffs.add(new Difference(ITEM_REMOVED_FROM_OPEN_CONTENT_MODEL, itemPath));
            } else {
                diffs.add(new Difference(ITEM_REMOVED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL, itemPath));
            }
        } else {
            diffs.add(new Difference(ITEM_REMOVED_FROM_CLOSED_CONTENT_MODEL, itemPath));
        }
    }

    private static void compareAdditionalItems(String path, ArraySchema orig, ArraySchema upd, List<Difference> diffs) {
        boolean origPermits = orig.permitsAdditionalItems();
        boolean updPermits = upd.permitsAdditionalItems();

        if (!origPermits && updPermits) {
            diffs.add(new Difference(ADDITIONAL_ITEMS_ADDED, path));
        } else if (origPermits && !updPermits) {
            diffs.add(new Difference(ADDITIONAL_ITEMS_REMOVED, path));
        } else if (origPermits) {
            Schema origSchema = orig.getSchemaOfAdditionalItems();
            Schema updSchema = upd.getSchemaOfAdditionalItems();
            if (origSchema == null && updSchema != null) {
                diffs.add(new Difference(ADDITIONAL_ITEMS_NARROWED, path));
            } else if (origSchema != null && updSchema == null) {
                diffs.add(new Difference(ADDITIONAL_ITEMS_EXTENDED, path));
            } else if (origSchema != null) {
                compareSchemas(path + "/additionalItems", origSchema, updSchema, diffs);
            }
        }
    }

    // --- Enum Schema ---

    private static void compareEnumSchemas(String path, EnumSchema orig, EnumSchema upd, List<Difference> diffs) {
        Set<Object> origValues = orig.getPossibleValues();
        Set<Object> updValues = upd.getPossibleValues();

        if (!origValues.equals(updValues)) {
            if (updValues.containsAll(origValues)) {
                diffs.add(new Difference(ENUM_ARRAY_EXTENDED, path));
            } else if (origValues.containsAll(updValues)) {
                diffs.add(new Difference(ENUM_ARRAY_NARROWED, path));
            } else {
                diffs.add(new Difference(ENUM_ARRAY_CHANGED, path));
            }
        }
    }

    // --- Combined Schema (allOf / anyOf / oneOf) ---

    private static void compareCombinedSchemas(String path, CombinedSchema orig, CombinedSchema upd, List<Difference> diffs) {
        var origCriterion = orig.getCriterion();
        var updCriterion = upd.getCriterion();

        if (origCriterion != updCriterion) {
            diffs.add(new Difference(COMBINED_TYPE_CHANGED, path));
            return;
        }

        List<Schema> origSubs = new ArrayList<>(orig.getSubschemas());
        List<Schema> updSubs = new ArrayList<>(upd.getSubschemas());

        // Match subschemas by type rather than position since getSubschemas() returns a Set
        Set<Integer> matchedUpdIndices = new HashSet<>();
        for (Schema origSub : origSubs) {
            Schema resolvedOrig = resolveSchema(origSub);
            boolean matched = false;
            for (int j = 0; j < updSubs.size(); j++) {
                if (matchedUpdIndices.contains(j)) continue;
                Schema resolvedUpd = resolveSchema(updSubs.get(j));
                if (resolvedOrig.getClass().equals(resolvedUpd.getClass())) {
                    compareSchemas(path, origSub, updSubs.get(j), diffs);
                    matchedUpdIndices.add(j);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                // Original subschema type not found in updated
                diffs.add(new Difference(COMBINED_TYPE_SUBSCHEMAS_CHANGED, path));
            }
        }
        // Check for new subschema types in updated
        for (int j = 0; j < updSubs.size(); j++) {
            if (!matchedUpdIndices.contains(j)) {
                diffs.add(new Difference(COMBINED_TYPE_SUBSCHEMAS_CHANGED, path));
            }
        }

        // Report subschema count changes
        if (updSubs.size() != origSubs.size()) {
            boolean isAllOf = origCriterion == CombinedSchema.ALL_CRITERION;
            if (updSubs.size() > origSubs.size()) {
                if (isAllOf) {
                    diffs.add(new Difference(PRODUCT_TYPE_EXTENDED, path));
                } else {
                    diffs.add(new Difference(SUM_TYPE_EXTENDED, path));
                }
            } else {
                if (isAllOf) {
                    diffs.add(new Difference(PRODUCT_TYPE_NARROWED, path));
                } else {
                    diffs.add(new Difference(SUM_TYPE_NARROWED, path));
                }
            }
        }
    }

    // --- Not Schema ---

    private static void compareNotSchemas(String path, NotSchema orig, NotSchema upd, List<Difference> diffs) {
        Schema origMustNotMatch = orig.getMustNotMatch();
        Schema updMustNotMatch = upd.getMustNotMatch();
        // For "not" schemas, compare the inner schemas.
        // Changes to the inner schema affect compatibility inversely:
        // extending the "not" constraint narrows what is valid, and vice versa.
        List<Difference> innerDiffs = new ArrayList<>();
        compareSchemas(path + "/not", origMustNotMatch, updMustNotMatch, innerDiffs);
        for (Difference innerDiff : innerDiffs) {
            // Map inner changes to not-schema level changes
            diffs.add(innerDiff);
        }
        if (!innerDiffs.isEmpty()) {
            // Determine overall direction: if inner schema was extended, the "not" is narrowed (compatible)
            // If inner schema was narrowed, the "not" is extended (breaking)
            boolean hasExtension = innerDiffs.stream().anyMatch(d ->
                    d.type() == TYPE_EXTENDED || d.type() == SUM_TYPE_EXTENDED
                            || d.type() == COMBINED_TYPE_EXTENDED || d.type() == ENUM_ARRAY_EXTENDED);
            boolean hasNarrowing = innerDiffs.stream().anyMatch(d ->
                    d.type() == TYPE_NARROWED || d.type() == SUM_TYPE_NARROWED
                            || d.type() == COMBINED_TYPE_NARROWED || d.type() == ENUM_ARRAY_NARROWED);
            if (hasExtension && !hasNarrowing) {
                diffs.add(new Difference(NOT_TYPE_NARROWED, path));
            } else if (hasNarrowing && !hasExtension) {
                diffs.add(new Difference(NOT_TYPE_EXTENDED, path));
            }
        }
    }

    // --- Utility methods ---

    private static boolean hasDefaultValue(Schema schema) {
        Schema resolved = resolveSchema(schema);
        return resolved.hasDefaultValue();
    }

    /**
     * Compares two optional Integer values and adds the appropriate difference type.
     * <p>
     * The "increased" and "decreased" parameters follow a consistent convention:
     * for maximum-like constraints, "increased" means the limit went up (more permissive),
     * while for minimum-like constraints, "decreased" means the limit went down (more permissive).
     * The caller is responsible for passing the correct difference types accordingly.
     *
     * @param path          the JSON path
     * @param origValue     the original value (may be null)
     * @param updValue      the updated value (may be null)
     * @param addedType     the difference type for when a constraint is added
     * @param removedType   the difference type for when a constraint is removed
     * @param increasedType the difference type for when the value increases
     * @param decreasedType the difference type for when the value decreases
     * @param diffs         the list to add differences to
     */
    private static void compareOptionalInteger(String path, Integer origValue, Integer updValue,
                                                Difference.Type addedType, Difference.Type removedType,
                                                Difference.Type increasedType, Difference.Type decreasedType,
                                                List<Difference> diffs) {
        if (Objects.equals(origValue, updValue)) {
            return;
        }
        if (origValue == null) {
            diffs.add(new Difference(addedType, path));
        } else if (updValue == null) {
            diffs.add(new Difference(removedType, path));
        } else if (updValue > origValue) {
            diffs.add(new Difference(increasedType, path));
        } else {
            diffs.add(new Difference(decreasedType, path));
        }
    }

    /**
     * Compares two optional Number values and adds the appropriate difference type.
     * Uses {@code doubleValue()} for comparison to handle different Number subclasses uniformly.
     */
    private static void compareOptionalNumber(String path, Number origValue, Number updValue,
                                               Difference.Type addedType, Difference.Type removedType,
                                               Difference.Type increasedType, Difference.Type decreasedType,
                                               List<Difference> diffs) {
        if (numbersEqual(origValue, updValue)) {
            return;
        }
        if (origValue == null) {
            diffs.add(new Difference(addedType, path));
        } else if (updValue == null) {
            diffs.add(new Difference(removedType, path));
        } else {
            int cmp = Double.compare(updValue.doubleValue(), origValue.doubleValue());
            if (cmp > 0) {
                diffs.add(new Difference(increasedType, path));
            } else {
                diffs.add(new Difference(decreasedType, path));
            }
        }
    }

    private static boolean numbersEqual(Number a, Number b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
    }
}
