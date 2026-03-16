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

### Everit JSON Schema (transitive via Confluent, to be retained)
- `org.everit.json.schema.Schema` — Used internally by Confluent's `JsonSchema.rawSchema()` and `SchemaDiff.compare()`
- `org.everit.json.schema.ValidationException` — Caught in **3 files** for validation error handling
- **Key insight**: Everit (`com.github.erosb:everit-json-schema:1.14.6`) depends on `org.json:json`, **not** on Jackson. It has no Jackson 2 dependency, so it can be kept as-is during the Jackson 3 migration. It currently enters the dependency tree transitively via `kafka-json-schema-provider`; after removing Confluent, it should be added as an explicit dependency.

### Spring Boot Jackson 2 Usage
The framework currently uses Spring Boot 4's **legacy Jackson 2 support** module (`spring-boot-jackson2`):
- **3 POM files** depend on `spring-boot-jackson2`: `main/runtime`, `main/client`, `main/query-support`
- **3 auto-configuration classes** use `Jackson2ObjectMapperBuilderCustomizer` from `spring.boot.jackson2.autoconfigure`:
  - `AggregateServiceApplication.java`
  - `AkcesClientAutoConfiguration.java`
  - `AkcesQueryModelAutoConfiguration.java` / `AkcesDatabaseModelAutoConfiguration.java`
