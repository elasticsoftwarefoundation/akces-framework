# Feature Specification: Event Sourcing Runtime

**Module**: `main/runtime` (`akces-runtime`)  
**Created**: 2025-07-15  
**Status**: Migrated  
**Source**: Reverse-engineered from 27 source files (~4 810 lines) and 59 test files (~4 607 lines)

## User Scenarios & Testing

### User Story 1 – Process a Command and Emit Domain Events (Priority: P1)

A developer sends a command (e.g. `CreateWalletCommand`) to the Akces runtime. The runtime locates the correct aggregate, validates the command against its JSON Schema, invokes the `@CommandHandler` method, produces one or more domain events to Kafka, updates aggregate state via the `@EventSourcingHandler`, and persists the new state snapshot.

**Why this priority**: This is the fundamental write path of the entire framework — without it nothing else works.

**Independent Test**: Send a `CreateWalletCommand` via `AkcesClient`, verify the response contains the expected `WalletCreatedEvent` and `BalanceCreatedEvent`, and confirm aggregate state is persisted.

**Acceptance Scenarios**:

1. **Given** no aggregate exists for the aggregate ID, **When** a creation command (`create = true`) is sent, **Then** the runtime emits the expected domain event(s) and persists initial aggregate state.
2. **Given** an aggregate exists, **When** a regular command is sent (e.g. `CreditWalletCommand`), **Then** the runtime loads current state, invokes the handler, emits domain events, and updates state.
3. **Given** an aggregate exists, **When** a creation command is sent for an existing aggregate ID, **Then** an `AggregateAlreadyExistsErrorEvent` is emitted instead.
4. **Given** no aggregate exists, **When** a non-creation command is sent, **Then** an `AggregateNotFoundErrorEvent` is emitted.
5. **Given** a command with invalid data, **When** sent to the runtime, **Then** a `CommandExecutionErrorEvent` is emitted (no exception thrown).
6. **Given** business validation fails inside a command handler, **When** the handler returns an error event (e.g. `InvalidAmountErrorEvent`), **Then** only the error event is produced and state is unchanged.

---

### User Story 2 – Manage Aggregate State with RocksDB and Event Replay (Priority: P1)

The runtime maintains local aggregate state using a pluggable `AggregateStateRepository`. In production, `RocksDBAggregateStateRepository` stores snapshots keyed by aggregate ID per Kafka partition. On partition assignment, state is bulk-loaded from the compacted Kafka state topic and cached in RocksDB for low-latency reads.

**Why this priority**: State management is inseparable from command processing — every command handler requires current state.

**Independent Test**: Create an aggregate, credit it, then restart the partition; verify state is recovered from the Kafka state topic and matches the pre-restart snapshot.

**Acceptance Scenarios**:

1. **Given** a new partition is assigned, **When** the partition initialises, **Then** all `AggregateStateRecord` entries from the compacted state topic are loaded into RocksDB.
2. **Given** a command produces new state, **When** the Kafka transaction commits, **Then** the state record is persisted to both the Kafka state topic and the local RocksDB store.
3. **Given** a Kafka transaction fails, **When** a rollback occurs, **Then** the prepared RocksDB state is discarded (no partial writes).
4. **Given** no state exists for an aggregate ID, **When** queried, **Then** `null` is returned.
5. **Given** a partition is revoked, **When** the runtime shuts down the partition, **Then** the RocksDB instance is closed cleanly.

---

### User Story 3 – Route Commands to Correct Kafka Partition (Priority: P1)

Every command carries an `@AggregateIdentifier` field. The runtime hashes this identifier using Murmur3-32 to deterministically map commands to a Kafka partition, guaranteeing that all commands for the same aggregate land on the same partition and are processed in order.

**Why this priority**: Correct partitioning is a hard consistency requirement; mis-routing causes data corruption.

**Independent Test**: Send 10 commands for distinct aggregate IDs; verify each is processed by the partition that owns that ID via the Murmur3 hash function.

**Acceptance Scenarios**:

