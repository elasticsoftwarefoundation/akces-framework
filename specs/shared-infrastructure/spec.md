# Shared Infrastructure Module — Feature Specification

> **Status**: Migrated
> **Module**: `main/shared/` (`akces-shared`)
> **Version**: 0.12.1-SNAPSHOT
> **Package**: `org.elasticsoftware.akces`
> **Input**: Brownfield migration of existing module (~4907 source lines across 60 files, ~1235 test lines across 11 files)

## Overview

The Shared Infrastructure module provides the foundational building blocks consumed by every runtime module in the Akces CQRS/Event Sourcing framework — runtime, client, query-support, and agentic. It defines the Kafka wire-format protocol records, a complete GDPR/PII encryption framework with pluggable storage backends, a JSON Schema registry with backward-compatibility checking, aggregate service discovery via control records, Kafka producer/consumer utilities, Protobuf and JSON serialization, and built-in error events. This module depends solely on `akces-api` within the framework.

## User Scenarios

### US-1: Serialize and Deserialize Protocol Records over Kafka

**As a** framework runtime developer,
**I want to** serialize commands, domain events, aggregate state, GDPR keys, and command responses into a compact binary wire format and deserialize them back,
**so that** all Akces components communicate over Kafka using a consistent, versioned protocol.

**Acceptance Criteria:**

1. `ProtocolRecord` is a sealed interface permitting exactly five record types: `CommandRecord`, `DomainEventRecord`, `AggregateStateRecord`, `GDPRKeyRecord`, and `CommandResponseRecord`.
2. Every `ProtocolRecord` exposes `tenantId()`, `name()`, `version()`, `payload()`, `encoding()`, `aggregateId()`, and `correlationId()`.
3. `CommandRecord` and `DomainEventRecord` auto-generate a UUID `id` when constructed with the convenience constructor.
4. `CommandResponseRecord` wraps a list of `DomainEventRecord` events plus an optional `encryptionKey` for GDPR-aware response deserialization.
5. `GDPRKeyRecord` carries a raw `byte[]` payload (the encryption key) associated with an aggregate ID.
6. `PayloadEncoding` enum supports `JSON`, `PROTOBUF`, and `BYTES` encodings.
7. `SchemaRecord` (non-sealed, outside the `ProtocolRecord` hierarchy) stores schema name, version, `JsonSchema` definition, and registration timestamp.
8. `ProtocolRecordSerde` serializes/deserializes all five `ProtocolRecord` types using Protobuf format, selecting the correct Protobuf schema based on the Kafka topic name.
9. `SchemaRecordSerde` serializes/deserializes `SchemaRecord` using Protobuf with an intermediate DTO conversion.
10. `AkcesControlRecordSerde` serializes/deserializes `AkcesControlRecord` using Jackson JSON with polymorphic type deduction.

### US-2: Encrypt and Decrypt PII Fields Transparently via GDPR Context

**As a** framework runtime developer,
**I want to** automatically encrypt PII-annotated fields during JSON serialization and decrypt them during deserialization,
**so that** personal data is protected at rest in Kafka without application code changes.

**Acceptance Criteria:**

1. `GDPRContext` is a sealed interface permitting `NoopGDPRContext` (pass-through) and `EncryptingGDPRContext` (AES-256 encryption).
2. `EncryptingGDPRContext` encrypts strings using AES with ECB mode (short values) or CBC mode, encoding results as Base64 URL-safe strings.
3. `GDPRContextHolder` uses a `ThreadLocal<GDPRContext>` to make the current GDPR context available during serialization/deserialization without explicit parameter passing.
4. `GDPRKeyUtils` generates 256-bit AES encryption keys using `SecureRandom` and validates UUID strings.
5. `GDPRAnnotationUtils.hasPIIDataAnnotation(Class)` recursively inspects fields, methods, constructor parameters, and nested record types for the `@PIIData` annotation.
6. `GDPRContextRepository` manages GDPR key storage with transactional semantics: `prepare()`, `commit()`, `rollback()`, `process()` for Kafka consumer records, `exists()`, and `get()`.
7. `InMemoryGDPRContextRepository` stores keys in a `HashMap` with Caffeine cache eviction and tracks Kafka offsets for replay.
8. `RocksDBGDPRContextRepository` stores keys durably in RocksDB with transactional writes and Caffeine caching for hot entries.
9. Both repositories are created via their respective `GDPRContextRepositoryFactory` implementations.
10. The Jackson `AkcesGDPRModule` registers `PIIDataSerializerModifier` and `PIIDataDeserializerModifier`, which detect `@PIIData` annotations and wrap field serializers/deserializers with `PIIDataJsonSerializer` and `PIIDataJsonDeserializer` that delegate to `GDPRContextHolder`.

### US-3: Register and Validate JSON Schemas with Backward Compatibility

