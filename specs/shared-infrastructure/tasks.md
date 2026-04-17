# Shared Infrastructure — Tasks

> **Status:** Migrated (all tasks completed)
> **Module:** `main/shared/` (`akces-shared`)

## Tasks

### Path Conventions

- **Module root**: `main/shared/src/main/java/org/elasticsoftware/akces/`
- **Test root**: `main/shared/src/test/java/org/elasticsoftware/akces/`
- **Test command**: `cd main/shared && mvn test`
- **Full build**: `mvn clean install -pl main/shared`

---

## Phase 1: Protocol Records

**Goal**: Define the sealed wire-format record hierarchy for all Kafka message types.

- [x] **T-1.1** Define `PayloadEncoding` enum with `JSON`, `PROTOBUF`, `BYTES` values — `protocol/PayloadEncoding.java`
- [x] **T-1.2** Define `ProtocolRecord` sealed interface with `tenantId()`, `name()`, `version()`, `payload()`, `encoding()`, `aggregateId()`, `correlationId()` — permitting `CommandRecord`, `DomainEventRecord`, `AggregateStateRecord`, `GDPRKeyRecord`, `CommandResponseRecord` — `protocol/ProtocolRecord.java`
- [x] **T-1.3** Implement `CommandRecord` record with fields `id`, `tenantId`, `name`, `version`, `payload`, `encoding`, `aggregateId`, `correlationId`, `replyToTopicPartition`; convenience constructor auto-generates UUID `id` — `protocol/CommandRecord.java`
- [x] **T-1.4** Implement `DomainEventRecord` record with fields `id`, `tenantId`, `name`, `version`, `payload`, `encoding`, `aggregateId`, `correlationId`, `generation`; convenience constructor auto-generates UUID `id` — `protocol/DomainEventRecord.java`
- [x] **T-1.5** Implement `AggregateStateRecord` record with fields `tenantId`, `name`, `version`, `payload`, `encoding`, `aggregateId`, `correlationId`, `generation` — `protocol/AggregateStateRecord.java`
- [x] **T-1.6** Implement `CommandResponseRecord` record with fields `tenantId`, `name`, `version`, `payload`, `encoding`, `aggregateId`, `correlationId`, `commandId`, `events` (List\<DomainEventRecord\>), `encryptionKey` (byte\[\]); convenience constructor defaulting name/version/payload/encoding — `protocol/CommandResponseRecord.java`
- [x] **T-1.7** Implement `GDPRKeyRecord` record with fields `tenantId`, `name`, `version`, `payload`, `encoding`, `aggregateId`, `correlationId`; convenience constructor defaulting name to `"GDPRKey"` — `protocol/GDPRKeyRecord.java`
- [x] **T-1.8** Implement `SchemaRecord` record with fields `schemaName`, `version`, `schema` (JsonSchema), `registeredAt` — `protocol/SchemaRecord.java`

---

## Phase 2: GDPR/PII Encryption Framework

**Goal**: Implement transparent PII field encryption/decryption with pluggable key storage.

