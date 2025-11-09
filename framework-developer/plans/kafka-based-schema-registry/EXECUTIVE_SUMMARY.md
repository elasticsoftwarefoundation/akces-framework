# Executive Summary: Kafka-Based Schema Registry

**Date**: 2025-11-09  
**Status**: ✅ Plan Complete - Awaiting Architect Approval  
**Estimated Effort**: 5-7 days  
**Risk Level**: 🟢 Low (backward compatible approach)

---

## Problem Statement

Currently, the Akces Framework has a runtime dependency on the external Confluent Schema Registry service. This creates:
- Unnecessary operational complexity
- Additional deployment requirements
- Extra resource consumption (500Mi-1Gi memory + CPU)
- Another potential point of failure

**Goal**: Remove the Schema Registry runtime dependency while keeping all validation functionality.

---

## Proposed Solution

Replace the HTTP-based Confluent Schema Registry with a **Kafka topic-based storage solution** that:
- Stores all schemas in a compacted Kafka topic (`akces-schemas`)
- Uses Confluent libraries for validation (no external service)
- Maintains complete backward compatibility
- Provides comparable performance

---

## Key Benefits

### 🚀 Operational
- **One less service** to deploy and manage
- **Simplified Kubernetes** deployments
- **Unified infrastructure** (just Kafka)
- **Easier troubleshooting** (fewer moving parts)

### 💰 Cost Savings
- **500Mi-1Gi memory saved** per deployment
- **250m-500m CPU saved** per deployment
- **Reduced monitoring** overhead
- **Lower cloud costs** (fewer services)

### 🔧 Technical
- **No breaking changes** to existing APIs
- **Backward compatible** (dual-mode support)
- **Same validation logic** (using Confluent libs)
- **Performance**: <1ms cached reads (unchanged)

---

## Architecture Overview

### Current (Before)
```
Application → KafkaSchemaRegistry → SchemaRegistryClient (HTTP)
                                          ↓
                                  Schema Registry Service
                                  (External dependency)
                                          ↓
                                    Kafka Cluster
```

**Issues**: External service, HTTP overhead, extra deployment

### Proposed (After)
```
Application → KafkaSchemaRegistry (unchanged API)
                    ↓
              KafkaTopicSchemaStorage (NEW)
                    ↓
              Kafka Topic: akces-schemas
              (Compacted, direct access)
```

**Benefits**: Direct Kafka access, no external service, same API

---

## Technical Approach

### Storage Model
- **Topic**: `akces-schemas` (compacted)
- **Key**: `{type}-{schemaName}-v{version}` where type is `commands` or `domainevents` (e.g., `commands-CreateWalletCommand-v1`, `domainevents-WalletCreatedEvent-v1`)
- **Value**: JSON with schema definition and metadata
- **Partitions**: 1 (maintains ordering)
- **Replication**: Configurable (default: 3)

### Caching Strategy
- **In-memory cache**: Caffeine (already used in framework)
- **Background polling**: Keep cache updated
- **TTL**: Configurable (default: 1 hour)
- **Performance**: <1ms for cached reads

### Compatibility
- **Keep using**: Confluent `SchemaDiff` for validation
- **Keep using**: Confluent `JsonSchema` classes
- **No changes**: Public API of `KafkaSchemaRegistry`
- **Dual-mode**: Support both registry and kafka modes

---

## Migration Path

The implementation supports **zero-downtime migration**:

1. **Deploy** new version (defaults to current registry mode)
2. **Validate** everything works as before
3. **Switch** to kafka mode via configuration change
4. **Monitor** and verify functionality
5. **Remove** Schema Registry deployment (optional)

**Configuration**:
```properties
# New way (default in future)
akces.schemas.storage=kafka
akces.schemas.topic=akces-schemas

# Old way (still supported for migration)
akces.schemas.storage=registry
akces.schemaregistry.url=http://localhost:8081
```

---

## Implementation Plan

### Phase 1: Core Infrastructure (2-3 days)
- Create `KafkaTopicSchemaStorage` interface
- Implement storage with caching
- Integrate with existing `KafkaSchemaRegistry`
- Comprehensive unit tests

### Phase 2: Auto-Configuration (1 day)
- Update Spring Boot configurations
- Add conditional bean creation
- Support dual-mode operation

### Phase 3: Testing (1-2 days)
- Integration tests with Kafka
- Backward compatibility validation
- Performance benchmarks
- Migration scenario testing

### Phase 4: Documentation (1 day)
- Migration guide
- Configuration documentation
- Update README and operator
- API documentation

**Total**: 5-7 days

---

