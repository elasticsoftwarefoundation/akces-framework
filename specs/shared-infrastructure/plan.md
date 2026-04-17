# Shared Infrastructure — Implementation Plan

> **Status:** Migrated
> **Module:** `main/shared/` (`akces-shared`)
> **Input**: Brownfield migration — module already implemented

## Summary

The Shared Infrastructure module provides the cross-cutting foundation for the Akces CQRS/Event Sourcing framework. It defines the Kafka wire-format protocol records (Protobuf-serialized), a complete GDPR/PII encryption framework with in-memory and RocksDB backends, a JSON Schema registry with backward-compatibility checking backed by Kafka compacted topics, aggregate service discovery via control records, and shared Kafka/serialization utilities. Every runtime Akces module (runtime, client, query-support, agentic) depends on this module; it depends only on `akces-api`.

## Technical Context

| Attribute | Value |
|-----------|-------|
| Language | Java 25+ |
| Framework | Spring Boot 4.x (limited — Spring context events, Kafka factories) |
| Messaging | Apache Kafka 4.x (KRaft mode) |
| Serialization | Jackson 3.x (`tools.jackson`) for JSON; Jackson Protobuf (`jackson-dataformat-protobuf`) for protocol records |
| Schema Generation | victools jsonschema-generator (Draft 7) with Jakarta Validation + Jackson modules |
| Schema Validation | everit-json-schema (Draft 7) |
| Persistent Storage | RocksDB (GDPR key storage) |
| Caching | Caffeine (GDPR contexts, schema storage) |
| Utilities | Google Guava, semver4j |
| Build | Maven (parent `akces-framework-main`) |
| Test | JUnit 5, Mockito, Testcontainers (Kafka) |
| Testing | `cd main/shared && mvn test` |
| Full build | `mvn clean install -pl main/shared` |

### Internal Dependencies

| Artifact | Provides |
|----------|----------|
| `akces-api` | `Command`, `DomainEvent`, `ErrorEvent`, `AggregateState`, `@PIIData`, `@DomainEventInfo`, `@AggregateIdentifier`, `CommandType`, `DomainEventType`, `SchemaType`, `AggregateStateType`, `CommandBusHolder` |

### Downstream Consumers

| Module | Uses From Shared |
|--------|-----------------|
| `akces-runtime` | All subsystems: protocol records, GDPR repositories, schema registry, control records, Kafka utilities, serialization |
| `akces-client` | Schema registry, GDPR module, control records, protocol serde, schema serde, Kafka sender, host utils |
| `akces-query-support` | Protocol serde, GDPR context, schema registry |
| `akces-agentic` | Protocol records, control records, GDPR context |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        akces-shared                                  │
│                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐  │
│  │  protocol/    │  │  control/    │  │  schemas/                 │  │
│  │              │  │              │  │                           │  │
│  │ ProtocolRecord│  │ AkcesControl │  │ SchemaRegistry (iface)   │  │
│  │  (sealed)    │  │  Record      │  │ KafkaSchemaRegistry      │  │
│  │  ├Command    │  │  (sealed)    │  │ JsonSchema               │  │
│  │  ├DomainEvent│  │  └Aggregate  │  │                           │  │
│  │  ├AggState   │  │   Service    │  │ storage/                  │  │
│  │  ├GDPRKey    │  │   Record     │  │  SchemaStorage (iface)   │  │
│  │  └CmdResponse│  │              │  │  KafkaTopicSchemaStorage │  │
│  │              │  │ AkcesRegistry│  │                           │  │
│  │ SchemaRecord │  │  (iface)     │  │ diff/                     │  │
│  │ PayloadEnc   │  │              │  │  SchemaDiff              │  │
│  └──────────────┘  └──────────────┘  │  Difference              │  │
│                                       └───────────────────────────┘  │
│  ┌──────────────────────────────┐  ┌───────────────────────────┐    │
│  │  gdpr/                       │  │  serialization/           │    │
│  │                              │  │                           │    │
│  │ GDPRContext (sealed)         │  │ ProtocolRecordSerde       │    │
│  │  ├ NoopGDPRContext           │  │ AkcesControlRecordSerde   │    │
│  │  └ EncryptingGDPRContext     │  │ SchemaRecordSerde         │    │
│  │ GDPRContextHolder (TL)      │  │ BigDecimalSerializer      │    │
│  │ GDPRContextRepository (iface)│  └───────────────────────────┘    │
│  │  ├ InMemoryGDPRContextRepo  │                                    │
│  │  └ RocksDBGDPRContextRepo   │  ┌───────────────────────────┐    │
│  │ GDPRAnnotationUtils         │  │  util/                     │    │
│  │ GDPRKeyUtils                │  │  KafkaUtils               │    │
│  │                              │  │  KafkaSender              │    │
│  │ jackson/                     │  │  HostUtils                │    │
│  │  AkcesGDPRModule            │  │  EnvironmentProperties    │    │
│  │  PIIDataJson(De)Serializer  │  │   Printer                 │    │
│  │  PIIData(De)SerializerMod   │  └───────────────────────────┘    │
│  └──────────────────────────────┘                                    │
│                                       ┌───────────────────────────┐  │
│  ┌──────────────────────────────┐    │  kafka/                    │  │
│  │  errors/                     │    │  CustomKafkaConsumer       │  │
│  │  AggregateAlreadyExistsError │    │   Factory                 │  │
│  │  AggregateNotFoundError      │    │  CustomKafkaProducer       │  │
│  │  CommandExecutionError       │    │   Factory                 │  │
│  └──────────────────────────────┘    │  RecordAndMetadata        │  │
│                                       └───────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