- [x] **T-2.1** Define `GDPRContext` sealed interface with `encrypt(String)`, `decrypt(String)`, `aggregateId()`, default `getEncryptionKey()` — permitting `NoopGDPRContext` and `EncryptingGDPRContext` — `gdpr/GDPRContext.java`
- [x] **T-2.2** Implement `NoopGDPRContext` record returning input unchanged for encrypt/decrypt — `gdpr/NoopGDPRContext.java`
- [x] **T-2.3** Implement `EncryptingGDPRContext` with AES-256 encryption (ECB for short values, CBC mode), Base64 URL-safe encoding, key from `byte[]` — `gdpr/EncryptingGDPRContext.java`
- [x] **T-2.4** Implement `GDPRContextHolder` with `ThreadLocal<GDPRContext>` static methods: `getCurrentGDPRContext()`, `setCurrentGDPRContext()`, `resetCurrentGDPRContext()` — `gdpr/GDPRContextHolder.java`
- [x] **T-2.5** Implement `GDPRKeyUtils` with `createKey()` (SecureRandom AES-256), `isUUID()` (UUID format validation) — `gdpr/GDPRKeyUtils.java`
- [x] **T-2.6** Implement `GDPRAnnotationUtils.hasPIIDataAnnotation(Class)` with recursive inspection of fields, methods, constructor parameters, and nested record types — `gdpr/GDPRAnnotationUtils.java`
- [x] **T-2.7** Define `GDPRContextRepository` interface with `getOffset()`, `prepare()`, `commit()`, `rollback()`, `process()`, `exists()`, `get()` — extending `Closeable` — `gdpr/GDPRContextRepository.java`
- [x] **T-2.8** Define `GDPRContextRepositoryFactory` interface for creating repository instances — `gdpr/GDPRContextRepositoryFactory.java`
- [x] **T-2.9** Define `GDPRContextRepositoryException` for repository errors — `gdpr/GDPRContextRepositoryException.java`
- [x] **T-2.10** Implement `InMemoryGDPRContextRepository` with `HashMap<String, byte[]>` storage, `Caffeine` cache for hot contexts, Kafka offset tracking — `gdpr/InMemoryGDPRContextRepository.java`
- [x] **T-2.11** Implement `InMemoryGDPRContextRepositoryFactory` — `gdpr/InMemoryGDPRContextRepositoryFactory.java`
- [x] **T-2.12** Implement `RocksDBGDPRContextRepository` with RocksDB transactional storage, Caffeine caching, Kafka offset tracking — `gdpr/RocksDBGDPRContextRepository.java`
- [x] **T-2.13** Implement `RocksDBGDPRContextRepositoryFactory` — `gdpr/RocksDBGDPRContextRepositoryFactory.java`
- [x] **T-2.14** Implement `AkcesGDPRModule` Jackson module registering `PIIDataSerializerModifier` and `PIIDataDeserializerModifier` with semver-based version detection — `gdpr/jackson/AkcesGDPRModule.java`
- [x] **T-2.15** Implement `PIIDataJsonSerializer` that encrypts string values via `GDPRContextHolder` — `gdpr/jackson/PIIDataJsonSerializer.java`
- [x] **T-2.16** Implement `PIIDataJsonDeserializer` that decrypts string values via `GDPRContextHolder` — `gdpr/jackson/PIIDataJsonDeserializer.java`
- [x] **T-2.17** Implement `PIIDataSerializerModifier` that detects `@PIIData` annotation on properties and wraps serializer — `gdpr/jackson/PIIDataSerializerModifier.java`
- [x] **T-2.18** Implement `PIIDataDeserializerModifier` that detects `@PIIData` annotation on properties and wraps deserializer — `gdpr/jackson/PIIDataDeserializerModifier.java`

---

## Phase 3: JSON Schema Registry and Compatibility Checking

**Goal**: Implement JSON Schema generation, Kafka-backed storage, and backward-compatibility enforcement.

