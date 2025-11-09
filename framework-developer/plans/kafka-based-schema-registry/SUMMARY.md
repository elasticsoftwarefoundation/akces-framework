# Kafka-Based Schema Registry - Implementation Summary

## Quick Reference

### What We're Changing
- **FROM**: External Confluent Schema Registry HTTP service dependency
- **TO**: Kafka topic-based schema storage using compacted topics

### What We're Keeping
- Confluent libraries for schema validation and compatibility checking
- Existing `KafkaSchemaRegistry` public API
- All schema validation logic and compatibility rules

### Key Benefits
1. **No Runtime Dependency**: Remove need for separate Schema Registry service
2. **Simplified Deployment**: One less service to manage in Kubernetes
3. **Kafka-Native**: Leverage existing Kafka infrastructure
4. **Cost Reduction**: No additional service to run/maintain
5. **Backward Compatible**: Existing code continues to work

## Architecture at a Glance

```
┌─────────────────────────────────────────────────────────────┐
│                     Application Layer                       │
│  (Aggregates, Commands, Events, Query Models)              │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│              KafkaSchemaRegistry (Unchanged API)            │
│  • registerAndValidate()                                    │
│  • validate()                                               │
│  • generateJsonSchema()                                     │
└─────────────────┬───────────────────────────────────────────┘
                  │
       ┌──────────┴──────────┐
       ▼                     ▼
┌─────────────────┐   ┌──────────────────────┐
│ Schema Registry │   │ KafkaTopicSchema     │  ◄── NEW
│ Client (Old)    │   │ Storage (New)        │
└─────────────────┘   └──────────────────────┘
       │                     │
       ▼                     ▼
┌─────────────────┐   ┌──────────────────────┐
│   HTTP API      │   │  Kafka Topic:        │
│   (External)    │   │  "akces-schemas"     │
│                 │   │  (Compacted)         │
└─────────────────┘   └──────────────────────┘
```

## Key Components

### 1. KafkaTopicSchemaStorage
**New interface for schema storage operations**
- `registerSchema()` - Store schema
- `getSchemas()` - Retrieve all versions
- `getSchema()` - Get specific version
- `deleteSchema()` - Remove version
- `initialize()` - Setup topic

### 2. Schema Topic Structure
**Topic**: `akces-schemas`
- **Cleanup**: Compacted (retains latest per key)
- **Key**: `{type}-{schemaName}-v{version}` where type is `commands` or `domainevents`
- **Value**: JSON schema + metadata
- **Partitions**: 1 (for ordering)

### 3. Caching Layer
- In-memory cache (Caffeine)
- Background topic polling for updates
- Configurable TTL

## Configuration

### Old Way (Schema Registry)
```properties
akces.schemaregistry.url=http://localhost:8081
```

### New Way (Kafka Topic)
```properties
akces.schemas.storage=kafka
akces.schemas.topic=akces-schemas
akces.schemas.cache.ttl=3600
```

### Backward Compatible
```properties
# Use old registry (for migration period)
akces.schemas.storage=registry
akces.schemaregistry.url=http://localhost:8081
```

## Implementation Checklist

### Phase 1: Core Infrastructure ✓ (To Plan)
- [ ] Create `KafkaTopicSchemaStorage` interface
- [ ] Create `SchemaRecord` class
- [ ] Implement `KafkaTopicSchemaStorageImpl`
- [ ] Add caching with Caffeine
- [ ] Implement topic initialization
- [ ] Add comprehensive error handling

### Phase 2: Integration ✓ (To Plan)
- [ ] Modify `KafkaSchemaRegistry` to support both backends
- [ ] Update `AkcesClientAutoConfiguration`
- [ ] Update `AggregateServiceApplication`
- [ ] Update `AkcesQueryModelAutoConfiguration`
- [ ] Update `AkcesDatabaseModelAutoConfiguration`

### Phase 3: Testing ✓ (To Plan)
- [ ] Unit tests for `KafkaTopicSchemaStorage`
- [ ] Integration tests with Kafka
- [ ] Backward compatibility tests
- [ ] Performance benchmarks
- [ ] Migration tests

### Phase 4: Documentation & Operations ✓ (To Plan)
- [ ] Migration guide
- [ ] Configuration documentation
- [ ] Kubernetes operator updates
- [ ] Test application updates
- [ ] README updates

## Migration Path

### Step 1: Install New Version
Deploy application with dual-mode support (both registry and Kafka available).

### Step 2: Export Existing Schemas (Optional)
If migrating from existing registry:
```bash
# Tool to be provided
akces-schema-export --from registry --to kafka
```

### Step 3: Switch to Kafka Mode
Update configuration:
```properties
akces.schemas.storage=kafka
```

### Step 4: Remove Registry (Optional)
Remove Schema Registry from deployment after validation period.

## Technical Decisions Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Storage Backend | Single Kafka Topic | Simpler management, natural fit |
| Topic Cleanup | Compacted | Retain latest version per key |
| Caching | Caffeine with background poll | Performance + consistency |
| Compatibility | Keep Confluent libs | Proven validation logic |
| Migration | Dual-mode support | Zero downtime migration |
| Key Format | `{type}-{name}-v{version}` | Natural, unique, sortable, type-separated |

## Testing Strategy

### Unit Tests
- Schema storage CRUD operations
- Cache behavior (hits, misses, invalidation)
- Topic initialization
- Error handling

### Integration Tests
- End-to-end with Kafka
- Schema validation flow
- Compatibility checking
- Concurrent access

### Backward Compatibility Tests
- Existing tests with new backend
- Migration scenarios
- Both modes simultaneously

## Performance Considerations

### Read Performance
- **Cold Start**: Initial topic read on startup (~100-500ms for typical schema count)
- **Cached Reads**: <1ms (memory lookup)
- **Cache Miss**: 10-50ms (Kafka read + parse)

### Write Performance
- **Schema Registration**: 50-200ms (Kafka write + sync)
- **Comparable to**: HTTP registry performance
- **Bottleneck**: Kafka broker, not application

### Memory Usage
- **Cache Size**: ~1-10KB per schema
- **Typical Load**: 100 schemas = ~1MB
- **Acceptable**: Even with 1000s of schemas

## Risks & Mitigations

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Topic compaction removes needed data | High | Low | Proper key design prevents this |
| Performance degradation | Medium | Low | Aggressive caching, benchmarking |
| Migration complexity | Medium | Medium | Dual-mode support, tooling |
| Breaking changes | High | Low | Extensive testing, backward compat |

## Success Metrics

✅ **Must Have**:
- All tests pass with new backend
- No runtime Schema Registry dependency
- Same validation behavior

🎯 **Should Have**:
- Performance within 10% of registry
- Migration tool available
- Complete documentation

💡 **Nice to Have**:
- Better performance than registry
- Cross-region replication support
- Schema versioning UI

## Next Steps

1. **Review & Approve Plan**: Get architect feedback
2. **Prototype**: Build core `KafkaTopicSchemaStorage`
3. **Validate**: Test with existing schemas
4. **Integrate**: Connect to `KafkaSchemaRegistry`
5. **Test**: Comprehensive test suite
6. **Document**: Migration guides
7. **Release**: Phased rollout

## Questions for Architect

1. ✅ Approve overall approach?
2. ✅ Preferred default mode (kafka vs registry)?
3. ✅ Need migration tool in initial release?
4. ✅ Kubernetes operator changes in same PR?
5. ✅ Timeline acceptable (5-7 days)?

---

**Plan Status**: 🟡 Awaiting Approval
**Created**: 2025-11-09
**Author**: Framework Developer Agent
