# Plan: Kafka-based Schema Registry Implementation

## Overview
Replace the current Confluent Schema Registry dependency with a Kafka topic-based implementation, while retaining Confluent libraries for backward compatibility checking and JSON schema validation.

## Current State Analysis

### Dependencies
Currently the framework depends on:
- `io.confluent:kafka-json-serializer` - For JSON schema support
- `io.confluent:kafka-json-schema-serializer` - For schema serialization
- `io.confluent.kafka.schemaregistry.client.SchemaRegistryClient` - External HTTP-based registry

### Current Usage
The `KafkaSchemaRegistry` class in the `shared` module uses `SchemaRegistryClient` for:
1. **Schema Registration**: `register()` - Store new schema versions
2. **Schema Retrieval**: `getSchemas()` - Get all schemas for a type
3. **Schema Versioning**: `getVersion()` - Get version number for a schema
4. **Schema Deletion**: `deleteSchemaVersion()` - Remove schemas (used when forceRegisterOnIncompatibleSchema=true)

The Confluent libraries are also used for:
- **Schema Comparison**: `SchemaDiff.compare()` - Check compatibility
- **Schema Representation**: `JsonSchema`, `ParsedSchema` - Schema objects
- **Difference Types**: `Difference` - Represent schema differences

### Configuration Points
Schema registry URL is configured at:
1. `main/client/src/main/java/.../AkcesClientAutoConfiguration.java` - `@Bean akcesClientSchemaRegistryClient`
2. `main/runtime/.../AggregateServiceApplication.java` - `@Bean aggregateServiceSchemaRegistryClient`
3. `main/query-support/.../AkcesQueryModelAutoConfiguration.java` - `@Bean akcesQueryModelSchemaRegistryClient`
4. `main/query-support/.../AkcesDatabaseModelAutoConfiguration.java` - `@Bean akcesDatabaseModelSchemaRegistryClient`
5. Kubernetes operator configmaps - `akces.schemaregistry.url=http://akces-schema-registry.kafka:8081`

### Test Infrastructure
Tests use Testcontainers to spin up:
- Kafka container
- Schema Registry container (confluentinc/cp-schema-registry)

## Design Goals

1. **Remove Runtime Dependency**: Eliminate the need for Confluent Schema Registry service
2. **Keep Compatibility Checking**: Continue using Confluent's `SchemaDiff` for validation
3. **Kafka-Native Storage**: Store schemas in a dedicated Kafka topic
4. **Backward Compatibility**: Ensure existing schema validation logic continues to work
5. **Minimal Changes**: Keep the public API of `KafkaSchemaRegistry` unchanged

## Proposed Architecture

### Schema Storage Topic
Create a compacted Kafka topic to store schemas:
- **Topic Name**: `akces-schemas` (configurable via `akces.schemas.topic`)
- **Key Format**: `<schemaName>-v<version>` (e.g., `CreateWalletCommand-v1`)
- **Value Format**: JSON containing:
  ```json
  {
    "schemaName": "CreateWalletCommand",
    "version": 1,
    "schema": { /* JSON Schema object */ },
    "registeredAt": "2025-11-09T11:34:51.453Z"
  }
  ```
- **Cleanup Policy**: `compact` - Keep latest value for each key
- **Partitions**: 1 (to maintain ordering)
- **Replication Factor**: 3 (configurable)

### New Components

#### 1. KafkaTopicSchemaStorage (Interface)
```java
public interface KafkaTopicSchemaStorage {
    /**
     * Register a new schema version
     */
    void registerSchema(String schemaName, JsonSchema schema, int version) throws SchemaException;
    
    /**
     * Get all schemas for a given schema name
     */
    List<SchemaRecord> getSchemas(String schemaName) throws SchemaException;
    
    /**
     * Get a specific schema version
     */
    Optional<SchemaRecord> getSchema(String schemaName, int version) throws SchemaException;
    
    /**
     * Delete a schema version (for forceRegister scenarios)
     */
    void deleteSchema(String schemaName, int version) throws SchemaException;
    
    /**
     * Initialize the storage (create topic if needed)
     */
    void initialize() throws SchemaException;
}

public record SchemaRecord(
    String schemaName,
    int version,
    JsonSchema schema,
    String registeredAt
) {}
```

