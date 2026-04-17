# Tasks: Event Sourcing Runtime

**Input**: Design documents from `specs/event-sourcing-runtime/`  
**Prerequisites**: plan.md (migrated), spec.md (migrated)  
**Status**: All tasks completed (brownfield migration — documenting existing implementation)

## Format: `[ID] [P?] [Phase] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- All tasks marked `[x]` — implementation exists in `main/runtime/`

---

## Phase 1: Core Controller and Application Bootstrap

**Purpose**: Spring Boot application entry point, controller lifecycle, and foundational bean definitions

- [x] T001 Implement `AggregateServiceApplication` Spring Boot application class with `@SpringBootApplication(exclude = KafkaAutoConfiguration.class)` and `@PropertySource("classpath:akces-aggregateservice.properties")` in `main/runtime/src/main/java/org/elasticsoftware/akces/AggregateServiceApplication.java`
- [x] T002 [P] Define Kafka consumer/producer factory beans (`aggregateServiceConsumerFactory`, `aggregateServiceProducerFactory`) using `CustomKafkaConsumerFactory`/`CustomKafkaProducerFactory` with `ProtocolRecordSerde`
- [x] T003 [P] Define control record consumer/producer factory beans (`aggregateServiceControlConsumerFactory`, `aggregateServiceControlProducerFactory`) using `AkcesControlRecordSerde`
- [x] T004 [P] Define schema record consumer/producer factory beans (`aggregateServiceSchemaConsumerFactory`, `aggregateServiceSchemaProducerFactory`) using `SchemaRecordSerde`
- [x] T005 [P] Define `aggregateServiceKafkaAdmin` bean for topic management via `KafkaAdmin`
- [x] T006 [P] Define `aggregateServiceJsonCustomizer` bean registering `BigDecimalSerializer` and `AkcesGDPRModule` on Jackson `ObjectMapper`
- [x] T007 [P] Define `aggregateServiceAggregateStateRepositoryFactory` bean backed by `RocksDBAggregateStateRepositoryFactory` with configurable `akces.rocksdb.baseDir`
- [x] T008 [P] Define `aggregateServiceGDPRContextRepositoryFactory` bean backed by `RocksDBGDPRContextRepositoryFactory`
- [x] T009 Implement `AkcesControllerState` enum with states: `INITIALIZING`, `INITIAL_REBALANCING`, `REBALANCING`, `RUNNING`, `SHUTTING_DOWN`, `ERROR` in `main/runtime/src/main/java/org/elasticsoftware/akces/AkcesControllerState.java`
- [x] T010 Implement `AkcesAggregateController` lifecycle manager in `main/runtime/src/main/java/org/elasticsoftware/akces/AkcesAggregateController.java` — consumes `Akces-Control` topic, manages `AggregatePartition` instances per assigned Kafka partition, handles rebalance via `ConsumerRebalanceListener`, coordinates startup/shutdown, publishes `AvailabilityChangeEvent` for Spring Boot liveness

**Checkpoint**: Application boots, connects to Kafka, reads control topic, and transitions through INITIALIZING → INITIAL_REBALANCING → RUNNING

---

## Phase 2: Kafka Partition Management

**Purpose**: Per-partition command processing loop, command routing, and transactional event production