## Project Structure

### Documentation (this feature)

```text
specs/shared-infrastructure/
├── spec.md              # Feature specification (migrated)
├── plan.md              # This file
└── tasks.md             # Task list (all complete)
```

### Source Code

```text
main/shared/
├── pom.xml
└── src/
    ├── main/java/org/elasticsoftware/akces/
    │   ├── protocol/                              # Wire-format protocol records (8 files)
    │   │   ├── PayloadEncoding.java               # Enum: JSON, PROTOBUF, BYTES
    │   │   ├── ProtocolRecord.java                # Sealed interface (5 permits)
    │   │   ├── CommandRecord.java                 # Command wire record (auto-UUID)
    │   │   ├── DomainEventRecord.java             # Event wire record (auto-UUID, generation)
    │   │   ├── AggregateStateRecord.java          # State snapshot record (generation)
    │   │   ├── CommandResponseRecord.java         # Response with events + encryption key
    │   │   ├── GDPRKeyRecord.java                 # GDPR encryption key record
    │   │   └── SchemaRecord.java                  # Schema definition record
    │   │
    │   ├── gdpr/                                  # GDPR/PII encryption framework (14 files)
    │   │   ├── GDPRContext.java                    # Sealed interface (encrypt/decrypt)
    │   │   ├── NoopGDPRContext.java                # Pass-through implementation
    │   │   ├── EncryptingGDPRContext.java          # AES-256 ECB/CBC encryption
    │   │   ├── GDPRContextHolder.java             # ThreadLocal context holder
    │   │   ├── GDPRContextRepository.java         # Repository interface (transactional)
    │   │   ├── GDPRContextRepositoryFactory.java  # Factory interface
    │   │   ├── GDPRContextRepositoryException.java # Repository exception
    │   │   ├── InMemoryGDPRContextRepository.java # HashMap + Caffeine implementation
    │   │   ├── InMemoryGDPRContextRepositoryFactory.java
    │   │   ├── RocksDBGDPRContextRepository.java  # RocksDB + Caffeine implementation
    │   │   ├── RocksDBGDPRContextRepositoryFactory.java
    │   │   ├── GDPRKeyUtils.java                  # Key generation + UUID validation
    │   │   ├── GDPRAnnotationUtils.java           # Recursive @PIIData detection
    │   │   └── jackson/                           # Jackson integration (4 files)
    │   │       ├── AkcesGDPRModule.java           # Jackson module registration
    │   │       ├── PIIDataJsonSerializer.java     # Encrypt on serialize
    │   │       ├── PIIDataJsonDeserializer.java   # Decrypt on deserialize
    │   │       ├── PIIDataSerializerModifier.java # @PIIData-aware serializer modifier
    │   │       └── PIIDataDeserializerModifier.java # @PIIData-aware deserializer modifier
    │   │
    │   ├── schemas/                               # JSON Schema registry (11 files)
    │   │   ├── SchemaRegistry.java                # Interface + static factory methods
    │   │   ├── KafkaSchemaRegistry.java           # Implementation with compatibility checking
    │   │   ├── JsonSchema.java                    # Schema wrapper (JsonNode + Everit)
    │   │   ├── SchemaException.java               # Base exception
    │   │   ├── SchemaNotFoundException.java
    │   │   ├── SchemaVersionNotFoundException.java
    │   │   ├── IncompatibleSchemaException.java
    │   │   ├── SchemaNotBackwardsCompatibleException.java
    │   │   ├── InvalidSchemaVersionException.java
    │   │   ├── PreviousSchemaVersionMissingException.java
    │   │   ├── storage/                           # Storage layer
    │   │   │   ├── SchemaStorage.java             # Interface (save/get/process)
    │   │   │   └── KafkaTopicSchemaStorage.java   # Kafka topic + Caffeine cache
    │   │   └── diff/                              # Schema comparison
    │   │       ├── SchemaDiff.java                # Recursive JSON Schema diff (738 lines)
    │   │       └── Difference.java                # Diff result with ~30 type enum values
    │   │
    │   ├── control/                               # Service discovery (6 files)
    │   │   ├── AkcesControlRecord.java            # Sealed interface (Jackson deduction)
    │   │   ├── AggregateServiceRecord.java        # Service metadata record
    │   │   ├── AggregateServiceType.java          # STANDARD vs AGENTIC enum
    │   │   ├── AggregateServiceCommandType.java   # Command type within a service
    │   │   ├── AggregateServiceDomainEventType.java # Event type within a service
    │   │   └── AkcesRegistry.java                 # Runtime routing interface
    │   │
    │   ├── kafka/                                 # Kafka factory utilities (3 files)
    │   │   ├── CustomKafkaConsumerFactory.java     # Sets GROUP_INSTANCE_ID
    │   │   ├── CustomKafkaProducerFactory.java     # Custom transactional producer
    │   │   └── RecordAndMetadata.java             # Record + Future<RecordMetadata> wrapper
    │   │
    │   ├── serialization/                         # Serde implementations (4 files)
    │   │   ├── ProtocolRecordSerde.java           # Protobuf serde for 5 ProtocolRecord types
    │   │   ├── AkcesControlRecordSerde.java       # JSON serde for AkcesControlRecord
    │   │   ├── SchemaRecordSerde.java             # Protobuf serde for SchemaRecord
    │   │   └── BigDecimalSerializer.java          # Jackson serializer (BigDecimal → String)
    │   │
    │   ├── errors/                                # Built-in error events (3 files)
    │   │   ├── AggregateAlreadyExistsErrorEvent.java
    │   │   ├── AggregateNotFoundErrorEvent.java
    │   │   └── CommandExecutionErrorEvent.java
    │   │
    │   └── util/                                  # Utilities (4 files)
    │       ├── KafkaUtils.java                    # Topic naming + compacted topic creation
    │       ├── KafkaSender.java                   # Async/sync producer send wrapper
    │       ├── HostUtils.java                     # Docker/InetAddress hostname resolution
    │       └── EnvironmentPropertiesPrinter.java  # Spring property logger
    │
    └── test/java/org/elasticsoftware/akces/
        ├── control/
        │   └── AggregateServiceRecordTest.java    # 7 tests: resolve agentic target, effectiveType
        ├── gdpr/
        │   ├── GDPRAnnotationUtilsTests.java      # 6 tests: PIIData detection across annotations
        │   ├── GDPRContextTests.java              # 6 tests: encrypt/decrypt, ECB, bad padding
        │   ├── GDPRKeyUtilsTests.java             # 7 tests: key creation, UUID validation
        │   ├── InMemoryGDPRContextRepositoryTests.java # 11 tests: CRUD, transactions, offsets
        │   ├── NoopGDPRContextTests.java          # 7 tests: pass-through behavior
        │   ├── RocksDBGDPRContextRepositoryTests.java # 1 test: transactional write
        │   └── jackson/
        │       └── AkcesGDPRModuleTests.java      # 2 tests: version generation
        ├── schemas/storage/
        │   └── KafkaTopicSchemaStorageTest.java   # 9 tests: CRUD, read-only mode, mocked Kafka
        ├── serialization/
        │   └── BigDecimalSerializerTests.java     # 8 tests: precision, trailing zeros, edge cases
        └── util/
            └── KafkaUtilsTests.java               # 5 tests: topic naming, compacted creation, quorum
```