1. **Given** a command with a known aggregate ID, **When** the partition is computed, **Then** the result equals `Hashing.murmur3_32_fixed().hashString(id, UTF_8).asInt() % partitionCount` (positive modulo).
2. **Given** a batch of commands, **When** processed, **Then** each command is handled by the `AggregatePartition` that owns its aggregate ID.
3. **Given** a partition rebalance occurs, **When** partitions are revoked and reassigned, **Then** commands resume processing on the new owner without loss.

---

### User Story 4 – Wire Aggregates Automatically via @AggregateInfo Scanning (Priority: P1)

The `AggregateBeanFactoryPostProcessor` scans the Spring `ApplicationContext` for beans annotated with `@AggregateInfo` (or `@AgenticAggregateInfo`), discovers their `@CommandHandler`, `@EventSourcingHandler`, `@EventHandler`, `@EventBridgeHandler`, and `@UpcastingHandler` methods, wraps each in a typed function adapter, and registers them as Spring beans. It then assembles an `AggregateRuntimeFactory` per aggregate and registers an `AkcesAggregateController` that manages the aggregate lifecycle.

**Why this priority**: Without auto-wiring, developers would need to manually register every handler — the framework would be unusable.

**Independent Test**: Load a Spring context with the `Wallet` aggregate; verify 4 `CommandHandlerFunction` beans, 4 `EventSourcingHandlerFunction` beans, 1 `EventHandlerFunction` bean, and 1 `UpcastingHandlerFunction` bean are registered.

**Acceptance Scenarios**:

1. **Given** a class annotated with `@AggregateInfo`, **When** the Spring context starts, **Then** all handler methods are detected and adapter beans are registered.
2. **Given** a `@CommandHandler(create = true)` method, **When** scanned, **Then** it is wired as the aggregate's creation command handler.
3. **Given** a `@CommandHandler(produces = {…}, errors = {…})` method, **When** scanned, **Then** the produced and error `DomainEventType` lists are populated, including system error events (`AggregateAlreadyExistsErrorEvent`, `AggregateNotFoundErrorEvent`, `CommandExecutionErrorEvent`).
4. **Given** an `@EventSourcingHandler(create = true)` method, **When** scanned, **Then** it is wired as the aggregate's state creation handler.
5. **Given** handler methods with invalid signatures, **When** the bean post-processor runs, **Then** an `ApplicationContextException` is thrown with a descriptive message.
6. **Given** GraalVM native-image compilation, **When** AOT processing runs, **Then** `BeanFactoryInitializationAotContribution` generates the required reflection hints.

---

### User Story 5 – Schema Upcasting for Backward-Compatible Event Evolution (Priority: P2)

When a domain event or aggregate state schema evolves (e.g. `AccountCreatedEvent` v1 → `AccountCreatedEventV2`), the developer writes an `@UpcastingHandler` method. At deserialization time, the runtime detects the older version, invokes the upcaster chain, and presents the handler with the latest schema version — all transparent to business logic.

**Why this priority**: Schema evolution is essential for production systems but not required for initial operation.

**Independent Test**: Serialise a `WalletState` v1 record, upcast to `WalletStateV2` via the registered handler, verify that `reservedAmounts` map is converted to the `reservations` list structure.

**Acceptance Scenarios**:

1. **Given** an event stored at version N, **When** the runtime deserialises it, **Then** it applies the registered upcasting chain to produce version N+M.
2. **Given** an aggregate state stored at an older version, **When** the runtime loads it from RocksDB, **Then** the state upcasting handler converts it to the current version.
3. **Given** a domain event upcasting handler, **When** registered, **Then** `DomainEventUpcastingHandlerFunctionAdapter` wraps it with source/target `DomainEventType` metadata.
4. **Given** a state upcasting handler, **When** registered, **Then** `AggregateStateUpcastingHandlerFunctionAdapter` wraps it with source/target `AggregateStateType` metadata.
5. **Given** a new schema version is registered that is not backward-compatible, **When** validated, **Then** a `SchemaNotBackwardsCompatibleException` is thrown.

---

### User Story 6 – Coordinate Cross-Aggregate Workflows via Process Managers (Priority: P2)

A process manager aggregate (e.g. `OrderProcessManager`) reacts to external domain events from other aggregates, maintains its own state via event sourcing, and emits commands to other aggregates to drive multi-step workflows. The runtime handles external event consumption (`@EventHandler`), event-to-command bridging (`@EventBridgeHandler`), and state management identically to regular aggregates.