#### 2. KafkaTopicSchemaStorageImpl (Implementation)
- Uses Kafka Producer to write schemas
- Uses Kafka Consumer to read schemas
- Maintains in-memory cache (Caffeine) of loaded schemas
- Implements tombstone records for deletion (value=null)
- Handles topic creation on initialization

#### 3. KafkaTopicSchemaRegistryClient (Adapter)
Implements a subset of `SchemaRegistryClient` interface needed by `KafkaSchemaRegistry`:
- Adapts `KafkaTopicSchemaStorage` to look like a `SchemaRegistryClient`
- Translates between `ParsedSchema` and our `SchemaRecord`
- Maintains compatibility with existing code

### Modified Components

#### KafkaSchemaRegistry
- No public API changes
- Constructor accepts `KafkaTopicSchemaStorage` or continues to accept `SchemaRegistryClient` (for backward compatibility)
- Internal implementation delegates to storage layer

## Implementation Plan

### Phase 1: Core Infrastructure (Shared Module)
**Files to Create:**
1. `main/shared/src/main/java/org/elasticsoftware/akces/schemas/storage/KafkaTopicSchemaStorage.java`
2. `main/shared/src/main/java/org/elasticsoftware/akces/schemas/storage/SchemaRecord.java`
3. `main/shared/src/main/java/org/elasticsoftware/akces/schemas/storage/KafkaTopicSchemaStorageImpl.java`
4. `main/shared/src/main/java/org/elasticsoftware/akces/schemas/storage/KafkaTopicSchemaStorageException.java`

**Files to Modify:**
1. `main/shared/src/main/java/org/elasticsoftware/akces/schemas/KafkaSchemaRegistry.java`
   - Add constructor accepting `KafkaTopicSchemaStorage`
   - Add internal adapter to bridge to existing code
   - Keep existing constructor for backward compatibility

**Configuration:**
- Add property: `akces.schemas.topic=akces-schemas`
- Add property: `akces.schemas.enabled=true` (to enable new implementation)

### Phase 2: Auto-Configuration Updates
**Files to Modify:**
1. `main/client/src/main/java/.../AkcesClientAutoConfiguration.java`
   - Add conditional bean for `KafkaTopicSchemaStorage`
   - Modify `createSchemaRegistry()` to use storage when enabled
   - Keep SchemaRegistryClient bean for backward compatibility

2. `main/runtime/.../AggregateServiceApplication.java`
   - Add conditional bean for `KafkaTopicSchemaStorage`
   - Modify `schemaRegistry()` to use storage when enabled

3. `main/query-support/.../AkcesQueryModelAutoConfiguration.java`
   - Similar changes as above

4. `main/query-support/.../AkcesDatabaseModelAutoConfiguration.java`
   - Similar changes as above

### Phase 3: Testing
**Files to Create:**
1. `main/shared/src/test/java/.../schemas/storage/KafkaTopicSchemaStorageTest.java`
   - Unit tests for schema storage
   - Test CRUD operations
   - Test caching behavior
   - Test topic creation

2. `main/shared/src/test/java/.../schemas/KafkaSchemaRegistryWithStorageTest.java`
   - Integration test using storage backend
   - Verify compatibility with existing validation logic

**Files to Modify:**
1. Update test containers configuration to not require schema registry when using Kafka-based storage
2. Add tests to verify backward compatibility when using SchemaRegistryClient

### Phase 4: Documentation & Migration
**Files to Create:**
1. `docs/SCHEMA_STORAGE_MIGRATION.md` - Migration guide
2. Update README.md to reflect optional Schema Registry dependency

**Files to Modify:**
1. `main/shared/pom.xml` - Mark confluent dependencies as optional
2. Kubernetes operator YAML files - Add configuration for Kafka-based storage
3. Test application properties - Add examples

## Technical Decisions

### 1. Topic Structure
- **Single Topic vs Multiple Topics**: Use a single compacted topic for all schemas
  - Pros: Simpler management, easier to backup/restore
  - Cons: All schemas in one namespace