**As a** framework runtime developer,
**I want to** generate JSON Schemas from Java types, register them in a Kafka-backed schema store, and enforce backward compatibility between versions,
**so that** command and event schemas evolve safely without breaking consumers.

**Acceptance Criteria:**

1. `SchemaRegistry` interface provides `validate()` (for external schemas), `registerAndValidate()` (for owned schemas), and `generateJsonSchema()` methods.
2. `SchemaRegistry.createJsonSchemaGenerator()` configures a victools `SchemaGenerator` with Draft 7, Jakarta Validation module (pattern expressions, not-nullable-required), Jackson module, forbidden additional properties, nullable fields by default, and BigDecimal-as-string override.
3. `KafkaSchemaRegistry` implements `SchemaRegistry`, using `SchemaStorage` for persistence and a `ThreadLocal<SchemaGenerator>` for thread safety.
4. `registerAndValidate()` registers version 1 of a new schema; for subsequent versions it validates backward compatibility via `SchemaDiff` and registers only if compatible; for external schemas it validates but does not register.
5. `registerAndValidate()` supports `forceRegisterOnIncompatibleSchema` flag to overwrite incompatible schemas (with warning log).
6. `SchemaStorage` interface defines `saveSchema()`, `getSchemas()`, `getSchema()`, `initialize()`, `process()`, and `close()`.
7. `KafkaTopicSchemaStorage` persists schemas to the `Akces-Schemas` compacted Kafka topic using `SchemaRecordSerde` and maintains a Caffeine-cached in-memory index.
8. `SchemaDiff.compare()` performs recursive schema comparison returning a list of `Difference` objects, each typed by one of ~30 `Difference.Type` enum values.
9. `SchemaDiff.COMPATIBLE_CHANGES_STRICT` defines ~50 backward-compatible change types used for consumer-side validation.
10. Six exception types cover schema validation failures: `SchemaNotFoundException`, `SchemaVersionNotFoundException`, `IncompatibleSchemaException`, `SchemaNotBackwardsCompatibleException`, `InvalidSchemaVersionException`, `PreviousSchemaVersionMissingException` — all extending `SchemaException`.

### US-4: Manage Aggregate Service Discovery via Control Records

**As a** framework runtime developer,
**I want to** publish and consume aggregate service metadata on a Kafka control topic,
**so that** clients and other services can discover which aggregates handle which commands and events.

**Acceptance Criteria:**

1. `AkcesControlRecord` is a sealed interface (with Jackson `@JsonTypeInfo` deduction) permitting `AggregateServiceRecord`.
2. `AggregateServiceRecord` contains: `aggregateName`, `commandTopic`, `domainEventTopic`, `type` (nullable), `supportedCommands`, `producedEvents`, `consumedEvents`, and `description` (nullable).
3. `AggregateServiceType` enum discriminates `STANDARD` (multi-partition) from `AGENTIC` (single-partition) services.
4. `AggregateServiceRecord.effectiveType()` defaults to `STANDARD` when `type` is null (backward compatibility with legacy records).
5. `AggregateServiceRecord.resolveAgenticTarget()` resolves command routing among multiple services: matches `AGENTIC` services by aggregate name, falls back to the single `STANDARD` service.
6. `AggregateServiceCommandType` and `AggregateServiceDomainEventType` describe individual command/event types within a service, with methods to convert to runtime `CommandType`/`DomainEventType`.
7. `AkcesRegistry` interface provides `resolveType()`, `resolveTopic()`, `resolveTopics()`, and `resolvePartition()` for command and event routing at runtime.

## Requirements

### R1: Sealed Protocol Record Hierarchy
`ProtocolRecord` must be a sealed interface permitting exactly `CommandRecord`, `DomainEventRecord`, `AggregateStateRecord`, `GDPRKeyRecord`, and `CommandResponseRecord`. All implementations must be Java records.

### R2: Auto-Generated UUIDs for Commands and Events
`CommandRecord` and `DomainEventRecord` must auto-generate a `UUID.randomUUID().toString()` identifier via their convenience constructors.

### R3: Pluggable GDPR Context Implementations
`GDPRContext` must be sealed, allowing only `NoopGDPRContext` (identity transform) and `EncryptingGDPRContext` (AES-256). New implementations require modifying the sealed hierarchy.

### R4: ThreadLocal GDPR Context Propagation
GDPR encryption context must be propagated via `ThreadLocal` in `GDPRContextHolder`, enabling transparent encryption/decryption during Jackson serialization without explicit parameter passing.

### R5: Transactional GDPR Key Storage
`GDPRContextRepository` must support a prepare/commit/rollback transaction protocol aligned with Kafka producer transactions.

### R6: Schema Version Sequencing
`KafkaSchemaRegistry.registerAndValidate()` must enforce that new schema versions are exactly `previousVersion + 1`. Non-sequential versions must throw `InvalidSchemaVersionException`.