- [x] **T-3.1** Implement `JsonSchema` class wrapping `JsonNode` raw schema + Everit `Schema` validator, with `version()`, `rawSchema()`, `validate()`, `deepEquals()` — `schemas/JsonSchema.java`
- [x] **T-3.2** Define `SchemaRegistry` interface with `validate(CommandType)`, `validate(DomainEventType)`, `registerAndValidate(SchemaType, boolean)`, `generateJsonSchema(SchemaType)`; static `createJsonSchemaGenerator()` factory and `generateJsonSchema(SchemaType, SchemaGenerator)` default — `schemas/SchemaRegistry.java`
- [x] **T-3.3** Implement `KafkaSchemaRegistry` with `SchemaStorage` backend, `ThreadLocal<SchemaGenerator>`, `validate()` (strict for commands, relaxed for events), `registerAndValidate()` with version sequencing and backward-compat checks, `forceRegisterOnIncompatibleSchema` override — `schemas/KafkaSchemaRegistry.java`
- [x] **T-3.4** Define `SchemaStorage` interface with `saveSchema()`, `getSchemas()`, `getSchema()`, `initialize()`, `process()`, `close()` — `schemas/storage/SchemaStorage.java`
- [x] **T-3.5** Implement `KafkaTopicSchemaStorage` with Kafka compacted topic (`Akces-Schemas`), `SchemaRecordSerde` for serialization, `Caffeine` cache, producer transactions, consumer polling — `schemas/storage/KafkaTopicSchemaStorage.java`
- [x] **T-3.6** Implement `SchemaDiff.compare()` with recursive JSON Schema comparison returning `List<Difference>`, covering property additions/removals, type changes, constraint changes, required-field changes; define `COMPATIBLE_CHANGES_STRICT` set — `schemas/diff/SchemaDiff.java`
- [x] **T-3.7** Define `Difference` class with `Type` enum (~30 difference types: `PROPERTY_ADDED_TO_OPEN_CONTENT_MODEL`, `PROPERTY_REMOVED_FROM_CLOSED_CONTENT_MODEL`, `TYPE_CHANGED`, etc.), path tracking, description — `schemas/diff/Difference.java`
- [x] **T-3.8** Implement `SchemaException` base exception — `schemas/SchemaException.java`
- [x] **T-3.9** Implement `SchemaNotFoundException` for missing schema subjects — `schemas/SchemaNotFoundException.java`
- [x] **T-3.10** Implement `SchemaVersionNotFoundException` for missing schema versions — `schemas/SchemaVersionNotFoundException.java`
- [x] **T-3.11** Implement `IncompatibleSchemaException` with violating differences list — `schemas/IncompatibleSchemaException.java`
- [x] **T-3.12** Implement `SchemaNotBackwardsCompatibleException` with breaking differences list — `schemas/SchemaNotBackwardsCompatibleException.java`
- [x] **T-3.13** Implement `InvalidSchemaVersionException` for non-sequential version registration — `schemas/InvalidSchemaVersionException.java`
- [x] **T-3.14** Implement `PreviousSchemaVersionMissingException` for version > 1 without prior versions — `schemas/PreviousSchemaVersionMissingException.java`

---

## Phase 4: Control Records and Service Discovery

**Goal**: Define aggregate service metadata and routing for the control plane.

- [x] **T-4.1** Define `AkcesControlRecord` sealed interface with `@JsonTypeInfo(use = DEDUCTION)` and `@JsonSubTypes` permitting `AggregateServiceRecord` — `control/AkcesControlRecord.java`
- [x] **T-4.2** Define `AggregateServiceType` enum: `STANDARD`, `AGENTIC` — `control/AggregateServiceType.java`
- [x] **T-4.3** Implement `AggregateServiceRecord` with fields `aggregateName`, `commandTopic`, `domainEventTopic`, `type`, `supportedCommands`, `producedEvents`, `consumedEvents`, `description`; implement `effectiveType()` defaulting null to `STANDARD` and static `resolveAgenticTarget()` — `control/AggregateServiceRecord.java`
- [x] **T-4.4** Implement `AggregateServiceCommandType` record with `typeName`, `version`, `create`, `schemaName`, `description` and `toExternalCommandType()` conversion — `control/AggregateServiceCommandType.java`
- [x] **T-4.5** Implement `AggregateServiceDomainEventType` record with `typeName`, `version`, `create`, `external`, `schemaName`, `description` and `toLocalDomainEventType()` conversion — `control/AggregateServiceDomainEventType.java`
- [x] **T-4.6** Define `AkcesRegistry` interface with `resolveType()`, `resolveTopic()`, `resolveTopics()`, `resolvePartition()` — `control/AkcesRegistry.java`

---

## Phase 5: Kafka Utilities and Serialization

**Goal**: Implement Kafka infrastructure utilities, custom factories, and serialization for all record types.

