# Jackson 3 Migration Plan — Remove Confluent Dependencies

## Objective
Migrate the Akces Framework to use Jackson 3 (`tools.jackson`) exclusively by removing all Confluent library dependencies (`io.confluent`) that are tied to Jackson 2 (`com.fasterxml.jackson`). In-source the JSON Schema validation and schema comparison functionality currently provided by Confluent classes into custom Jackson 3-compatible code within the framework.

## Current State

### Jackson Usage
- **Jackson 2** (`com.fasterxml.jackson`): Used in **64 files** — primary Jackson version (v2.21.1)
- **Jackson 3** (`tools.jackson`): Used in **~7 files** — partial/test-only usage

### Confluent Dependencies (8.2.0)
Three Confluent artifacts are used:
1. `kafka-json-serializer` — JSON serializer for Kafka
2. `kafka-json-schema-serializer` — JSON Schema serializer, transitively brings `kafka-json-schema-provider` which contains `JsonSchema`, `SchemaDiff`, `Difference`
3. `kafka-protobuf-serializer` — Protobuf serializer for Kafka

### Confluent Classes Used (23 files)

| Class | Package | Used In | Purpose |
|-------|---------|---------|---------|
| `JsonSchema` | `io.confluent.kafka.schemaregistry.json` | 21 files | Schema representation, validation, comparison |
| `SchemaDiff` | `io.confluent.kafka.schemaregistry.json.diff` | 3 files | Schema compatibility comparison |
| `Difference` | `io.confluent.kafka.schemaregistry.json.diff` | 3 files | Schema difference representation |
| `ParsedSchema` | `io.confluent.kafka.schemaregistry` | 1 file | Generic schema interface |

### Everit JSON Schema (transitive via Confluent)
- `org.everit.json.schema.Schema` — Used internally by Confluent's `JsonSchema.rawSchema()` and `SchemaDiff.compare()`
- `org.everit.json.schema.ValidationException` — Caught in **3 files** for validation error handling

### Specific API Surface Used

**`JsonSchema` methods used:**
| Method | Where Used | What It Does |
|--------|-----------|--------------|
| `new JsonSchema(String)` | Tests | Construct from JSON string |
| `new JsonSchema(String, List, Map, Integer)` | `SchemaRegistry`, `SchemaRecordSerde` | Construct with version |
| `new JsonSchema(JsonNode, List, Map, Integer)` | `SchemaRecordSerde` | Construct from JsonNode with version |
| `validate(JsonNode)` | `KafkaAggregateRuntime`, `AkcesClientController` | Validate data against schema |
| `rawSchema()` | `KafkaSchemaRegistry` | Get `org.everit.json.schema.Schema` for diff |
| `toJsonNode()` | `SchemaRecordSerde` | Convert to Jackson JsonNode |
| `deepEquals(JsonSchema)` | `KafkaSchemaRegistry` | Deep equality check |
| `version()` | `KafkaSchemaRegistry` | Get schema version |
| `toString()` | `KafkaSchemaRegistry` | String representation for comparison |

**`SchemaDiff` methods used:**
| Method | Where Used |
|--------|-----------|
| `SchemaDiff.compare(Schema, Schema)` | `KafkaSchemaRegistry` (4 call sites), `JsonSchemaTests`, `EventCatalogProcessorTest` |
| `SchemaDiff.COMPATIBLE_CHANGES_STRICT` | `KafkaSchemaRegistry.validateBackwardCompatibility()` |

**`Difference` types referenced:**
| Type | Where Used |
|------|-----------|
| `PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL` | `KafkaSchemaRegistry` (relaxed validation filter) |
| `REQUIRED_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL` | `KafkaSchemaRegistry` (backward compatibility filter) |

## Architecture

### Design Principle
Replace Confluent's `JsonSchema`, `SchemaDiff`, and `Difference` with framework-owned classes in the `org.elasticsoftware.akces.schemas` package. Use a Jackson 3-compatible JSON Schema validation library instead of the Everit validator (which depends on `org.json` / Jackson 2).