- [x] T011 Implement `AggregatePartition` in `main/runtime/src/main/java/org/elasticsoftware/akces/kafka/AggregatePartition.java` — manages a single Kafka partition's lifecycle (state hydration from compacted topic, GDPR key loading, command consumption loop, transactional produce to domain-events + state topics, domain-event indexing for indexed aggregates)
- [x] T012 [P] Implement `AggregatePartitionState` enum with states: `INITIALIZING`, `INITIALIZING_STATE`, `LOADING_GDPR_KEYS`, `LOADING_STATE`, `PROCESSING`, `SHUTTING_DOWN` in `main/runtime/src/main/java/org/elasticsoftware/akces/kafka/AggregatePartitionState.java`
- [x] T013 [P] Implement `AggregatePartitionCommandBus` in `main/runtime/src/main/java/org/elasticsoftware/akces/kafka/AggregatePartitionCommandBus.java` — `CommandBus` implementation that produces command records to other aggregates' command topics (used by process managers for cross-aggregate commands)
- [x] T014 Implement `KafkaAggregateRuntime` in `main/runtime/src/main/java/org/elasticsoftware/akces/kafka/KafkaAggregateRuntime.java` — holds all handler function maps (command, event sourcing, event handler, event bridge, state upcasting, domain event upcasting), performs JSON Schema validation, invokes handlers, collects emitted events, handles create vs update semantics, manages GDPR context, and returns protocol records
- [x] T015 [P] Implement `AggregateRuntimeFactory` builder in `main/runtime/src/main/java/org/elasticsoftware/akces/kafka/AggregateRuntimeFactory.java` — constructs `KafkaAggregateRuntime` instances from discovered handler functions and aggregate metadata
- [x] T016 [P] Implement `PartitionUtils` in `main/runtime/src/main/java/org/elasticsoftware/akces/kafka/PartitionUtils.java` — topic naming conventions (`{Aggregate}-Commands`, `{Aggregate}-DomainEvents`, `{Aggregate}-AggregateState`), Murmur3-32 partition calculation via `Hashing.murmur3_32_fixed().hashString(id, UTF_8)`, and partition count helpers
- [x] T017 [P] Implement `AggregateRuntime` interface in `main/runtime/src/main/java/org/elasticsoftware/akces/aggregate/AggregateRuntime.java` — defines `handleCommandRecord()`, `handleExternalDomainEventRecord()`, `getName()`, `getDescription()`, `getAggregateClass()`, and handler lookup methods
- [x] T018 [P] Implement `IndexParams` record in `main/runtime/src/main/java/org/elasticsoftware/akces/aggregate/IndexParams.java` — holds `indexName`, `indexKey`, `createIndex` for domain-event indexing

**Checkpoint**: Commands are routed to correct partitions, processed transactionally, domain events produced, state persisted, and process-manager commands dispatched

---

## Phase 3: RocksDB State Repository

**Purpose**: Pluggable aggregate state persistence with production (RocksDB) and test (in-memory) implementations

- [x] T019 Define `AggregateStateRepository` interface in `main/runtime/src/main/java/org/elasticsoftware/akces/state/AggregateStateRepository.java` — `prepare()`, `commit()`, `rollback()`, `get()`, `process()`, `getOffset()`, extends `Closeable`
- [x] T020 [P] Define `AggregateStateRepositoryFactory` interface in `main/runtime/src/main/java/org/elasticsoftware/akces/state/AggregateStateRepositoryFactory.java` — `create(AggregateRuntime, Integer partitionId)`
- [x] T021 [P] Implement `AggregateStateRepositoryException` in `main/runtime/src/main/java/org/elasticsoftware/akces/state/AggregateStateRepositoryException.java`
- [x] T022 Implement `RocksDBAggregateStateRepository` in `main/runtime/src/main/java/org/elasticsoftware/akces/state/RocksDBAggregateStateRepository.java` — per-partition RocksDB instance, stores serialised `AggregateStateRecord` keyed by aggregate ID, tracks committed Kafka offset, supports prepare/commit/rollback for transactional consistency
- [x] T023 [P] Implement `RocksDBAggregateStateRepositoryFactory` in `main/runtime/src/main/java/org/elasticsoftware/akces/state/RocksDBAggregateStateRepositoryFactory.java` — creates `RocksDBAggregateStateRepository` with configured `baseDir` and `ProtocolRecordSerde`
- [x] T024 [P] Implement `InMemoryAggregateStateRepository` in `main/runtime/src/main/java/org/elasticsoftware/akces/state/InMemoryAggregateStateRepository.java` — test implementation using `ConcurrentHashMap`
- [x] T025 [P] Implement `InMemoryAggregateStateRepositoryFactory` in `main/runtime/src/main/java/org/elasticsoftware/akces/state/InMemoryAggregateStateRepositoryFactory.java`

**Checkpoint**: Aggregate state can be stored, retrieved, and recovered from Kafka state topic via both RocksDB and in-memory backends

---

## Phase 4: Bean Wiring and Annotation Scanning

**Purpose**: Auto-discovery of aggregates and handlers from Spring beans, function adapter registration