- [x] **T-5.1** Implement `ProtocolRecordSerde` with Protobuf serialization/deserialization for all 5 `ProtocolRecord` types, topic-based schema selection — `serialization/ProtocolRecordSerde.java`
- [x] **T-5.2** Implement `AkcesControlRecordSerde` with Jackson JSON serialization/deserialization for `AkcesControlRecord` — `serialization/AkcesControlRecordSerde.java`
- [x] **T-5.3** Implement `SchemaRecordSerde` with Protobuf serialization/deserialization for `SchemaRecord` using DTO conversion — `serialization/SchemaRecordSerde.java`
- [x] **T-5.4** Implement `BigDecimalSerializer` Jackson serializer writing `BigDecimal.toPlainString()` as JSON string — `serialization/BigDecimalSerializer.java`
- [x] **T-5.5** Implement `CustomKafkaConsumerFactory` extending `DefaultKafkaConsumerFactory` setting `GROUP_INSTANCE_ID` — `kafka/CustomKafkaConsumerFactory.java`
- [x] **T-5.6** Implement `CustomKafkaProducerFactory` extending `DefaultKafkaProducerFactory` for custom transactional producer creation — `kafka/CustomKafkaProducerFactory.java`
- [x] **T-5.7** Implement `RecordAndMetadata` generic record wrapping a record value with `Future<RecordMetadata>` — `kafka/RecordAndMetadata.java`
- [x] **T-5.8** Implement `KafkaUtils` with `getIndexTopicName()`, `createCompactedTopic()` (LZ4 compression, quorum-based min.insync.replicas, 20MB max message), `calculateQuorum()` — `util/KafkaUtils.java`
- [x] **T-5.9** Implement `KafkaSender.send()` static utility with immediate `FutureFailure` extraction, callback support, ApiException/RuntimeException unwrapping — `util/KafkaSender.java`
- [x] **T-5.10** Implement `HostUtils.getHostName()` with Docker `HOSTNAME` env fallback to `InetAddress.getLocalHost()` — `util/HostUtils.java`
- [x] **T-5.11** Implement `EnvironmentPropertiesPrinter` Spring `@EventListener` logging `akces.*`, `spring.*`, `management.*`, `server.*` properties on `ContextRefreshedEvent` — `util/EnvironmentPropertiesPrinter.java`
- [x] **T-5.12** Implement built-in error events: `AggregateAlreadyExistsErrorEvent(id, aggregateName)`, `AggregateNotFoundErrorEvent(id, aggregateName)`, `CommandExecutionErrorEvent(id, aggregateName, commandName, errorDescription)` — all implementing `ErrorEvent` with `@DomainEventInfo` — `errors/`

---

## Phase 6: Tests

**Goal**: Verify all subsystems with unit and integration tests.

### 6.1 GDPR Tests

- [x] **T-6.1** `GDPRContextTests` — 6 tests: encrypt/decrypt roundtrip, multiple values, ECB mode, bad padding handling, cross-context encryption — `gdpr/GDPRContextTests.java`
- [x] **T-6.2** `GDPRKeyUtilsTests` — 7 tests: key creation (256-bit), secure random, UUID validation (valid, uppercase, mixed case, invalid formats, null) — `gdpr/GDPRKeyUtilsTests.java`
- [x] **T-6.3** `GDPRAnnotationUtilsTests` — 6 tests: PII detection on fields, methods, constructor parameters, no-PII classes, nested PII, simple types — `gdpr/GDPRAnnotationUtilsTests.java`
- [x] **T-6.4** `NoopGDPRContextTests` — 7 tests: creation, encrypt returns original, decrypt returns original, null handling, roundtrip, aggregateId access — `gdpr/NoopGDPRContextTests.java`
- [x] **T-6.5** `InMemoryGDPRContextRepositoryTests` — 11 tests: initial offset, exists, get noop context, prepare/commit, get encrypting context, rollback, process, null record deletion, multiple commits, close, commit without prepare — `gdpr/InMemoryGDPRContextRepositoryTests.java`
- [x] **T-6.6** `RocksDBGDPRContextRepositoryTests` — 1 test: write in RocksDB transaction — `gdpr/RocksDBGDPRContextRepositoryTests.java`
- [x] **T-6.7** `AkcesGDPRModuleTests` — 2 tests: semver version generation, version from manifest — `gdpr/jackson/AkcesGDPRModuleTests.java`

### 6.2 Schema Tests

