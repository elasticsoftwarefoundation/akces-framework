# Kafka-Based Schema Registry Implementation Plan

## Problem
Current implementation requires deploying and managing a separate Confluent Schema Registry service, adding operational complexity and consuming 500Mi-1Gi memory + CPU per deployment.

## Solution
Replace HTTP-based Confluent Schema Registry with Kafka topic-based storage while retaining Confluent libraries for schema validation and compatibility checking.

## Architecture

### Storage Model
- **Topic**: `akces-schemas` (compacted, 1 partition, configurable replication)
- **Key**: `{type}-{schemaName}-v{version}` where type is `commands` or `domainevents`
  - Examples: `commands-CreateWalletCommand-v1`, `domainevents-WalletCreatedEvent-v1`
- **Value**: JSON with schema definition, version, and timestamp
- **Caching**: In-memory Caffeine cache with background polling, configurable TTL (default: 1 hour)

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
- Kafka Producer for writes
- Kafka Consumer for reads
- Caffeine cache for performance
- Background polling thread for cache updates
- Tombstone records for deletion (null value)

### Modified Components

**KafkaSchemaRegistry** - Add constructor accepting `KafkaTopicSchemaStorage`, maintain existing API

**AutoConfiguration Classes** - Add conditional beans based on `akces.schemas.storage` property:
- `main/client/.../AkcesClientAutoConfiguration.java`
- `main/runtime/.../AggregateServiceApplication.java`
- `main/query-support/.../AkcesQueryModelAutoConfiguration.java`
- `main/query-support/.../AkcesDatabaseModelAutoConfiguration.java`

**Configuration** (dual-mode support):
```properties
# Kafka mode (new, default)
akces.schemas.storage=kafka
akces.schemas.topic=akces-schemas
akces.schemas.cache.ttl=3600

# Registry mode (legacy, still supported)
akces.schemas.storage=registry
akces.schemaregistry.url=http://localhost:8081
```

## Implementation Phases

### Phase 1: Core Infrastructure (2-3 days)
- [ ] Create `KafkaTopicSchemaStorage` interface
- [ ] Implement `KafkaTopicSchemaStorageImpl` with Kafka producer/consumer
- [ ] Add Caffeine caching layer
- [ ] Implement topic initialization and background polling
- [ ] Update `KafkaSchemaRegistry` to support both backends
- [ ] Unit tests for storage layer

### Phase 2: Auto-Configuration (1 day)
- [ ] Update all AutoConfiguration classes with conditional beans
- [ ] Add configuration properties with defaults
- [ ] Support dual-mode operation (kafka/registry)

### Phase 3: Testing (1-2 days)
- [ ] Integration tests with Kafka (remove Schema Registry container requirement)
- [ ] Backward compatibility tests with registry mode
- [ ] Performance benchmarks
- [ ] Migration scenario tests

### Phase 4: Documentation & Deployment (1 day)
- [ ] Update README (mark Schema Registry as optional)
- [ ] Migration guide
- [ ] Update Kubernetes operator configs to default to Kafka mode
- [ ] Update test applications

## Technical Decisions

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

**Make Optional**:
- `io.confluent:kafka-schema-registry-client` - Only for registry mode

**No New Dependencies** - All required deps (Kafka, Jackson, Caffeine) already present

## Migration Path

1. Deploy new version (defaults to current registry mode)
2. Validate everything works
3. Switch to kafka mode via configuration
4. Monitor and verify
5. Remove Schema Registry deployment (optional)

## Impact

**Resource Savings**: -500Mi to -1Gi memory, -250m to -500m CPU per deployment  
**Performance**: <1ms cached reads (unchanged), 50-200ms writes (similar)  
**Breaking Changes**: None (backward compatible)  
**Timeline**: 5-7 days

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Topic compaction removes needed data | High | Proper key design ensures retention |
| Performance degradation | Medium | Aggressive caching, benchmarking |
| Migration complexity | Medium | Dual-mode support, clear guide |

## Success Criteria

- ✅ All existing tests pass with Kafka backend
- ✅ No runtime Schema Registry dependency
- ✅ Zero breaking changes to public APIs
- ✅ Performance within 10% of current
- ✅ Complete migration documentation