- [x] T026 Implement `AggregateBeanFactoryPostProcessor` in `main/runtime/src/main/java/org/elasticsoftware/akces/beans/AggregateBeanFactoryPostProcessor.java` — scans `ConfigurableListableBeanFactory` for beans with `@AggregateInfo` or `@AgenticAggregateInfo`, discovers `@CommandHandler`, `@EventSourcingHandler`, `@EventHandler`, `@EventBridgeHandler`, `@UpcastingHandler` methods, wraps them in typed adapters, registers beans, builds `AggregateRuntimeFactory` per aggregate, and registers `AkcesAggregateController` bean. Also implements `BeanFactoryInitializationAotProcessor` + `BeanRegistrationExcludeFilter` for GraalVM native-image support.
- [x] T027 [P] Implement `CommandHandlerFunctionAdapter` in `main/runtime/src/main/java/org/elasticsoftware/akces/beans/CommandHandlerFunctionAdapter.java` — wraps a `@CommandHandler` method into `CommandHandlerFunction<AggregateState, Command, DomainEvent>`, holding produced/error `DomainEventType` lists
- [x] T028 [P] Implement `EventSourcingHandlerFunctionAdapter` in `main/runtime/src/main/java/org/elasticsoftware/akces/beans/EventSourcingHandlerFunctionAdapter.java` — wraps `@EventSourcingHandler` into `EventSourcingHandlerFunction<AggregateState, DomainEvent>`
- [x] T029 [P] Implement `EventHandlerFunctionAdapter` in `main/runtime/src/main/java/org/elasticsoftware/akces/beans/EventHandlerFunctionAdapter.java` — wraps `@EventHandler` for external domain-event consumption (used by process managers)
- [x] T030 [P] Implement `EventBridgeHandlerFunctionAdapter` in `main/runtime/src/main/java/org/elasticsoftware/akces/beans/EventBridgeHandlerFunctionAdapter.java` — wraps `@EventBridgeHandler` for event-to-command bridging
- [x] T031 [P] Implement `AggregateValidator` in `main/runtime/src/main/java/org/elasticsoftware/akces/beans/AggregateValidator.java` — validates aggregate structure (handler method signatures, annotation consistency)
- [x] T032 [P] Implement `ProtocolRecordTypeValueCodeGeneratorDelegate` in `main/runtime/src/main/java/org/elasticsoftware/akces/beans/ProtocolRecordTypeValueCodeGeneratorDelegate.java` — AOT code generation for protocol record types

**Checkpoint**: Aggregates auto-discovered, all handlers wired as Spring beans, AkcesAggregateController registered

---

## Phase 5: Upcasting Support

**Purpose**: Transparent schema evolution for domain events and aggregate state

- [x] T033 Implement `AggregateStateUpcastingHandlerFunctionAdapter` in `main/runtime/src/main/java/org/elasticsoftware/akces/beans/AggregateStateUpcastingHandlerFunctionAdapter.java` — wraps `@UpcastingHandler` methods for state migration, holds source/target `AggregateStateType` metadata
- [x] T034 [P] Implement `DomainEventUpcastingHandlerFunctionAdapter` in `main/runtime/src/main/java/org/elasticsoftware/akces/beans/DomainEventUpcastingHandlerFunctionAdapter.java` — wraps `@UpcastingHandler` methods for domain event migration, holds source/target `DomainEventType` metadata
- [x] T035 Wire upcasting handlers into `KafkaAggregateRuntime` handler maps so that deserialisation of older versions automatically chains through upcasters to produce the current version

**Checkpoint**: Older event/state versions are transparently upcasted; `WalletState` v1→v2 and `AccountCreatedEvent` v1→v2 both resolve correctly

---

## Phase 6: Integration Tests (Testcontainers)

**Purpose**: End-to-end validation with real Kafka broker via Testcontainers

### Test Infrastructure

- [x] T036 Implement `TestUtils` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/TestUtils.java` — Kafka topic creation (commands, domain-events, state, control, schema, GDPR-keys topics), schema registration, aggregate service record preparation
- [x] T037 [P] Implement `RuntimeConfiguration` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/RuntimeConfiguration.java` — all-aggregate Spring test configuration scanning `org.elasticsoftware.akcestest.aggregate`
- [x] T038 [P] Implement `WalletConfiguration` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/WalletConfiguration.java` — Wallet-only test config with mock `SchemaStorage`
- [x] T039 [P] Implement `AccountConfiguration` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/AccountConfiguration.java` — Account-only test config
- [x] T040 [P] Create test resources `test-application.yaml` and `akces-client.properties`