## Performance Characteristics

| Metric | Current (Registry) | Proposed (Kafka) | Status |
|--------|-------------------|------------------|--------|
| Cached Read | <1ms | <1ms | ✅ Same |
| Write Latency | 50-150ms | 50-200ms | ✅ Similar |
| Cold Start | 500ms-1s | 1-2s | ⚠️ Acceptable |
| Memory (App) | Minimal | +1-10MB cache | ✅ Negligible |
| Memory (Infra) | +500Mi-1Gi | -500Mi-1Gi | ✅ **Savings!** |

**Conclusion**: Performance nearly identical, with significant infrastructure savings.

---

## Risk Assessment

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Compatibility issues | High | Low | Dual-mode, extensive testing |
| Performance degradation | Medium | Low | Caching, benchmarking |
| Migration complexity | Medium | Low | Clear guide, tooling |
| Topic compaction issues | High | Very Low | Proper key design |

**Overall Risk**: 🟢 **LOW** - Well-defined approach with backward compatibility

---

## Success Criteria

✅ **Must Have**:
- All existing tests pass with new backend
- Zero runtime Schema Registry dependency
- No breaking changes to public APIs
- Performance within 10% of current
- Complete migration documentation

🎯 **Should Have**:
- Migration tool for existing schemas
- Comprehensive monitoring examples
- Kubernetes operator updates
- Test application examples

💡 **Nice to Have**:
- Better performance than registry
- Schema versioning UI
- Cross-region replication support

---

## Documentation Deliverables

All documentation has been created in: `framework-developer/plans/kafka-based-schema-registry/`

1. **README.md** (7.5K) - Plan overview and navigation
2. **SUMMARY.md** (8.3K) - Quick reference and architecture
3. **PLAN.md** (12K) - Detailed implementation plan
4. **ARCHITECTURE.md** (25K) - Technical diagrams and flows
5. **IMPLEMENTATION_CHECKLIST.md** (14K) - Step-by-step guide

**Total Documentation**: ~67K characters, 1,833 lines

---

## Dependencies

### To Keep Using
- ✅ `io.confluent:kafka-json-schema-serializer` - Schema validation
- ✅ `com.github.victools:jsonschema-generator` - Schema generation
- ✅ Confluent `SchemaDiff` - Compatibility checking

### To Make Optional
- ⚠️ `io.confluent:kafka-schema-registry-client` - Only if using registry mode

### New Dependencies
- ✅ **NONE!** All required deps already present (Kafka, Jackson, Caffeine)

---

## Impact Assessment

### Code Changes
- **New files**: ~5-7 classes (storage layer)
- **Modified files**: ~6 configuration classes
- **Test files**: ~3-5 new test classes
- **Documentation**: Complete suite provided

### Breaking Changes
- **NONE** - Complete backward compatibility maintained

### Deployment Changes
- **Optional**: Can remove Schema Registry service
- **Configuration**: New properties (with sensible defaults)
- **Kubernetes**: Updated operator configs (backward compatible)

---

## Recommendation

✅ **APPROVE** and proceed with implementation

**Rationale**:
1. Clear business value (cost savings, simplification)
2. Low technical risk (backward compatible, well-tested approach)
3. Comprehensive plan and documentation
4. Reasonable timeline (5-7 days)
5. No breaking changes or migration pain
6. Natural fit with existing Kafka infrastructure

---

## Questions for Approval

Before proceeding, please confirm:

1. ✅ **Architecture Approach**: Approved as designed?
2. ⚠️ **Default Mode**: Should new installations default to `kafka` or `registry`?
3. ⚠️ **Migration Tool**: Include in initial release or separate effort?
4. ⚠️ **Kubernetes Operator**: Update in same PR or separate?
5. ✅ **Timeline**: 5-7 days acceptable?
6. ⚠️ **Release Version**: Target 0.11.0 or sooner?

---

## Next Steps

Upon approval:

1. **Begin Phase 1**: Core infrastructure implementation
2. **Daily Progress Reports**: Using report_progress tool
3. **Continuous Testing**: Run tests after each phase
4. **Documentation Updates**: As implementation progresses
5. **Code Review**: Before final merge

---

## Contact

- **Plan Created By**: Framework Developer Agent
- **Date**: 2025-11-09
- **Branch**: `copilot/rewrite-schema-registry-feature`
- **Documentation**: `framework-developer/plans/kafka-based-schema-registry/`

---

**Status**: 🟡 **AWAITING ARCHITECT APPROVAL**

Once approved, implementation can begin immediately following the detailed checklist in `IMPLEMENTATION_CHECKLIST.md`.