## Key Design Decisions

### D-1: Sealed Interfaces for Type Safety

Both `ProtocolRecord` and `GDPRContext` use sealed interfaces to restrict the set of implementations. This enables exhaustive pattern matching and ensures the framework controls all wire-format types and encryption strategies.

### D-2: Protobuf for Protocol Records, JSON for Control Records

Protocol records (high-volume, low-latency) use Protobuf serialization via `jackson-dataformat-protobuf`. Control records (low-volume, human-readable) use JSON via standard Jackson. This balances throughput with debuggability.

### D-3: ThreadLocal for GDPR Context and Schema Generation

`GDPRContextHolder` uses `ThreadLocal` to propagate the encryption context through Jackson serialization without explicit parameter passing. `KafkaSchemaRegistry` uses `ThreadLocal<SchemaGenerator>` because the victools generator is not thread-safe. Both patterns align with the single-threaded controller architecture used by the runtime.

### D-4: Pluggable GDPR Key Storage

GDPR keys can be stored in-memory (for testing/development) or in RocksDB (for production). The `GDPRContextRepositoryFactory` pattern enables this pluggability while maintaining the transactional prepare/commit/rollback contract.

### D-5: Schema Diff for Compatibility Checking

Rather than relying on the victools generator for compatibility, a dedicated `SchemaDiff` utility performs recursive JSON Schema comparison. This provides fine-grained control over which changes are backward-compatible and which are breaking.