- **Key Format**: `<schemaName>-v<version>`
  - Ensures unique keys per schema version
  - Allows compaction to work correctly
  - Natural ordering when listing

### 2. Caching Strategy
- Use Caffeine cache with configurable TTL (default: 1 hour)
- Cache key: `<schemaName>-v<version>`
- Invalidate on write/delete
- Initial load: Read all records from topic on startup

### 3. Concurrency
- Producer: Single shared instance, thread-safe
- Consumer: Background thread polls topic for updates
- Cache: Thread-safe Caffeine cache
- Synchronization: Lock on schema registration to prevent races

### 4. Error Handling
- Kafka errors wrapped in `SchemaException`
- Retry logic for transient failures
- Circuit breaker for persistent issues

### 5. Schema Deletion
- Use tombstone records (null value) for deletion
- Keep metadata about deletion in a separate key if needed
- Cleanup policy ensures tombstones eventually removed

## Migration Strategy

### Phase 1: Dual-Mode Support (Current + New)
- Both implementations available
- Configuration flag to choose: `akces.schemas.storage=kafka|registry`
- Default: `registry` (existing behavior)
- Allows gradual migration and testing

### Phase 2: Deprecation
- Mark SchemaRegistryClient-based code as deprecated
- Update documentation to recommend Kafka-based storage
- Update all examples and test apps

### Phase 3: Default Switch
- Change default to `kafka`
- Keep registry support for backward compatibility
- Update operator to use Kafka-based by default

### Phase 4: Optional Removal (Future)
- Make SchemaRegistryClient dependency optional in pom.xml
- Document as legacy feature
- Consider removal in next major version

## Risks & Mitigations

### Risk 1: Schema Topic Compaction Issues
- **Risk**: Compaction might remove schemas we still need
- **Mitigation**: Use proper key design to ensure each version has unique key

### Risk 2: Performance Impact
- **Risk**: Reading from Kafka might be slower than HTTP registry
- **Mitigation**: Aggressive caching, background topic polling

### Risk 3: Schema Migration from Registry to Kafka
- **Risk**: Existing users need to migrate schemas
- **Mitigation**: Provide migration tool to export from registry and import to topic

### Risk 4: Breaking Changes
- **Risk**: Changes might break existing applications
- **Mitigation**: Dual-mode support, extensive testing, phased rollout

## Success Criteria

1. ✅ All existing tests pass with Kafka-based storage
2. ✅ No runtime dependency on Confluent Schema Registry
3. ✅ Backward compatibility maintained for existing code
4. ✅ Performance comparable to registry-based approach
5. ✅ Documentation complete and clear
6. ✅ Migration path defined and tested

## Timeline Estimate

- **Phase 1**: Core Infrastructure - 2-3 days
- **Phase 2**: Auto-Configuration - 1 day
- **Phase 3**: Testing - 1-2 days
- **Phase 4**: Documentation - 1 day

**Total**: 5-7 days

## Open Questions

1. Should we provide a migration tool to export schemas from existing registry?
2. What should be the default behavior for new installations?
3. Should we support cross-region schema replication?
4. How to handle schema registry mode selection in Kubernetes operator?

## Dependencies to Keep

The following Confluent dependencies will be **retained** for their utility:
- `io.confluent:kafka-json-schema-serializer` - For JsonSchema class and schema validation
- `io.confluent.kafka.schemaregistry.json.diff.SchemaDiff` - For compatibility checking
- `io.confluent.kafka.schemaregistry.json.diff.Difference` - For representing differences

These provide valuable functionality for schema comparison and validation without requiring the registry service.

## Dependencies to Make Optional

The following dependencies can become **optional**:
- `io.confluent:kafka-schema-registry-client` - Only needed if using registry mode

## New Dependencies Required

Minimal new dependencies:
- None! All required dependencies (Kafka client, Jackson, Caffeine) already present

## Conclusion

This plan provides a path to remove the runtime dependency on Confluent Schema Registry while maintaining all existing functionality and backward compatibility. The implementation is straightforward, leverages existing Kafka infrastructure, and provides a smooth migration path for existing users.
