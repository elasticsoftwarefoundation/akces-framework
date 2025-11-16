# Kafka-Based Schema Registry Implementation Plan

## Problem
Current implementation requires deploying and managing a separate Confluent Schema Registry service, adding operational complexity and consuming 500Mi-1Gi memory + CPU per deployment.

## Solution
Replace HTTP-based Confluent Schema Registry with Kafka topic-based storage. Retain Confluent libraries for schema validation and compatibility checking only (no external service dependency).

## Architecture

### Storage Model
- **Topic**: `akces-schemas` (compacted, 1 partition, configurable replication)
- **Key**: `{type}-{schemaName}-v{version}` where type is `commands` or `domainevents`
  - Examples: `commands-CreateWalletCommand-v1`, `domainevents-WalletCreatedEvent-v1`
- **Value**: Protobuf-encoded SchemaRecord containing schema definition, version, and timestamp
- **Caching**: In-memory Caffeine cache with background polling, configurable TTL (default: 1 hour)

**Protobuf Definition** (`SchemaRecord.proto`):
```protobuf
message SchemaRecord {
  optional string schemaName = 1;
  optional int32 version = 2;
  optional string schema = 3;        // JSON Schema as string
  optional int64 registeredAt = 4;   // Unix timestamp in milliseconds
}
```

### New Components

**Storage Layer** (`main/shared/src/main/java/org/elasticsoftware/akces/schemas/storage/`):
```java
public interface KafkaTopicSchemaStorage {
    void registerSchema(String schemaName, JsonSchema schema, int version) throws SchemaException;
    List<SchemaRecord> getSchemas(String schemaName) throws SchemaException;
    Optional<SchemaRecord> getSchema(String schemaName, int version) throws SchemaException;
    void deleteSchema(String schemaName, int version) throws SchemaException;
    void initialize() throws SchemaException;
}

public record SchemaRecord(String schemaName, int version, JsonSchema schema, String registeredAt) {}
```

**Implementation** (`KafkaTopicSchemaStorageImpl`):
- Kafka Producer for writes (protobuf serialization)
- Kafka Consumer for reads (protobuf deserialization)
- Caffeine cache for performance
- Background polling thread for cache updates
- Tombstone records for deletion (null value)

### Modified Components

**KafkaSchemaRegistry** - Replace SchemaRegistryClient with KafkaTopicSchemaStorage, maintain existing public API

**AutoConfiguration Classes** - Replace SchemaRegistryClient beans with KafkaTopicSchemaStorage:
- `main/client/.../AkcesClientAutoConfiguration.java`
- `main/runtime/.../AggregateServiceApplication.java`
- `main/query-support/.../AkcesQueryModelAutoConfiguration.java`
- `main/query-support/.../AkcesDatabaseModelAutoConfiguration.java`

**Configuration**:
```properties
akces.schemas.topic=Akces-Schemas
akces.schemas.cache.ttl=3600
```

## Implementation Phases

### Phase 1: Core Infrastructure (2-3 days)
- [ ] Create `SchemaRecord.proto` protobuf definition
- [ ] Generate Java classes from protobuf
- [ ] Create `KafkaTopicSchemaStorage` interface
- [ ] Implement `KafkaTopicSchemaStorageImpl` with Kafka producer/consumer and protobuf serialization
- [ ] Add Caffeine caching layer
- [ ] Implement topic initialization and background polling
- [ ] Replace SchemaRegistryClient in `KafkaSchemaRegistry` with KafkaTopicSchemaStorage
- [ ] Unit tests for storage layer

### Phase 2: Auto-Configuration (1 day)
- [ ] Replace SchemaRegistryClient beans with KafkaTopicSchemaStorage in all AutoConfiguration classes
- [ ] Remove SchemaRegistryClient dependencies
- [ ] Update configuration properties

### Phase 3: Testing (1-2 days)
- [ ] Integration tests with Kafka (remove Schema Registry container)
- [ ] Performance benchmarks
- [ ] Update existing tests to work without Schema Registry

### Phase 4: Documentation & Deployment (1 day)
- [ ] Update README (remove Schema Registry requirement)
- [ ] Update Kubernetes operator configs
- [ ] Update test applications
- [ ] Document schema storage in Kafka

## Technical Decisions

### Value Encoding
- Use Protobuf for efficient binary serialization
- Protobuf SchemaRecord message with fields: schemaName, version, schema (JSON as string), registeredAt
- Consistent with existing framework patterns (CommandRecord, DomainEventRecord)
- Smaller message size compared to JSON encoding

### Topic Structure
- Single compacted topic for all schemas (simpler management)
- Key format includes type prefix for command/event separation
- 1 partition to maintain ordering

### Caching
- Caffeine cache with configurable TTL
- Cache key: `{type}-{schemaName}-v{version}`
- Background polling keeps cache updated
- Invalidation on write/delete

### Error Handling
- Retry with exponential backoff for transient failures
- Circuit breaker after 3 consecutive failures
- Comprehensive logging

### Dependencies
**Keep Using**:
- `io.confluent:kafka-json-schema-serializer` - Schema validation
- Confluent `SchemaDiff` - Compatibility checking
- Confluent `JsonSchema` classes

**Remove**:
- `io.confluent:kafka-schema-registry-client` - No longer needed

**No New Dependencies** - All required deps (Kafka, Jackson, Caffeine) already present

## Migration Path

This is a breaking change that removes the Schema Registry dependency:

1. Users must migrate to new version
2. Schema Registry service is no longer needed
3. Existing schemas will need to be re-registered on first startup (schemas are generated from code)

## Impact

**Resource Savings**: -500Mi to -1Gi memory, -250m to -500m CPU per deployment  
**Performance**: <1ms cached reads (unchanged), 50-200ms writes (similar)  
**Breaking Changes**: Yes - removes Schema Registry dependency, schemas re-generated from code on startup  
**Timeline**: 5-7 days

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Topic compaction removes needed data | High | Proper key design ensures retention |
| Performance degradation | Medium | Aggressive caching, benchmarking |

## Success Criteria

- ✅ All existing tests pass with Kafka backend
- ✅ No runtime Schema Registry dependency
- ✅ Schema Registry completely removed from codebase
- ✅ Performance within 10% of current
- ✅ Complete documentation