- **8+ test files** use `Jackson2ObjectMapperBuilder` from `spring.http.converter.json`
- Spring Boot 4 / Spring Framework 7 default to Jackson 3 via `spring-boot-jackson` module. The Jackson 2 module (`spring-boot-jackson2`) is a backward-compatibility shim that should be replaced.

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
- Stores the schema as a `com.fasterxml.jackson.databind.JsonNode` internally (Jackson 2 for now, migrated to `tools.jackson.databind.JsonNode` in Phase 8)
- Uses `org.everit.json.schema` for `validate()` (Everit has no Jackson dependency, so it's safe to keep)
- Provides `rawSchema()` returning `org.everit.json.schema.Schema` for `SchemaDiff` to consume
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
    
    // Validation (delegates to org.everit.json.schema internally)
    public JsonNode validate(JsonNode data) throws org.everit.json.schema.ValidationException;
    
    // Schema access
    public JsonNode toJsonNode();
    public org.everit.json.schema.Schema rawSchema();  // Returns Everit Schema for SchemaDiff
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

### Everit Validation (Retained)
The `org.everit.json.schema.ValidationException` will continue to be used as-is. No replacement exception class is needed since Everit does not depend on Jackson and is safe to keep.

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
    
    public static List<Difference> compare(org.everit.json.schema.Schema originalSchema, org.everit.json.schema.Schema updatedSchema);
}
```

The `compare()` method needs to:
1. Walk both JSON schema trees recursively
2. Compare properties, types, constraints
3. Detect additions, removals, and modifications
4. Categorize each difference into the appropriate `Difference.Type`

This is the most complex part of the in-sourcing effort.

### JSON Schema Validation: Keep Everit

The `org.everit.json.schema` library (`com.github.erosb:everit-json-schema:1.14.6`) depends only on `org.json:json`, `com.damnhandy:handy-uri-templates`, and `com.google.re2j:re2j` — **none of which are Jackson-related**. It is therefore safe to retain as the JSON Schema validation library.

Currently it enters the dependency tree transitively via Confluent's `kafka-json-schema-provider`. After removing the Confluent dependencies, `everit-json-schema` must be added as an **explicit dependency** in the shared module's POM.

The `org.everit.json.schema.ValidationException` will continue to be caught in `KafkaAggregateRuntime.java` and `AkcesClientController.java` without any changes.

### Schema Comparison Strategy for `SchemaDiff`

The `SchemaDiff.compare()` operates on `org.everit.json.schema.Schema` objects (same as Confluent's implementation). Since we are retaining Everit, the `SchemaDiff` implementation can work directly with Everit's typed schema model:

- `org.everit.json.schema.ObjectSchema` — for property/additionalProperties comparison
- `org.everit.json.schema.StringSchema` — for minLength/maxLength/pattern
- `org.everit.json.schema.NumberSchema` — for minimum/maximum/multipleOf
- `org.everit.json.schema.ArraySchema` — for items/minItems/maxItems
- `org.everit.json.schema.EnumSchema` — for enum values
- `org.everit.json.schema.CombinedSchema` — for allOf/anyOf/oneOf
- `org.everit.json.schema.NotSchema` — for not

This is the same approach Confluent uses internally. Walking the typed Everit schema tree is more accurate than comparing raw JSON nodes.

## Implementation Phases

### Phase 0: Pre-Verification (0.5 day)

- [ ] Verify `jackson-dataformat-protobuf` has a Jackson 3 version available (needed for `SchemaRecordSerde`)
- [ ] Verify `kafka-protobuf-serializer` is not used at runtime:
  - Run `mvn dependency:tree` to check what depends on it
  - Search for any runtime class loading or service provider configuration
  - If it provides Kafka serializers used via configuration (not Java imports), identify and replace
- [ ] Verify `com.github.erosb:everit-json-schema:1.14.6` works correctly as an explicit (non-transitive) dependency

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
- [ ] Implement `toJsonNode()` returning Jackson `JsonNode`
- [ ] Implement `validate(JsonNode)` delegating to `org.everit.json.schema.Schema.validate()` internally
- [ ] Implement `rawSchema()` returning `org.everit.json.schema.Schema` for SchemaDiff
- [ ] Implement `deepEquals()` using JSON node comparison
- [ ] Implement `version()`, `toString()`, `equals()`, `hashCode()`
- [ ] Unit tests for JsonSchema
- [ ] Add `com.github.erosb:everit-json-schema` as explicit dependency in `main/shared/pom.xml`

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
- [ ] Add `com.github.erosb:everit-json-schema` as explicit dependency (it was previously transitive via Confluent)
- [ ] Remove `joda-time` dependency (only needed by Confluent)
- [ ] Run `mvn compile -pl main/shared` to verify compilation

### Phase 3: Update Runtime Module (1 day)

- [ ] Update `KafkaAggregateRuntime.java`:
  - Replace `io.confluent.kafka.schemaregistry.json.JsonSchema` → `org.elasticsoftware.akces.schemas.JsonSchema`
  - Keep `org.everit.json.schema.ValidationException` as-is (Everit is retained)
  - Update `domainEventSchemas` and `commandSchemas` map types
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
  - Keep `org.everit.json.schema.ValidationException` as-is (Everit is retained)
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

### Phase 8: Jackson 2 → Jackson 3 Migration Including Spring Boot/Framework (Separate, Larger Effort)

> **Note**: This phase covers the broader Jackson 2 → 3 migration which is a separate, much larger effort. The phases above focus specifically on removing Confluent dependencies, which is the blocker for the Jackson 3 migration. Spring Boot 4 and Spring Framework 7 default to Jackson 3 (`tools.jackson`), so this migration aligns the framework with the platform defaults.

**8.1 — Core Jackson Migration**
- [ ] Replace `com.fasterxml.jackson` imports with `tools.jackson` across all 64 files
- [ ] Update `ObjectMapper` → `tools.jackson.databind.json.JsonMapper` (Jackson 3 equivalent)
- [ ] Update `JsonNode` / `ObjectNode` / `ArrayNode` imports to `tools.jackson.databind.node.*`
- [ ] Update `JsonProcessingException` → `tools.jackson.core.JacksonException`
- [ ] Update custom serializers/deserializers (GDPR module, BigDecimal, etc.) to extend Jackson 3 base classes
- [ ] Update `jackson-dataformat-protobuf` to Jackson 3 version (`tools.jackson.dataformat.protobuf`)
- [ ] Replace `jackson-bom` (`com.fasterxml.jackson:jackson-bom`) with Jackson 3 BOM (`tools.jackson:jackson-bom`)

**8.2 — Spring Boot 4 / Spring Framework 7 Jackson 3 Integration**

Spring Boot 4 provides two Jackson modules: `spring-boot-jackson` (Jackson 3, the default) and `spring-boot-jackson2` (legacy backward-compatibility). The framework should migrate to the Jackson 3 module.

**POM dependency changes (3 modules):**
- [ ] In `main/runtime/pom.xml`: Replace `spring-boot-jackson2` → `spring-boot-jackson`
- [ ] In `main/client/pom.xml`: Replace `spring-boot-jackson2` → `spring-boot-jackson`
- [ ] In `main/query-support/pom.xml`: Replace `spring-boot-jackson2` → `spring-boot-jackson`

**Auto-configuration classes — replace Jackson 2 customizer with Jackson 3 equivalents:**

| Jackson 2 (current) | Jackson 3 (target) |
|---------------------|-------------------|
| `org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer` | `org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer` |
| `Jackson2ObjectMapperBuilder` | `JsonMapper.builder()` or Spring's Jackson 3 builder |
| `Jackson2AutoConfiguration` | `JacksonAutoConfiguration` |
| `spring.jackson2.*` properties | `spring.jackson.*` properties |

- [ ] `main/runtime/src/main/java/.../AggregateServiceApplication.java` — Replace `Jackson2ObjectMapperBuilderCustomizer` with `JsonMapperBuilderCustomizer`
- [ ] `main/client/src/main/java/.../AkcesClientAutoConfiguration.java` — Replace `Jackson2ObjectMapperBuilderCustomizer` with `JsonMapperBuilderCustomizer`
- [ ] `main/query-support/src/main/java/.../AkcesQueryModelAutoConfiguration.java` — Replace `Jackson2ObjectMapperBuilderCustomizer` with `JsonMapperBuilderCustomizer`
- [ ] `main/query-support/src/main/java/.../AkcesDatabaseModelAutoConfiguration.java` — Replace `Jackson2ObjectMapperBuilderCustomizer` with `JsonMapperBuilderCustomizer`

**Test files — replace Jackson2ObjectMapperBuilder usage:**
- [ ] `main/runtime/src/test/.../TestUtils.java` — Replace `Jackson2ObjectMapperBuilder` with Jackson 3 builder
- [ ] `main/runtime/src/test/.../JsonSchemaTests.java` — Replace `Jackson2ObjectMapperBuilder`
- [ ] `main/runtime/src/test/.../RuntimeTests.java` — Replace `Jackson2ObjectMapperBuilder`
- [ ] `main/runtime/src/test/.../WalletConfiguration.java` — Replace `Jackson2ObjectMapperBuilderCustomizer`
- [ ] `main/runtime/src/test/.../AccountConfiguration.java` — Replace `Jackson2ObjectMapperBuilderCustomizer`
- [ ] `main/client/src/test/.../AkcesClientTests.java` — Replace `Jackson2ObjectMapperBuilder`
- [ ] `main/eventcatalog/src/test/.../EventCatalogProcessorTest.java` — Replace `Jackson2ObjectMapperBuilder`
- [ ] `test-apps/crypto-trading/commands/src/test/.../TestUtils.java` — Replace `Jackson2ObjectMapperBuilder`
- [ ] `test-apps/crypto-trading/queries/src/test/.../TestUtils.java` — Replace `Jackson2ObjectMapperBuilder`
- [ ] `test-apps/crypto-trading/aggregates/src/test/.../TestUtils.java` — Replace `Jackson2ObjectMapperBuilder`

**8.3 — Remove Jackson 2 Dependencies**
- [ ] Remove `spring-boot-jackson2` dependency from all POMs
- [ ] Remove `com.fasterxml.jackson:jackson-bom` from `main/pom.xml` dependencyManagement
- [ ] Verify no transitive Jackson 2 dependencies remain: `mvn dependency:tree -Dincludes=com.fasterxml.jackson*`
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

### Schema Validation: Keeping Everit
The `org.everit.json.schema` library depends on `org.json:json` (not Jackson). It has **zero Jackson dependency** and is therefore fully compatible with both Jackson 2 and Jackson 3 codebases. After removing Confluent, Everit must be added as an explicit dependency. This avoids the need to find or build a Jackson 3-compatible JSON Schema validator, significantly reducing migration effort and risk.

### Spring Boot 4 Jackson 3 Integration
Spring Boot 4 ships with two Jackson modules:
- `spring-boot-jackson` — **Jackson 3** (the default, recommended)
- `spring-boot-jackson2` — **Jackson 2** (legacy backward-compatibility shim)

The framework currently uses `spring-boot-jackson2` and its `Jackson2ObjectMapperBuilderCustomizer`. Phase 8 replaces these with `spring-boot-jackson` and `JsonMapperBuilderCustomizer`. This is the natural path for Spring Boot 4 / Spring Framework 7 applications.

### `ParsedSchema` Interface Removal
The `AkcesClientController` uses `ParsedSchema` as a generic interface, then checks `instanceof JsonSchema`. Since the framework only supports JSON Schema (not Avro or Protobuf schemas), we can simplify this to use `JsonSchema` directly, removing the need for the `ParsedSchema` abstraction.

### `kafka-protobuf-serializer` Dependency
This Confluent dependency is listed in `main/runtime/pom.xml` and `main/client/pom.xml` but is **not directly imported in any Java source file**. Investigation in Phase 0 should verify whether it provides Kafka serializer/deserializer classes loaded via configuration (e.g., through `kafka.serializer` properties) or service provider mechanisms. If it is only a transitive dependency and not actively used, it can be safely removed. If it provides Kafka serializers used at runtime via configuration, alternative serializers must be configured before removal.

## Files to Change

### Shared Module (`main/shared`)
| File | Change |
|------|--------|
| `pom.xml` | Remove Confluent dependencies, add `everit-json-schema` as explicit dependency |
| `schemas/SchemaRegistry.java` | Replace `io.confluent.*.JsonSchema` with new `JsonSchema` |
| `schemas/KafkaSchemaRegistry.java` | Replace all Confluent imports (JsonSchema, SchemaDiff, Difference) |
| `schemas/IncompatibleSchemaException.java` | Replace `Difference` import |
| `schemas/SchemaNotBackwardsCompatibleException.java` | Replace `Difference` import |
| `schemas/storage/SchemaStorage.java` | Replace `JsonSchema` import |
| `schemas/storage/KafkaTopicSchemaStorage.java` | Replace `JsonSchema` import |
| `protocol/SchemaRecord.java` | Replace `JsonSchema` import |
| `serialization/SchemaRecordSerde.java` | Replace `JsonSchema` import, update constructors |
| **NEW** `schemas/JsonSchema.java` | New class (wrapper using Everit internally) |
| **NEW** `schemas/diff/Difference.java` | New record |
| **NEW** `schemas/diff/SchemaDiff.java` | New class (operates on Everit Schema objects) |

### Runtime Module (`main/runtime`)
| File | Change |
|------|--------|
| `pom.xml` | Remove Confluent dependencies |
| `kafka/KafkaAggregateRuntime.java` | Replace Confluent `JsonSchema` import; keep Everit `ValidationException` |
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
| `client/AkcesClientController.java` | Replace Confluent imports, simplify types; keep Everit `ValidationException` |
| `test/.../AkcesClientTests.java` | Replace imports |

### Other Modules
| File | Change |
|------|--------|
| `main/query-support/test/.../TestUtils.java` | Replace imports |
| `main/eventcatalog/test/.../EventCatalogProcessorTest.java` | Replace imports |
| `test-apps/crypto-trading/*/test/.../TestUtils.java` (3 files) | Replace imports |
| `main/pom.xml` | Remove Confluent dependency management, repository |
| `bom/pom.xml` | Remove Confluent references if any |

### Spring Boot Jackson 3 Migration (Phase 8)
| File | Change |
|------|--------|
| `main/runtime/pom.xml` | Replace `spring-boot-jackson2` → `spring-boot-jackson` |
| `main/client/pom.xml` | Replace `spring-boot-jackson2` → `spring-boot-jackson` |
| `main/query-support/pom.xml` | Replace `spring-boot-jackson2` → `spring-boot-jackson` |
| `main/runtime/.../AggregateServiceApplication.java` | Replace `Jackson2ObjectMapperBuilderCustomizer` → `JsonMapperBuilderCustomizer` |
| `main/client/.../AkcesClientAutoConfiguration.java` | Replace `Jackson2ObjectMapperBuilderCustomizer` → `JsonMapperBuilderCustomizer` |
| `main/query-support/.../AkcesQueryModelAutoConfiguration.java` | Replace `Jackson2ObjectMapperBuilderCustomizer` → `JsonMapperBuilderCustomizer` |
| `main/query-support/.../AkcesDatabaseModelAutoConfiguration.java` | Replace `Jackson2ObjectMapperBuilderCustomizer` → `JsonMapperBuilderCustomizer` |
| `main/runtime/test/.../WalletConfiguration.java` | Replace `Jackson2ObjectMapperBuilderCustomizer` → `JsonMapperBuilderCustomizer` |
| `main/runtime/test/.../AccountConfiguration.java` | Replace `Jackson2ObjectMapperBuilderCustomizer` → `JsonMapperBuilderCustomizer` |
| 10+ test files across modules | Replace `Jackson2ObjectMapperBuilder` with Jackson 3 builder |

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

**Dependency Reduction**: Removes ~15-20 transitive JARs from the Confluent dependency tree; retains `everit-json-schema` as explicit dependency
**Breaking Changes**: None for framework users (all changes are internal implementation)
**API Surface**: `SchemaRegistry`, `SchemaRecord`, `SchemaStorage` interfaces change their `JsonSchema` type from `io.confluent` to `org.elasticsoftware.akces.schemas`
**Spring Boot**: Phase 8 migrates from `spring-boot-jackson2` to `spring-boot-jackson` (Jackson 3), aligning with Spring Boot 4 defaults
**Timeline**: 7-10 days for Phases 1-7 (Confluent removal), additional time for Phase 8 (full Jackson 3 migration including Spring Boot)

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| SchemaDiff comparison misses edge cases | High | Comprehensive unit tests, compare results with Confluent's implementation for known schemas |
| JSON Schema validation behavior differs after wrapping | Low | Everit is retained as-is; only the wrapper class changes, not the validation logic |
| Transitive dependency on Jackson 2 via other libs | Low | Run `mvn dependency:tree` to verify no Jackson 2 remains after Phase 8 |
| `kafka-protobuf-serializer` removal breaks functionality | Medium | Verify no runtime usage, check if it's needed for Protobuf schema loading |
| Spring Boot Jackson 3 auto-configuration behavioral differences | Medium | Test all auto-configuration classes; `JsonMapperBuilderCustomizer` has similar API to `Jackson2ObjectMapperBuilderCustomizer` |

## Success Criteria

- [ ] Zero imports from `io.confluent` in any Java file
- [ ] `org.everit` imports retained (Everit is kept as explicit dependency)
- [ ] No Confluent dependencies in any `pom.xml`
- [ ] No Confluent Maven repository reference
- [ ] `com.github.erosb:everit-json-schema` added as explicit dependency
- [ ] All existing unit tests pass
- [ ] All existing integration tests pass
- [ ] Schema validation produces same results as before
- [ ] Schema compatibility checking produces same results as before
- [ ] `mvn clean compile` succeeds for entire project
- [ ] `mvn test` succeeds for entire project
- [ ] No `com.fasterxml.jackson` imports remain (Phase 8 only)
- [ ] No `spring-boot-jackson2` dependencies remain (Phase 8 only)
- [ ] All Spring Boot classes use Jackson 3 auto-configuration (`spring-boot-jackson`) (Phase 8 only)