### Test Aggregates (Fixtures)

- [x] T041 Implement Wallet test aggregate (16 files) in `main/runtime/src/test/java/org/elasticsoftware/akcestest/aggregate/wallet/` — `Wallet.java`, `WalletState.java`, `WalletStateV2.java`, commands (`CreateWallet`, `CreditWallet`, `CreateBalance`, `ReserveAmount`), events (`WalletCreated`, `BalanceCreated`, `WalletCredited`, `AmountReserved`), error events (`InvalidAmount`, `InvalidCurrency`, `InsufficientFunds`, `BalanceAlreadyExists`), external event (`ExternalAccountCreatedEvent`, `InvalidAccountCreatedEvent`)
- [x] T042 [P] Implement Account test aggregate (6 files) in `main/runtime/src/test/java/org/elasticsoftware/akcestest/aggregate/account/` — `Account.java`, `AccountState.java`, `PreviousAccountState.java`, `CreateAccountCommand.java`, `AccountCreatedEvent.java`, `AccountCreatedEventV2.java`
- [x] T043 [P] Implement OrderProcessManager test aggregate (9 files) in `main/runtime/src/test/java/org/elasticsoftware/akcestest/aggregate/orders/` — `OrderProcessManager.java`, `OrderProcessManagerState.java`, `OrderProcess.java`, `BuyOrderProcess.java`, `FxMarket.java`, `PlaceBuyOrderCommand.java`, `UserOrderProcessesCreatedEvent.java`, `BuyOrderCreatedEvent.java`, `BuyOrderPlacedEvent.java`, `BuyOrderRejectedEvent.java`
- [x] T044 [P] Create legacy schema versions in `main/runtime/src/test/java/org/elasticsoftware/akcestest/old/` for backward-compatibility testing

### Integration Tests

- [x] T045 Implement `RuntimeTests` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/RuntimeTests.java` — full Testcontainers Kafka integration: `testKafkaAdminClient`, `waitForControllersToStart`, `testAkcesControl` (create wallet, credit, error handling), `testBatchedCommands` (10 concurrent creates), `testCreateViaExternalDomainEvent` (Account → Wallet), `testGDPREncryption` (PII field verification), `testDomainEventIndexing`, `testDomainEventIndexingWithErrorEvents`
- [x] T046 [P] Implement `AggregateServiceApplicationTests` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/AggregateServiceApplicationTests.java` — single-aggregate (Account) Testcontainers test verifying aggregate loading and controller initialization

### Unit Tests

- [x] T047 Implement `WalletTests` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/WalletTests.java` — bean discovery verification (4 command handlers, 4 event sourcing handlers, 1 event handler, 1 upcasting handler), JSON Schema validation (missing schema, valid schemas, pre-loaded schemas, external event subset), multi-version schema registration (v1→v2→v3, skipped version error, non-compatible error), command handling (create wallet by command, create by external event), event indexing
- [x] T048 [P] Implement `AccountTests` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/AccountTests.java` — schema evolution test (`PreviousAccountState` → `AccountState` backward-compatible deserialization with default `twoFactorEnabled = false`)
- [x] T049 [P] Implement `JsonSchemaTests` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/schemas/JsonSchemaTests.java` — schema compatibility (`AccountCreatedEvent` v1→v2→v3 via `SchemaDiff.compare()`), nullable field validation, non-null field validation, BigDecimal command serialization, schema rendering
- [x] T050 [P] Implement schema fixture classes in `main/runtime/src/test/java/org/elasticsoftware/akcestest/schemas/` — `AccountCreatedEvent.java`, `AccountCreatedEventV2.java`, `AccountCreatedEventV3.java`, `NotCompatibleAccountCreatedEventV4.java`, `AccountTypeV1.java`, `AccountTypeV2.java`, `CreditWalletCommand.java`
- [x] T051 [P] Implement `ProtocolTests` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/protocol/ProtocolTests.java` — Protobuf serialization/deserialization of `DomainEventRecord`, `CommandRecord`, `AggregateStateRecord`, `CommandResponseRecord`; protobuf schema generation
- [x] T052 [P] Implement `RocksDBAggregateStateRepositoryTests` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/state/RocksDBAggregateStateRepositoryTests.java` — RocksDB create, single update with offset tracking, Mockito-mocked producer response
- [x] T053 [P] Implement `AkcesAggregateControllerTests` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/control/AkcesAggregateControllerTests.java` — `AggregateServiceRecord` serialization, deserialization (including legacy format without "type" field), full serde round-trip