### R7: Backward Compatibility Enforcement
Schema evolution must be validated using `SchemaDiff`. Breaking changes (not in `COMPATIBLE_CHANGES_STRICT`) must throw `SchemaNotBackwardsCompatibleException`.

### R8: Thread-Safe Schema Generation
`KafkaSchemaRegistry` must use `ThreadLocal<SchemaGenerator>` because the victools `SchemaGenerator` is not thread-safe.

### R9: BigDecimal as String Serialization
`BigDecimalSerializer` must serialize `BigDecimal` values as JSON strings (using `toPlainString()`) to preserve precision and avoid floating-point representation issues.

### R10: Compacted Topic Configuration
`KafkaUtils.createCompactedTopic()` must configure topics with `cleanup.policy=compact`, LZ4 compression, `min.insync.replicas` derived from quorum calculation, and 20MB max message bytes.

### R11: Built-in Error Events
The module must provide three built-in error events — `AggregateAlreadyExistsErrorEvent`, `AggregateNotFoundErrorEvent`, `CommandExecutionErrorEvent` — implementing `ErrorEvent` with `@DomainEventInfo` annotations.

## CQRS Design

### Protocol Record Types (Wire Format)

| Record | Sealed Permit | Key Fields | Encoding | Purpose |
|--------|--------------|------------|----------|---------|
| `CommandRecord` | `ProtocolRecord` | `id`, `tenantId`, `name`, `version`, `payload`, `encoding`, `aggregateId`, `correlationId`, `replyToTopicPartition` | Protobuf | Command wire format |
| `DomainEventRecord` | `ProtocolRecord` | `id`, `tenantId`, `name`, `version`, `payload`, `encoding`, `aggregateId`, `correlationId`, `generation` | Protobuf | Domain event wire format |
| `AggregateStateRecord` | `ProtocolRecord` | `tenantId`, `name`, `version`, `payload`, `encoding`, `aggregateId`, `correlationId`, `generation` | Protobuf | Aggregate state snapshot |
| `CommandResponseRecord` | `ProtocolRecord` | `tenantId`, `aggregateId`, `correlationId`, `commandId`, `events`, `encryptionKey` | Protobuf | Command response with events |
| `GDPRKeyRecord` | `ProtocolRecord` | `tenantId`, `aggregateId`, `payload` | Protobuf | GDPR encryption key |

### Schema Management Flow

```
Java Class + Annotations
        │
        ▼
SchemaRegistry.generateJsonSchema()  ← victools SchemaGenerator (Draft 7)
        │
        ▼
KafkaSchemaRegistry.registerAndValidate()
        │
        ├─ v1? → register directly
        ├─ v(N+1)? → SchemaDiff backward-compat check → register
        └─ existing version? → SchemaDiff strict-compat check → validate
        │
        ▼
KafkaTopicSchemaStorage  ← Akces-Schemas compacted Kafka topic + Caffeine cache
```

### Control Record Topology

| Topic | Record Type | Key | Purpose |
|-------|-------------|-----|---------|
| `Akces-Control` | `AggregateServiceRecord` | Aggregate name | Service discovery |
| `Akces-Schemas` | `SchemaRecord` | Schema name + version | Schema registry |
| `{Aggregate}-GDPRKeys` | `GDPRKeyRecord` | Aggregate ID | GDPR key storage |

### Error Events

| Event | Type Name | Fields |
|-------|-----------|--------|
| `AggregateAlreadyExistsErrorEvent` | `AggregateAlreadyExistsError` | `id`, `aggregateName` |
| `AggregateNotFoundErrorEvent` | `AggregateNotFoundError` | `id`, `aggregateName` |
| `CommandExecutionErrorEvent` | `CommandExecutionError` | `id`, `aggregateName`, `commandName`, `errorDescription` |

## Non-Functional Requirements

- **Compilation target**: Java 25
- **Serialization performance**: Protobuf for high-throughput protocol records; JSON for human-readable control records
- **Encryption**: AES-256 with ECB (short values) and CBC modes; Base64 URL-safe encoding
- **Caching**: Caffeine caches for GDPR contexts and schema storage
- **Thread safety**: `ThreadLocal` for GDPR context propagation and schema generation; `ConcurrentHashMap` not required (single-threaded controller patterns)
- **Durability**: RocksDB for persistent GDPR key storage; Kafka compacted topics for schemas and control records
- **Backward compatibility**: Schema evolution enforced via `SchemaDiff` with ~50 compatible change types

## Out of Scope

- Aggregate command handling and event sourcing logic (handled by `akces-runtime`)
- Client-side command submission and response correlation (handled by `akces-client`)
- Query model projections and database models (handled by `akces-query-support`)
- Kubernetes operator and deployment configuration (handled by `services/operator`)
- Annotation processing and EventCatalog generation (handled by `akces-eventcatalog`)