**Why this priority**: Process managers enable complex business workflows but are built on top of the core command/event path.

**Independent Test**: Create an `Account`, verify `OrderProcessManager` receives the `AccountCreatedEvent` and creates its own state; then place a buy order, verify `ReserveAmountCommand` is sent to the `Wallet` aggregate.

**Acceptance Scenarios**:

1. **Given** an external `AccountCreatedEvent` is emitted, **When** the `OrderProcessManager` receives it, **Then** it creates its own `OrderProcessManagerState` via the `@EventHandler(create = true)` method.
2. **Given** a `PlaceBuyOrderCommand` is sent, **When** the process manager handles it, **Then** it emits a `BuyOrderCreatedEvent` and sends a `ReserveAmountCommand` to the `Wallet` aggregate via the `CommandBus`.
3. **Given** the `Wallet` aggregate emits an `AmountReservedEvent`, **When** the process manager receives it, **Then** it emits a `BuyOrderPlacedEvent` confirming the order.
4. **Given** the `Wallet` emits an `InsufficientFundsErrorEvent`, **When** the process manager receives it, **Then** it emits a `BuyOrderRejectedEvent`.

---

### Edge Cases

- What happens when a command handler throws an unchecked exception instead of returning an error event? → A `CommandExecutionErrorEvent` is emitted.
- How does the runtime handle a Kafka transaction commit failure? → State is rolled back via `AggregateStateRepository.rollback()` and the consumer re-reads the offset.
- What happens when RocksDB is corrupt on startup? → An `AggregateStateRepositoryException` is thrown; the partition fails to initialise and the controller enters `ERROR` state.
- How does the system handle duplicate commands? → Kafka's exactly-once semantics (idempotent producer + transactional processing) prevent duplicates.
- What happens when an unknown command type arrives? → The runtime returns a `CommandExecutionErrorEvent` indicating the command type is unrecognised.

## Requirements

### Functional Requirements

- **FR-001**: System MUST accept commands via a Kafka commands topic, route them to the correct partition using Murmur3 hashing of the `@AggregateIdentifier`, and process them exactly once within a Kafka transaction.
- **FR-002**: System MUST invoke `@CommandHandler` methods reflectively, passing current aggregate state, and collect emitted `DomainEvent` instances into the Kafka domain-events topic.
- **FR-003**: System MUST invoke `@EventSourcingHandler` methods for each emitted domain event to produce updated aggregate state, and persist the state snapshot to both the Kafka state topic and the local state repository.
- **FR-004**: System MUST validate every inbound command against its registered JSON Schema before invoking the command handler; invalid commands MUST produce a `CommandExecutionErrorEvent`.
- **FR-005**: System MUST support pluggable `AggregateStateRepository` implementations — `RocksDBAggregateStateRepository` for production, `InMemoryAggregateStateRepository` for testing.
- **FR-006**: System MUST reload aggregate state from the compacted Kafka state topic into the local repository on partition assignment (state hydration).
- **FR-007**: System MUST support domain-event and aggregate-state upcasting via `@UpcastingHandler` methods, applying a version chain transparently at deserialisation time.
- **FR-008**: System MUST auto-discover aggregates and their handlers from Spring beans annotated with `@AggregateInfo` or `@AgenticAggregateInfo` using `AggregateBeanFactoryPostProcessor`.
- **FR-009**: System MUST register handler function adapters (`CommandHandlerFunctionAdapter`, `EventSourcingHandlerFunctionAdapter`, `EventHandlerFunctionAdapter`, `EventBridgeHandlerFunctionAdapter`, `AggregateStateUpcastingHandlerFunctionAdapter`, `DomainEventUpcastingHandlerFunctionAdapter`) as Spring beans.
- **FR-010**: System MUST manage controller lifecycle through states: `INITIALIZING` → `INITIAL_REBALANCING` → `RUNNING` (steady state), with `REBALANCING` on partition reassignment and `SHUTTING_DOWN` / `ERROR` for terminal states.
- **FR-011**: System MUST manage partition lifecycle through states: `INITIALIZING` → `INITIALIZING_STATE` → `LOADING_GDPR_KEYS` → `LOADING_STATE` → `PROCESSING` → `SHUTTING_DOWN`.
- **FR-012**: System MUST support GDPR/PII encryption for fields annotated with `@PIIData`, loading encryption keys from a dedicated Kafka topic per partition.
- **FR-013**: System MUST support process managers that consume external domain events and emit commands to other aggregates via `CommandBus`.
- **FR-014**: System MUST support domain-event indexing for aggregates marked with `indexed = true`, producing index records to per-aggregate-ID index topics.
- **FR-015**: System MUST publish `AggregateServiceRecord` control records to the `Akces-Control` topic describing the aggregate's commands, produced events, and consumed events.
- **FR-016**: System MUST support GraalVM native-image compilation via AOT processing in `AggregateBeanFactoryPostProcessor`.
- **FR-017**: System MUST validate JSON Schemas for backward compatibility when registering new event/command versions, throwing `SchemaNotBackwardsCompatibleException` on violations.
- **FR-018**: System MUST use Protobuf as the wire format for `ProtocolRecord` types (`CommandRecord`, `DomainEventRecord`, `AggregateStateRecord`, `CommandResponseRecord`) on Kafka topics.