### Utility Tests

- [x] T054 [P] Implement `MinInsyncReplicasTest` in `main/runtime/src/test/java/org/elasticsoftware/akces/beans/MinInsyncReplicasTest.java` — quorum calculation for replication factors 1–10
- [x] T055 [P] Implement `AotServicesTest` in `main/runtime/src/test/java/org/elasticsoftware/akces/beans/AotServicesTest.java` — AOT service loading verification for GraalVM
- [x] T056 [P] Implement `HostUtilsTests` in `main/runtime/src/test/java/org/elasticsoftware/akcestest/util/HostUtilsTests.java` — hostname retrieval

**Checkpoint**: `cd main/runtime && mvn test` — all tests pass

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T057 Verify `mvn clean install` from repository root passes with the runtime module
- [x] T058 [P] Verify Protobuf wire format is consistent across all protocol record types
- [x] T059 [P] Verify `@PIIData` annotation coverage on `AccountState` PII fields (firstName, lastName, email)

---

## Gaps: Areas with Thin Test Coverage

The following areas have limited or no dedicated test coverage and represent opportunities for improvement:

- **Gap-001**: `AggregatePartition` partition rebalance resilience — no dedicated test simulates a Kafka rebalance mid-transaction to verify state rollback and recovery. RuntimeTests verifies steady-state but not failure during rebalance.
- **Gap-002**: `RocksDBAggregateStateRepository` concurrent access — only single-threaded tests exist (`testCreate`, `testSingleUpdate`). No tests verify concurrent prepare/commit from multiple threads or recovery after RocksDB corruption.
- **Gap-003**: `AggregatePartitionCommandBus` cross-aggregate command delivery — process manager command dispatch to other aggregates' topics is tested indirectly via `RuntimeTests` but has no isolated unit test.
- **Gap-004**: `KafkaAggregateRuntime` error paths — while error events are tested (invalid amount, insufficient funds), edge cases like handler methods throwing unchecked exceptions, serialization failures during event production, and null aggregate state in non-create handlers are not individually tested.
- **Gap-005**: `AggregateBeanFactoryPostProcessor` negative cases — no tests for aggregates with missing handler methods, duplicate create handlers, or incompatible method signatures. Only happy-path scanning is verified.
- **Gap-006**: `InMemoryAggregateStateRepository` — no dedicated test class exists; it is only exercised indirectly by `WalletTests`.
- **Gap-007**: `AggregatePartition` GDPR key loading failure — no test for what happens when GDPR key topic is unavailable or contains corrupt data during partition initialization.
- **Gap-008**: Multi-version upcasting chain — tests verify single-hop upcasting (v1→v2) but not multi-hop chains (v1→v2→v3) at runtime.
- **Gap-009**: `PartitionUtils` topic naming and partition calculation — no dedicated unit tests; relied upon indirectly by integration tests.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Bootstrap)**: No dependencies — foundation
- **Phase 2 (Kafka Partitions)**: Depends on Phase 1 (controller) and Phase 3 (state repository)
- **Phase 3 (State Repository)**: No dependencies — can run in parallel with Phase 1
- **Phase 4 (Bean Wiring)**: Depends on Phase 2 (needs `AggregateRuntimeFactory` and `AkcesAggregateController`)
- **Phase 5 (Upcasting)**: Depends on Phase 4 (adapters registered via bean post-processor)
- **Phase 6 (Tests)**: Depends on all previous phases
- **Phase 7 (Polish)**: Depends on Phase 6

### Parallel Opportunities

- Phases 1 and 3 can proceed in parallel (independent concerns)
- Within each phase, tasks marked `[P]` can run in parallel
- Test fixture creation (T041–T044) can all run in parallel
- All unit tests (T047–T056) can run in parallel