- [x] **T-6.8** `KafkaTopicSchemaStorageTest` — 9 tests: initialize, process polling, save schema (transactional), save failure, get not-found, get multiple versions, get empty, close, read-only mode — `schemas/storage/KafkaTopicSchemaStorageTest.java`

### 6.3 Serialization Tests

- [x] **T-6.9** `BigDecimalSerializerTests` — 8 tests: simple BigDecimal, trailing zeros preservation, scientific notation expansion, zero, negative, very large, very small, object context — `serialization/BigDecimalSerializerTests.java`

### 6.4 Control Tests

- [x] **T-6.10** `AggregateServiceRecordTest` — 7 tests: resolve agentic target by name, fallback to single standard, null on no match, null on multiple standard, prefer agentic over standard, effectiveType defaults to STANDARD, effectiveType returns explicit — `control/AggregateServiceRecordTest.java`

### 6.5 Utility Tests

- [x] **T-6.11** `KafkaUtilsTests` — 5 tests: index topic name, empty index key, compacted topic creation with configs, single replica, quorum calculation — `util/KafkaUtilsTests.java`

---

## Gap Analysis

### Test Coverage Gaps

| ID | Source File(s) | Description | Severity |
|----|---------------|-------------|----------|
| **GAP-T1** | `ProtocolRecordSerde.java` | No tests for Protobuf serialization/deserialization of any of the 5 `ProtocolRecord` types | High |
| **GAP-T2** | `AkcesControlRecordSerde.java` | No tests for JSON serialization/deserialization of `AkcesControlRecord` | Medium |
| **GAP-T3** | `SchemaRecordSerde.java` | No tests for Protobuf serialization/deserialization of `SchemaRecord` | Medium |
| **GAP-T4** | `KafkaSchemaRegistry.java` | No tests for schema validation, registration, backward-compatibility checking, or force-register logic | High |
| **GAP-T5** | `SchemaDiff.java` | No direct tests for recursive schema comparison (tested indirectly via `eventcatalog` module's `EventCatalogProcessorTest`) | Medium |
| **GAP-T6** | `JsonSchema.java` | No tests for schema wrapping, validation, or `deepEquals()` | Medium |
| **GAP-T7** | `EncryptingGDPRContext.java` | ECB/CBC mode selection and edge cases partially covered; no test for `getEncryptionKey()` method | Low |
| **GAP-T8** | `RocksDBGDPRContextRepository.java` | Only 1 test (transactional write); missing tests for `get()`, `exists()`, `process()`, `rollback()`, cache behavior | High |
| **GAP-T9** | `KafkaSender.java` | No tests for async send, callback handling, or exception unwrapping | Medium |
| **GAP-T10** | `HostUtils.java` | No tests for hostname resolution (Docker vs InetAddress fallback) | Low |
| **GAP-T11** | `EnvironmentPropertiesPrinter.java` | No tests for Spring event listener property logging | Low |
| **GAP-T12** | `CustomKafkaConsumerFactory.java`, `CustomKafkaProducerFactory.java` | No tests for custom Kafka factory behavior | Low |
| **GAP-T13** | Error events | No tests for `AggregateAlreadyExistsErrorEvent`, `AggregateNotFoundErrorEvent`, `CommandExecutionErrorEvent` record construction or `getAggregateId()` | Low |
| **GAP-T14** | `PIIDataJsonSerializer.java`, `PIIDataJsonDeserializer.java` | No direct unit tests; Jackson integration tested only via `AkcesGDPRModuleTests` (version tests, not serialization behavior) | Medium |

### Source Code Observations

| ID | Location | Description | Severity |
|----|----------|-------------|----------|
| **OBS-1** | `ProtocolRecordSerde.java` | Topic-based deserialization schema selection couples serde to Kafka topic naming conventions | Low |
| **OBS-2** | `EncryptingGDPRContext.java` | Uses AES/ECB mode for short values, which does not provide semantic security for repeated plaintexts | Low |
| **OBS-3** | `KafkaSchemaRegistry.java:149` | `relaxExternalValidation()` method on `SchemaType` controls validation strictness — undocumented behavior | Low |