### Key Entities

- **`AkcesAggregateController`**: Top-level lifecycle manager. Consumes the `Akces-Control` topic, creates `AggregatePartition` instances per assigned Kafka partition, and coordinates startup/shutdown.
- **`AggregatePartition`**: Manages a single Kafka partition. Owns a consumer/producer pair, an `AggregateStateRepository`, and a `GDPRContextRepository`. Processes commands and external events in a transactional loop.
- **`KafkaAggregateRuntime`**: Implements `AggregateRuntime`. Holds all handler maps (command, event sourcing, event bridge, event handler, upcasting) for a single aggregate type. Performs command validation, handler invocation, and event production.
- **`AggregateRuntimeFactory`**: Builder for `KafkaAggregateRuntime` instances. Constructed by `AggregateBeanFactoryPostProcessor` with all discovered handler functions.
- **`AggregateBeanFactoryPostProcessor`**: Spring `BeanFactoryPostProcessor`. Scans for `@AggregateInfo` beans, discovers handler methods, wraps them in adapters, and registers the `AkcesAggregateController` bean.
- **`AggregateStateRepository`**: Interface for per-partition state storage. Methods: `prepare()`, `commit()`, `rollback()`, `get()`, `process()`, `getOffset()`.
- **`RocksDBAggregateStateRepository`**: Production implementation backed by RocksDB. One instance per aggregate per partition.
- **`InMemoryAggregateStateRepository`**: Test implementation backed by `ConcurrentHashMap`.

## Success Criteria

### Measurable Outcomes

- **SC-001**: All 8 primary integration/unit test classes pass (RuntimeTests, WalletTests, AccountTests, AggregateServiceApplicationTests, JsonSchemaTests, ProtocolTests, RocksDBAggregateStateRepositoryTests, AkcesAggregateControllerTests) via `cd main/runtime && mvn test`.
- **SC-002**: A Wallet aggregate can be created, credited, and reserved within a single integration test using Testcontainers Kafka (demonstrated by `RuntimeTests.testAkcesControl`).
- **SC-003**: GDPR-encrypted PII fields in `AccountState` are not readable in raw Kafka messages (demonstrated by `RuntimeTests.testGDPREncryption`).
- **SC-004**: Schema evolution from `AccountCreatedEvent` v1 → v2 is handled transparently via upcasting (demonstrated by `AccountTests.testAggregateStateSerializationWithChangingSchema`).
- **SC-005**: Batched commands (10 concurrent wallet creations) complete within a single test run (demonstrated by `RuntimeTests.testBatchedCommands`).
- **SC-006**: The `OrderProcessManager` successfully coordinates a multi-aggregate workflow (Account → Wallet → Order) in the integration test.

## Assumptions

- Apache Kafka is running in KRaft mode (no ZooKeeper dependency).
- Kafka topics are pre-created with the expected partition count (3 in tests) before the controller starts.
- The `Akces-Control` topic and `Akces-Schemas` topic exist and are populated before aggregate controllers initialise.
- RocksDB native libraries are available on the runtime classpath.
- JSON Schema Draft 7 is used for all schema validation and compatibility checks.
- The Protobuf wire format is stable and versioned; protocol schema changes require coordinated releases.