### New Package Structure
```
main/shared/src/main/java/org/elasticsoftware/akces/schemas/
├── JsonSchema.java                    # Replaces io.confluent.kafka.schemaregistry.json.JsonSchema
├── JsonSchemaValidationException.java # Replaces org.everit.json.schema.ValidationException
├── diff/
│   ├── Difference.java                # Replaces io.confluent...diff.Difference
│   └── SchemaDiff.java                # Replaces io.confluent...diff.SchemaDiff
├── KafkaSchemaRegistry.java           # Update imports only
├── SchemaRegistry.java                # Update imports only
├── IncompatibleSchemaException.java   # Update imports only
├── SchemaNotBackwardsCompatibleException.java  # Update imports only
└── storage/
    ├── SchemaStorage.java             # Update imports only
    └── KafkaTopicSchemaStorage.java   # Update imports only
```

### New Component: `JsonSchema`
A framework-owned JSON Schema wrapper class that:
- Stores the schema as a Jackson 3 `tools.jackson.databind.JsonNode` internally
- Uses a Jackson 3-compatible JSON Schema validator for `validate()` (see [JSON Schema Validator Selection](#json-schema-validator-selection))
- Provides `rawSchema()` returning a parsed schema object for `SchemaDiff` to consume
- Implements `deepEquals()`, `version()`, `toString()`, `toJsonNode()`

```java
package org.elasticsoftware.akces.schemas;

import tools.jackson.databind.JsonNode;

public final class JsonSchema {
    private final JsonNode schemaNode;
    private final Integer version;
    
    // Constructors
    public JsonSchema(String schemaJson);
    public JsonSchema(String schemaJson, Integer version);
    public JsonSchema(JsonNode schemaNode, Integer version);
    
    // Validation
    public JsonNode validate(JsonNode data) throws JsonSchemaValidationException;
    
    // Schema access
    public JsonNode toJsonNode();
    public Object rawSchema();  // Returns parsed schema for SchemaDiff
    public Integer version();
    
    // Comparison
    public boolean deepEquals(JsonSchema other);
    
    @Override
    public String toString();
    
    @Override
    public boolean equals(Object o);
    
    @Override
    public int hashCode();
}
```

### New Component: `JsonSchemaValidationException`
Replaces `org.everit.json.schema.ValidationException`:

```java
package org.elasticsoftware.akces.schemas;

public class JsonSchemaValidationException extends Exception {
    private final String schemaLocation;
    private final String pointerToViolation;
    
    public JsonSchemaValidationException(String message);
    public JsonSchemaValidationException(String message, Throwable cause);
    // ... getters
}
```

### New Component: `Difference`
Replaces `io.confluent.kafka.schemaregistry.json.diff.Difference`:

```java
package org.elasticsoftware.akces.schemas.diff;

public record Difference(Type type, String jsonPath) {
    public enum Type {
        // All 114 values from Confluent's Difference.Type
        // Key ones used in the framework:
        PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL,
        REQUIRED_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL,
        OPTIONAL_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL,
        // ... all others for completeness
    }
    
    public Type getType() { return type; }
    public String getJsonPath() { return jsonPath; }
}
```

### New Component: `SchemaDiff`
Replaces `io.confluent.kafka.schemaregistry.json.diff.SchemaDiff`:

```java
package org.elasticsoftware.akces.schemas.diff;

import java.util.List;
import java.util.Set;

public final class SchemaDiff {
    public static final Set<Difference.Type> COMPATIBLE_CHANGES_STRICT = Set.of(
        // Same 56 types as Confluent's COMPATIBLE_CHANGES_STRICT
    );
    
    public static List<Difference> compare(Object originalSchema, Object updatedSchema);
}
```

The `compare()` method needs to:
1. Walk both JSON schema trees recursively
2. Compare properties, types, constraints
3. Detect additions, removals, and modifications
4. Categorize each difference into the appropriate `Difference.Type`

This is the most complex part of the in-sourcing effort.

### JSON Schema Validator Selection

Since `org.everit.json.schema` depends on `org.json` (not Jackson), and we want Jackson 3 only, we need a compatible validator. Options:

**Option A: `com.networknt:json-schema-validator` (Recommended)**
- Pure Java, works directly with Jackson's `JsonNode`
- Supports JSON Schema Draft-07 (which is what the framework uses)
- Version 1.5.x+ supports Jackson 3 via `tools.jackson`
- Active maintenance, widely used
- License: Apache 2.0

**Option B: `com.github.erosb:json-sKema`**
- Modern JSON Schema validator
- Supports Draft 2020-12 and Draft 2019-09
- Already a transitive dependency of Confluent's code
- Would need to verify Jackson 3 compatibility

**Option C: Custom Validation Using Jackson 3**
- Write minimal validation logic directly against `tools.jackson.databind.JsonNode`
- For Draft-07, the validation rules are well-defined
- Highest effort but zero external dependencies
- Only need to validate: types, required properties, patterns, string constraints, numeric constraints, enum values, additionalProperties

**Recommendation**: **Option A** (`com.networknt:json-schema-validator`). It has the best Jackson integration, supports Draft-07, and is well-maintained. Check for Jackson 3 compatibility before committing. If not yet available, consider Option C for initial implementation.

### Schema Comparison Strategy for `SchemaDiff`

The `SchemaDiff.compare()` operates on parsed schema objects (currently `org.everit.json.schema.Schema`). Two approaches:

**Approach 1: JSON Tree Comparison (Recommended)**
- Work directly with `tools.jackson.databind.JsonNode` trees
- Walk both schema trees recursively
- Compare `properties`, `required`, `type`, `additionalProperties`, `items`, `allOf`/`anyOf`/`oneOf`, and constraint keywords
- Generate `Difference` records for each detected change
- Advantages: No additional dependency needed, works natively with Jackson 3

**Approach 2: Schema Model Comparison**
- Parse schemas into a structural model first, then compare models
- More complex but more accurate
- Higher development effort

**Recommendation**: **Approach 1** — JSON Tree Comparison. The framework uses Draft-07 schemas generated from Java classes via VicTools, which produces well-structured, normalized schema JSON. A tree-based comparison is sufficient for detecting the difference types actually used (property changes, required changes, type changes, constraint changes).

## Implementation Phases

### Phase 0: Pre-Verification (0.5 day)

- [ ] Verify Jackson 3-compatible JSON Schema validator availability:
  - Check `com.networknt:json-schema-validator` for Jackson 3 (`tools.jackson`) support
  - If not available, plan for custom validation implementation (Option C)
  - This determines the approach for `JsonSchema.validate()` in Phase 1.2
- [ ] Verify `jackson-dataformat-protobuf` has a Jackson 3 version available (needed for `SchemaRecordSerde`)
- [ ] Verify `kafka-protobuf-serializer` is not used at runtime:
  - Run `mvn dependency:tree` to check what depends on it
  - Search for any runtime class loading or service provider configuration
  - If it provides Kafka serializers used via configuration (not Java imports), identify and replace

### Phase 1: Create In-Sourced Schema Classes (3-4 days)

**1.1 — Create `Difference` and `SchemaDiff` classes**
- [ ] Create `org.elasticsoftware.akces.schemas.diff.Difference` record with all `Type` enum values (all 114 values from Confluent's enum for completeness, even though only 2 are directly referenced in framework code — the full set is needed because `SchemaDiff.compare()` can return any type, and `COMPATIBLE_CHANGES_STRICT` contains 56 of them)
- [ ] Create `org.elasticsoftware.akces.schemas.diff.SchemaDiff` with `compare()` and `COMPATIBLE_CHANGES_STRICT`
- [ ] Implement JSON tree-based comparison for:
  - Object schema: properties added/removed, additionalProperties changes
  - Required attributes: added/removed
  - Type changes: extended/narrowed/changed
  - String constraints: minLength, maxLength, pattern
  - Numeric constraints: minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf
  - Array constraints: minItems, maxItems, uniqueItems, additionalItems
  - Enum changes: extended/narrowed
  - Combined schemas: allOf/anyOf/oneOf changes
- [ ] Unit tests for SchemaDiff covering all implemented difference types
- [ ] Verify `COMPATIBLE_CHANGES_STRICT` matches Confluent's definition (56 types)

**1.2 — Create `JsonSchema` wrapper class**
- [ ] Create `org.elasticsoftware.akces.schemas.JsonSchema` class
- [ ] Implement constructors: `JsonSchema(String)`, `JsonSchema(String, Integer)`, `JsonSchema(JsonNode, Integer)`
- [ ] Implement `toJsonNode()` returning `tools.jackson.databind.JsonNode`
- [ ] Implement `validate(JsonNode)` using chosen validator (Option A or C)
- [ ] Implement `rawSchema()` returning appropriate object for SchemaDiff
- [ ] Implement `deepEquals()` using Jackson node comparison
- [ ] Implement `version()`, `toString()`, `equals()`, `hashCode()`
- [ ] Unit tests for JsonSchema

**1.3 — Create `JsonSchemaValidationException`**
- [ ] Create exception class replacing `org.everit.json.schema.ValidationException`
- [ ] Include meaningful error messages with schema location and violation pointer

### Phase 2: Update Shared Module (1-2 days)

- [ ] Update `SchemaRegistry.java` — replace Confluent `JsonSchema` with new `JsonSchema`
- [ ] Update `KafkaSchemaRegistry.java` — replace all Confluent imports
  - Replace `SchemaDiff.compare(registeredSchema.rawSchema(), localSchema.rawSchema())` with new SchemaDiff
  - Replace `Difference.Type` references
  - Replace `SchemaDiff.COMPATIBLE_CHANGES_STRICT`
  - Replace `JsonSchema.deepEquals()`
- [ ] Update `SchemaStorage.java` — replace Confluent `JsonSchema` with new type
- [ ] Update `KafkaTopicSchemaStorage.java` — replace Confluent `JsonSchema` with new type
- [ ] Update `SchemaRecord.java` — replace Confluent `JsonSchema` with new type
- [ ] Update `SchemaRecordSerde.java` — replace Confluent `JsonSchema` constructors
  - Update `SchemaRecordDTO.from()` to use new `JsonSchema.toJsonNode()`
  - Update `toSchemaRecord()` to use new `JsonSchema(JsonNode, Integer)` constructor
- [ ] Update `IncompatibleSchemaException.java` — replace Confluent `Difference` import
- [ ] Update `SchemaNotBackwardsCompatibleException.java` — replace Confluent `Difference` import
- [ ] Remove `kafka-json-serializer` and `kafka-json-schema-serializer` dependencies from `main/shared/pom.xml`
- [ ] Remove `joda-time` dependency (only needed by Confluent)
- [ ] Run `mvn compile -pl main/shared` to verify compilation

### Phase 3: Update Runtime Module (1 day)

- [ ] Update `KafkaAggregateRuntime.java`:
  - Replace `io.confluent.kafka.schemaregistry.json.JsonSchema` → `org.elasticsoftware.akces.schemas.JsonSchema`
  - Replace `org.everit.json.schema.ValidationException` → `org.elasticsoftware.akces.schemas.JsonSchemaValidationException`
  - Update `domainEventSchemas` and `commandSchemas` map types
  - Update `serialize(DomainEvent)` and `serialize(Command)` catch blocks
- [ ] Remove `kafka-json-serializer`, `kafka-json-schema-serializer`, `kafka-protobuf-serializer` from `main/runtime/pom.xml`
- [ ] Remove `joda-time` dependency
- [ ] Update test files:
  - `TestUtils.java` — replace Confluent imports
  - `JsonSchemaTests.java` — replace Confluent `JsonSchema`, `SchemaDiff`, `Difference` imports
  - `RuntimeTests.java` — replace Confluent imports  
  - `WalletConfiguration.java` — replace Confluent imports
  - `AccountConfiguration.java` — replace Confluent imports
  - `WalletTests.java` — replace Confluent imports
- [ ] Run `mvn compile -pl main/runtime` to verify compilation

### Phase 4: Update Client Module (1 day)

- [ ] Update `AkcesClientController.java`:
  - Replace `io.confluent.kafka.schemaregistry.ParsedSchema` — remove usage (use `JsonSchema` directly)
  - Replace `io.confluent.kafka.schemaregistry.json.JsonSchema` → `org.elasticsoftware.akces.schemas.JsonSchema`
  - Replace `org.everit.json.schema.ValidationException` → `org.elasticsoftware.akces.schemas.JsonSchemaValidationException`
  - Simplify `commandSchemas`, `domainEventSchemas`, `commandSchemasLookup` maps to use `JsonSchema` directly (remove `ParsedSchema` interface)
  - Remove `instanceof JsonSchema` check in `serialize()` since we always use `JsonSchema`
- [ ] Remove `kafka-json-serializer`, `kafka-json-schema-serializer`, `kafka-protobuf-serializer` from `main/client/pom.xml`
- [ ] Remove `joda-time` dependency
- [ ] Update `AkcesClientTests.java` — replace Confluent imports
- [ ] Run `mvn compile -pl main/client` to verify compilation

### Phase 5: Update Query-Support and EventCatalog Modules (0.5 day)

- [ ] Update `main/query-support` test files (`TestUtils.java`)
- [ ] Update `main/eventcatalog` test files (`EventCatalogProcessorTest.java`)
  - Replace `SchemaDiff.compare()`, `JsonSchema`, and `Difference` imports
- [ ] Remove any Confluent dependencies from these module POMs (if present)
- [ ] Run `mvn compile` for these modules

### Phase 6: Update Test Applications (0.5 day)

- [ ] Update `test-apps/crypto-trading/aggregates/src/test/java/.../TestUtils.java`
- [ ] Update `test-apps/crypto-trading/commands/src/test/java/.../TestUtils.java`
- [ ] Update `test-apps/crypto-trading/queries/src/test/java/.../TestUtils.java`
- [ ] Remove Confluent dependencies from test-app POMs
- [ ] Run `mvn compile` for test-apps

### Phase 7: Remove Confluent Dependencies from Parent POM (0.5 day)

- [ ] Remove from `main/pom.xml` dependencyManagement:
  - `io.confluent:kafka-json-serializer`
  - `io.confluent:kafka-json-schema-serializer`
  - `io.confluent:kafka-protobuf-serializer`
  - `joda-time:joda-time`
- [ ] Remove `confluent.version` and `joda-time.version` properties
- [ ] Remove Confluent Maven repository from `<repositories>` section
- [ ] Update `bom/pom.xml` if it references Confluent
- [ ] Verify no remaining references to `io.confluent` in any POM or Java file
- [ ] Full build: `mvn clean compile`

### Phase 8: Jackson 2 → Jackson 3 Migration (Separate, Larger Effort)

> **Note**: This phase covers the broader Jackson 2 → 3 migration which is a separate, much larger effort. The phases above focus specifically on removing Confluent dependencies, which is the blocker for the Jackson 3 migration.

- [ ] Replace `com.fasterxml.jackson` imports with `tools.jackson` across all 64 files
- [ ] Update `ObjectMapper` → Jackson 3 equivalent
- [ ] Update custom serializers/deserializers (GDPR module, BigDecimal, etc.)
- [ ] Update `jackson-dataformat-protobuf` to Jackson 3 version
- [ ] Update `jackson-bom` import to Jackson 3 BOM
- [ ] Update Spring Boot Jackson auto-configuration
- [ ] Full test suite pass

## Technical Decisions

### Why In-Source Instead of Finding a Jackson 3-Compatible Alternative?
Confluent's schema registry client is a large library with many transitive dependencies. The framework uses only a tiny fraction of its API:
- **3 classes** (`JsonSchema`, `SchemaDiff`, `Difference`)
- **~10 methods** total
- The `SchemaDiff` comparison logic, while complex in the general case, is used with Draft-07 schemas generated from Java records, which are structurally simple (flat objects, no `$ref`, no complex compositions)

In-sourcing gives full control over the implementation, removes a large dependency tree, and eliminates the Jackson 2 dependency.

### Why Not Use Confluent's Source Code Directly?
Confluent's source code is licensed under the Confluent Community License (CCL), which is not Apache 2.0 compatible. We must write our own implementation inspired by the same JSON Schema specification, not copy Confluent's code.

### Schema Validation Without Everit
The `org.everit.json.schema` library depends on `org.json` (not Jackson). Options:
1. **`com.networknt:json-schema-validator`** — Works with Jackson `JsonNode`, supports Draft-07. Check for Jackson 3 support.
2. **Custom implementation** — Since the framework generates simple schemas (flat records with basic types), a custom validator could handle the needed cases.

### `ParsedSchema` Interface Removal
The `AkcesClientController` uses `ParsedSchema` as a generic interface, then checks `instanceof JsonSchema`. Since the framework only supports JSON Schema (not Avro or Protobuf schemas), we can simplify this to use `JsonSchema` directly, removing the need for the `ParsedSchema` abstraction.

### `kafka-protobuf-serializer` Dependency
This Confluent dependency is listed in `main/runtime/pom.xml` and `main/client/pom.xml` but is **not directly imported in any Java source file**. Investigation in Phase 0 should verify whether it provides Kafka serializer/deserializer classes loaded via configuration (e.g., through `kafka.serializer` properties) or service provider mechanisms. If it is only a transitive dependency and not actively used, it can be safely removed. If it provides Kafka serializers used at runtime via configuration, alternative serializers must be configured before removal.

## Files to Change

### Shared Module (`main/shared`)
| File | Change |
|------|--------|
| `pom.xml` | Remove Confluent dependencies |
| `schemas/SchemaRegistry.java` | Replace `io.confluent.*.JsonSchema` with new `JsonSchema` |
| `schemas/KafkaSchemaRegistry.java` | Replace all Confluent imports (JsonSchema, SchemaDiff, Difference) |
| `schemas/IncompatibleSchemaException.java` | Replace `Difference` import |
| `schemas/SchemaNotBackwardsCompatibleException.java` | Replace `Difference` import |
| `schemas/storage/SchemaStorage.java` | Replace `JsonSchema` import |
| `schemas/storage/KafkaTopicSchemaStorage.java` | Replace `JsonSchema` import |
| `protocol/SchemaRecord.java` | Replace `JsonSchema` import |
| `serialization/SchemaRecordSerde.java` | Replace `JsonSchema` import, update constructors |
| **NEW** `schemas/JsonSchema.java` | New class |
| **NEW** `schemas/JsonSchemaValidationException.java` | New exception class |
| **NEW** `schemas/diff/Difference.java` | New record |
| **NEW** `schemas/diff/SchemaDiff.java` | New class |

### Runtime Module (`main/runtime`)
| File | Change |
|------|--------|
| `pom.xml` | Remove Confluent dependencies |
| `kafka/KafkaAggregateRuntime.java` | Replace imports |
| `test/.../TestUtils.java` | Replace imports |
| `test/.../JsonSchemaTests.java` | Replace imports |
| `test/.../RuntimeTests.java` | Replace imports |
| `test/.../WalletConfiguration.java` | Replace imports |
| `test/.../AccountConfiguration.java` | Replace imports |
| `test/.../WalletTests.java` | Replace imports |

### Client Module (`main/client`)
| File | Change |
|------|--------|
| `pom.xml` | Remove Confluent dependencies |
| `client/AkcesClientController.java` | Replace imports, simplify types |
| `test/.../AkcesClientTests.java` | Replace imports |

### Other Modules
| File | Change |
|------|--------|
| `main/query-support/test/.../TestUtils.java` | Replace imports |
| `main/eventcatalog/test/.../EventCatalogProcessorTest.java` | Replace imports |
| `test-apps/crypto-trading/*/test/.../TestUtils.java` (3 files) | Replace imports |
| `main/pom.xml` | Remove Confluent dependency management, repository |
| `bom/pom.xml` | Remove Confluent references if any |

## `COMPATIBLE_CHANGES_STRICT` Reference

The following 56 `Difference.Type` values constitute `COMPATIBLE_CHANGES_STRICT` (extracted from Confluent's `SchemaDiff` static initializer):

```
ID_CHANGED, DESCRIPTION_CHANGED, TITLE_CHANGED, DEFAULT_CHANGED, 
SCHEMA_REMOVED, TYPE_EXTENDED, MAX_LENGTH_INCREASED, MAX_LENGTH_REMOVED, 
MIN_LENGTH_DECREASED, MIN_LENGTH_REMOVED, PATTERN_REMOVED, 
MAXIMUM_INCREASED, MAXIMUM_REMOVED, MINIMUM_DECREASED, MINIMUM_REMOVED, 
EXCLUSIVE_MAXIMUM_INCREASED, EXCLUSIVE_MAXIMUM_REMOVED, 
EXCLUSIVE_MINIMUM_DECREASED, EXCLUSIVE_MINIMUM_REMOVED, 
MULTIPLE_OF_REDUCED, MULTIPLE_OF_REMOVED, 
REQUIRED_ATTRIBUTE_WITH_DEFAULT_ADDED, REQUIRED_ATTRIBUTE_REMOVED, 
DEPENDENCY_ARRAY_NARROWED, DEPENDENCY_ARRAY_REMOVED, DEPENDENCY_SCHEMA_REMOVED, 
MAX_PROPERTIES_INCREASED, MAX_PROPERTIES_REMOVED, 
MIN_PROPERTIES_DECREASED, MIN_PROPERTIES_REMOVED, 
ADDITIONAL_PROPERTIES_ADDED, ADDITIONAL_PROPERTIES_EXTENDED, 
PROPERTY_WITH_EMPTY_SCHEMA_ADDED_TO_OPEN_CONTENT_MODEL, 
REQUIRED_PROPERTY_WITH_DEFAULT_ADDED_TO_UNOPEN_CONTENT_MODEL, 
OPTIONAL_PROPERTY_ADDED_TO_UNOPEN_CONTENT_MODEL, 
PROPERTY_WITH_FALSE_REMOVED_FROM_CLOSED_CONTENT_MODEL, 
PROPERTY_REMOVED_FROM_OPEN_CONTENT_MODEL, 
PROPERTY_ADDED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL, 
PROPERTY_REMOVED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL, 
MAX_ITEMS_INCREASED, MAX_ITEMS_REMOVED, MIN_ITEMS_DECREASED, MIN_ITEMS_REMOVED, 
UNIQUE_ITEMS_REMOVED, ADDITIONAL_ITEMS_ADDED, ADDITIONAL_ITEMS_EXTENDED, 
ITEM_WITH_EMPTY_SCHEMA_ADDED_TO_OPEN_CONTENT_MODEL, 
ITEM_ADDED_TO_CLOSED_CONTENT_MODEL, 
ITEM_WITH_FALSE_REMOVED_FROM_CLOSED_CONTENT_MODEL, 
ITEM_REMOVED_FROM_OPEN_CONTENT_MODEL, 
ITEM_ADDED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL, 
ITEM_REMOVED_IS_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL, 
ENUM_ARRAY_EXTENDED, COMBINED_TYPE_EXTENDED, 
PRODUCT_TYPE_NARROWED, SUM_TYPE_EXTENDED, NOT_TYPE_NARROWED
```

And the additional types added for `COMPATIBLE_CHANGES_LENIENT` (superset of STRICT):
```
ADDITIONAL_PROPERTIES_NARROWED, ADDITIONAL_PROPERTIES_REMOVED,
PROPERTY_ADDED_TO_OPEN_CONTENT_MODEL, PROPERTY_REMOVED_FROM_OPEN_CONTENT_MODEL,
PROPERTY_ADDED_NOT_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL,
PROPERTY_REMOVED_NOT_COVERED_BY_PARTIALLY_OPEN_CONTENT_MODEL
```

## Impact

**Dependency Reduction**: Removes ~15-20 transitive JARs from the Confluent dependency tree
**Breaking Changes**: None for framework users (all changes are internal implementation)
**API Surface**: `SchemaRegistry`, `SchemaRecord`, `SchemaStorage` interfaces change their `JsonSchema` type from `io.confluent` to `org.elasticsoftware.akces.schemas`
**Timeline**: 7-10 days for Phases 1-7 (Confluent removal), additional time for Phase 8 (full Jackson 3 migration)

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| SchemaDiff comparison misses edge cases | High | Comprehensive unit tests, compare results with Confluent's implementation for known schemas |
| JSON Schema validation behavior differs | Medium | Test validation against same schemas and data used in existing tests |
| Jackson 3-compatible validator not available | Medium | Fall back to custom validation (Option C) or use a bridge approach |
| Transitive dependency on Jackson 2 via other libs | Low | Run `mvn dependency:tree` to verify no Jackson 2 remains |
| `kafka-protobuf-serializer` removal breaks functionality | Medium | Verify no runtime usage, check if it's needed for Protobuf schema loading |

## Success Criteria

- [ ] Zero imports from `io.confluent` in any Java file
- [ ] Zero imports from `org.everit` in any Java file  
- [ ] No Confluent dependencies in any `pom.xml`
- [ ] No Confluent Maven repository reference
- [ ] All existing unit tests pass
- [ ] All existing integration tests pass
- [ ] Schema validation produces same results as before
- [ ] Schema compatibility checking produces same results as before
- [ ] `mvn clean compile` succeeds for entire project
- [ ] `mvn test` succeeds for entire project
- [ ] No `com.fasterxml.jackson` imports remain (Phase 8 only)
