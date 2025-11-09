# Kafka-Based Schema Registry - Project Plan

This directory contains the complete plan for replacing the Confluent Schema Registry with a Kafka topic-based implementation.

## 📋 Documents

### [SUMMARY.md](./SUMMARY.md)
**Quick reference and executive summary**
- High-level overview of changes
- Key benefits and architecture at a glance
- Configuration examples
- Success metrics

**Read this first** for a quick understanding of the project.

### [PLAN.md](./PLAN.md)
**Detailed implementation plan**
- Current state analysis
- Design goals and proposed architecture
- Implementation phases with file-by-file changes
- Technical decisions and rationale
- Risk analysis and mitigation strategies
- Timeline estimates

**Read this** for comprehensive understanding of what, why, and how.

### [ARCHITECTURE.md](./ARCHITECTURE.md)
**Technical architecture and diagrams**
- Before/after architecture diagrams
- Component interaction flows
- Data models and schemas
- Deployment comparisons
- Performance characteristics
- Security considerations

**Read this** for visual representation and technical details.

### [IMPLEMENTATION_CHECKLIST.md](./IMPLEMENTATION_CHECKLIST.md)
**Step-by-step implementation guide**
- Detailed checklist for each phase
- File-by-file tasks
- Testing requirements
- Quality gates
- Sign-off criteria

**Use this** during implementation to track progress.

## 🎯 Project Goals

**Primary Goal**: Remove runtime dependency on Confluent Schema Registry service

**What Changes**:
- Replace HTTP-based Schema Registry with Kafka topic storage
- Store schemas in compacted `akces-schemas` topic
- Implement caching layer for performance

**What Stays**:
- Confluent libraries for schema validation and compatibility checking
- Existing `KafkaSchemaRegistry` public API
- All schema validation logic

## 🏗️ Architecture Overview

```
┌─────────────────────────────────┐
│      Application Layer          │
│   (Aggregates, Commands, etc)   │
└───────────┬─────────────────────┘
            │
            ▼
┌─────────────────────────────────┐
│    KafkaSchemaRegistry          │  ◄── No API changes
│    (Unchanged Public API)       │
└───────────┬─────────────────────┘
            │
    ┌───────┴────────┐
    ▼                ▼
┌────────┐    ┌──────────────┐
│Registry│    │KafkaTopicS...│  ◄── NEW
│Client  │    │   Storage    │
│(Old)   │    │   (New)      │
└────────┘    └──────┬───────┘
                     │
                     ▼
              ┌──────────────┐
              │ Kafka Topic  │
              │akces-schemas │
              └──────────────┘
```

## 📊 Key Benefits

1. **🚀 Simplified Deployment**: One less service to manage
2. **💰 Cost Reduction**: Save 500Mi-1Gi memory per deployment
3. **🔧 Operational Simplicity**: Fewer moving parts
4. **📈 Same Performance**: Cached reads <1ms (unchanged)
5. **🔄 Backward Compatible**: Existing code works unchanged

## 🛠️ Implementation Phases

### Phase 1: Core Infrastructure (2-3 days)
- Create `KafkaTopicSchemaStorage` interface and implementation
- Add caching layer with Caffeine
- Update `KafkaSchemaRegistry` to support both backends

### Phase 2: Auto-Configuration (1 day)
- Update all AutoConfiguration classes
- Add conditional bean creation
- Support dual-mode (registry or kafka)

### Phase 3: Testing (1-2 days)
- Unit tests for storage layer
- Integration tests with Kafka
- Backward compatibility tests
- Performance benchmarks

### Phase 4: Documentation (1 day)
- Migration guide
- Configuration documentation
- Update README and operator configs

**Total Estimated Time**: 5-7 days

## 📈 Success Criteria

- ✅ All existing tests pass with Kafka-based storage
- ✅ No runtime dependency on Confluent Schema Registry
- ✅ Backward compatibility maintained
- ✅ Performance comparable or better
- ✅ Complete documentation
- ✅ Clear migration path

## 🚀 Quick Start (For Implementation)

1. **Review the plan**: Read SUMMARY.md → PLAN.md → ARCHITECTURE.md
2. **Get approval**: Discuss with architect, address questions
3. **Follow checklist**: Use IMPLEMENTATION_CHECKLIST.md for step-by-step guidance
4. **Test thoroughly**: Run all tests at each phase
5. **Document**: Update docs as you go

## ⚠️ Important Notes

### What We're NOT Changing
- ❌ Public API of `KafkaSchemaRegistry`
- ❌ Schema validation logic
- ❌ Compatibility checking behavior
- ❌ Existing configuration for users who want registry mode

### Backward Compatibility
The implementation supports **dual-mode** operation:
- **Kafka Mode** (new, default): Uses topic storage
- **Registry Mode** (legacy): Uses HTTP Schema Registry

Users can choose mode via configuration:
```properties
# New way (default)
akces.schemas.storage=kafka

# Old way (still supported)
akces.schemas.storage=registry
akces.schemaregistry.url=http://localhost:8081
```

### Migration Path
1. Deploy new version with registry mode (no changes)
2. Validate everything works
3. Switch to kafka mode via configuration
4. Remove Schema Registry deployment (optional)

## 📝 Dependencies

### Keep Using
- `io.confluent:kafka-json-schema-serializer` - Schema validation
- `com.github.victools:jsonschema-generator` - Schema generation
- `SchemaDiff` - Compatibility checking

### Make Optional
- `io.confluent:kafka-schema-registry-client` - Only if using registry mode

### No New Dependencies
All required dependencies (Kafka, Jackson, Caffeine) already present!

## 🔍 Technical Highlights

### Topic Configuration
```yaml
name: akces-schemas
cleanup.policy: compact
partitions: 1
replication.factor: 3
```

### Key Format
```
{type}-{schemaName}-v{version}
# Where type is: commands | domainevents
# Examples:
# commands-CreateWalletCommand-v1
# commands-CreateWalletCommand-v2
# domainevents-WalletCreatedEvent-v1
```

### Value Format
```json
{
  "schemaName": "CreateWalletCommand",
  "version": 1,
  "schema": { /* JSON Schema */ },
  "registeredAt": "2025-11-09T11:34:51.453Z"
}
```

### Caching Strategy
- In-memory cache using Caffeine
- Background polling for updates
- TTL configurable (default 1 hour)
- Cache keys: `{type}-{schemaName}-v{version}`

## 📚 Reference

### Related Issues
- Issue: Remove Confluent Schema Registry runtime dependency

### Related Documentation
- Akces Framework README
- FRAMEWORK_OVERVIEW.md
- SERVICES.md

### External Resources
- Kafka Documentation: Compacted Topics
- Confluent Schema Registry API
- JSON Schema Specification

## 🤝 Contributors

- Framework Developer Agent (Plan creation)
- [To be added during implementation]

## 📅 Timeline

- **Plan Created**: 2025-11-09
- **Architect Review**: [Pending]
- **Implementation Start**: [To be scheduled]
- **Target Completion**: [5-7 days after start]
- **Release Version**: 0.11.0 (tentative)

## ❓ Questions?

If you have questions about this plan:
1. Check the FAQ sections in PLAN.md and ARCHITECTURE.md
2. Review the IMPLEMENTATION_CHECKLIST.md for specific tasks
3. Discuss with the architect
4. Update this README with answers for future reference

---

**Status**: 🟡 Plan Complete - Awaiting Architect Approval

Last Updated: 2025-11-09