---

## CQRS / Event Sourcing Design

### Commands

| Command | `@CommandInfo type` | Version | Aggregate | `create = true`? |
|---------|---------------------|---------|-----------|-----------------|
| `CreateWalletCommand` | `"CreateWallet"` | 1 | `Wallet` | Yes |
| `CreditWalletCommand` | `"CreditWallet"` | 1 | `Wallet` | No |
| `CreateBalanceCommand` | `"CreateBalance"` | 1 | `Wallet` | No |
| `ReserveAmountCommand` | `"ReserveAmount"` | 1 | `Wallet` | No |
| `CreateAccountCommand` | `"CreateAccount"` | 1 | `Account` | Yes |
| `PlaceBuyOrderCommand` | `"PlaceBuyOrder"` | 1 | `OrderProcessManager` | No |

### Domain Events

| Event | `@DomainEventInfo type` | Version | Emitted by | Is error event? |
|-------|-------------------------|---------|-----------|----------------|
| `WalletCreatedEvent` | `"WalletCreated"` | 1 | `CreateWalletCommand` | No |
| `BalanceCreatedEvent` | `"BalanceCreated"` | 1 | `CreateWalletCommand` | No |
| `WalletCreditedEvent` | `"WalletCredited"` | 1 | `CreditWalletCommand` | No |
| `AmountReservedEvent` | `"AmountReserved"` | 1 | `ReserveAmountCommand` | No |
| `InvalidAmountErrorEvent` | `"InvalidAmountError"` | 1 | `CreditWalletCommand` | Yes |
| `InvalidCurrencyErrorEvent` | `"InvalidCurrencyError"` | 1 | `ReserveAmountCommand` | Yes |
| `InsufficientFundsErrorEvent` | `"InsufficientFundsError"` | 1 | `ReserveAmountCommand` | Yes |
| `BalanceAlreadyExistsErrorEvent` | `"BalanceAlreadyExistsError"` | 1 | `CreateBalanceCommand` | Yes |
| `AccountCreatedEvent` | `"AccountCreated"` | 1 | `CreateAccountCommand` | No |
| `AccountCreatedEventV2` | `"AccountCreated"` | 2 | Upcasted from v1 | No |
| `UserOrderProcessesCreatedEvent` | `"UserOrderProcessesCreated"` | 1 | External `AccountCreatedEvent` | No |
| `BuyOrderCreatedEvent` | `"BuyOrderCreated"` | 1 | `PlaceBuyOrderCommand` | No |
| `BuyOrderPlacedEvent` | `"BuyOrderPlaced"` | 1 | External `AmountReservedEvent` | No |
| `BuyOrderRejectedEvent` | `"BuyOrderRejected"` | 1 | External error events | No |

### Aggregate / State Impact

- **Affected Aggregate(s)**: `Wallet` (state: `WalletStateV2`), `Account` (state: `AccountState`), `OrderProcessManager` (state: `OrderProcessManagerState`)
- **State changes**: All state is reconstructed from domain events via `@EventSourcingHandler` methods. State records are immutable Java records implementing `AggregateState`.
- **State versions**: `WalletState` v1 → `WalletStateV2` v2 (upcasted via `@UpcastingHandler`); `PreviousAccountState` → `AccountState` (backward-compatible field addition)
- **PII fields**: `AccountState.firstName`, `AccountState.lastName`, `AccountState.email` (all annotated with `@PIIData`)

### Query / Read Model Impact

- **Affected QueryModels**: None in this module (query models are in `main/query-support`)
- **Affected DatabaseModels**: None in this module
- **New read endpoints**: None — the runtime module is write-side only

### Schema Evolution

- **Upcasting required**: Yes
- **Domain event upcasting**: `AccountCreatedEvent` v1 → `AccountCreatedEventV2` v2 (adds `twoFactorEnabled` defaulting to `false`)
- **State upcasting**: `WalletState` v1 → `WalletStateV2` v2 (converts flat `reservedAmount` per balance to `List<Reservation>`)
- **Schema Registry compatibility**: BACKWARD — all new versions must be backward-compatible; validated by `SchemaDiff.compare()` at registration time