### D-6: Caffeine Caching Throughout

Both GDPR repositories and schema storage use Caffeine caches for hot entries. This provides high read throughput while backing stores (RocksDB, Kafka topics) provide durability.

## Integration Points

| System | Protocol | Direction |
|--------|----------|-----------|
| Kafka (`Akces-Control`) | `AkcesControlRecordSerde` (JSON) | Produce/Consume |
| Kafka (`Akces-Schemas`) | `SchemaRecordSerde` (Protobuf) | Produce/Consume |
| Kafka (`{Aggregate}-*` topics) | `ProtocolRecordSerde` (Protobuf) | Produce/Consume |
| Kafka (`{Aggregate}-GDPRKeys`) | `ProtocolRecordSerde` (Protobuf) | Produce/Consume |
| RocksDB | Native `rocksdbjni` | Read/Write |

## Risk & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| AES encryption key leakage | PII data exposure | Keys stored in dedicated Kafka topic and RocksDB; never included in domain events (only in `CommandResponseRecord` for client-side decrypt) |
| Schema registry unavailable at startup | Aggregate service cannot validate schemas | Deferred processing in downstream consumers; `SchemaNotFoundException` is recoverable |
| RocksDB corruption | GDPR keys lost | Keys also stored in Kafka compacted topic; repository can be rebuilt from topic |
| Protobuf schema evolution | Deserialization failure | `ProtocolRecordSerde` uses topic-based schema selection; protocol records are versioned |
| SchemaDiff false positives | Valid schemas rejected | `COMPATIBLE_CHANGES_STRICT` set is extensive; `forceRegisterOnIncompatibleSchema` override available |

## Test Strategy

| Layer | Tool | Scope |
|-------|------|-------|
| Unit | JUnit 5 | GDPR encryption, key generation, annotation utils, schema diff, BigDecimal serialization, Kafka utils |
| Unit (mocked) | Mockito | `KafkaTopicSchemaStorage` (mocked Kafka producer/consumer), `AggregateServiceRecord` routing |
| Integration | Testcontainers Kafka | `RocksDBGDPRContextRepository` transactional writes |
| Coverage | JaCoCo | See gap analysis in tasks.md |

## Complexity Tracking

No violations — single module, well-bounded scope, clear subsystem boundaries.
