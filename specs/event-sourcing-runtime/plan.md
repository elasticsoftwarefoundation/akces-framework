# Implementation Plan: Event Sourcing Runtime

**Module**: `main/runtime` (`akces-runtime`) | **Date**: 2025-07-15 | **Spec**: [spec.md](spec.md)  
**Input**: Reverse-engineered from existing production module

## Summary

The Event Sourcing Runtime is the core engine of the Akces Framework. It processes commands through aggregates, emits domain events to Kafka, manages aggregate state with RocksDB snapshots, handles schema upcasting, and coordinates cross-aggregate workflows via process managers. This plan documents the existing implementation for brownfield migration tracking.

## Technical Context

**Language/Version**: Java 25  
**Primary Dependencies**: Spring Boot 4.x (`spring-boot-autoconfigure`, `spring-boot-kafka`, `spring-boot-jackson`), Apache Kafka (`kafka-clients` 4.x, `kafka-streams`, `spring-kafka`), Google Protobuf (`protobuf-java`), victools (`jsonschema-generator`, `jsonschema-module-jakarta-validation`, `jsonschema-module-jackson`), Google Guava (Murmur3 hashing), Semver4j, SLF4J  
**Sibling Modules**: `akces-api` (annotations, interfaces), `akces-shared` (serialization, GDPR, schema registry, protocol records), `akces-client` (test dependency for command submission)  
**Storage**: RocksDB (per-partition aggregate state snapshots), Kafka (event log, state topic, control topic, schema topic, GDPR keys topic)  
**Testing**: JUnit Jupiter 5, Mockito, AssertJ, Spring Boot Test, Testcontainers (Confluent Kafka 7.8.x in KRaft mode); run with `cd main/runtime && mvn test`  
**Target Platform**: JVM / Kubernetes (deployed as `AggregateService` CRD via the Akces Operator)  
**Constraints**: All command/event handlers must be non-blocking and deterministic. Schema changes must be backward-compatible. Kafka transactions guarantee exactly-once processing. Protobuf wire format for all protocol records.

## Project Structure

### Documentation (this feature)

```text
specs/event-sourcing-runtime/
├── spec.md              # Feature specification (migrated)
├── plan.md              # This file
└── tasks.md             # Task breakdown (all completed)
```

### Source Code

```text
main/runtime/
├── pom.xml
└── src/
    ├── main/java/org/elasticsoftware/akces/
    │   ├── AggregateServiceApplication.java          # Spring Boot entry point, bean definitions
    │   ├── AkcesAggregateController.java             # Top-level lifecycle controller
    │   ├── AkcesControllerState.java                 # Controller state enum (INITIALIZING → RUNNING → …)
    │   ├── aggregate/
    │   │   ├── AggregateRuntime.java                 # Core runtime interface
    │   │   └── IndexParams.java                      # Aggregate indexing parameters record
    │   ├── beans/
    │   │   ├── AggregateBeanFactoryPostProcessor.java # @AggregateInfo scanner + bean wiring
    │   │   ├── AggregateValidator.java               # Aggregate structure validator
    │   │   ├── CommandHandlerFunctionAdapter.java     # Wraps @CommandHandler methods
    │   │   ├── EventSourcingHandlerFunctionAdapter.java # Wraps @EventSourcingHandler methods
    │   │   ├── EventHandlerFunctionAdapter.java       # Wraps @EventHandler methods
    │   │   ├── EventBridgeHandlerFunctionAdapter.java # Wraps @EventBridgeHandler methods
    │   │   ├── AggregateStateUpcastingHandlerFunctionAdapter.java # Wraps state @UpcastingHandler
    │   │   ├── DomainEventUpcastingHandlerFunctionAdapter.java    # Wraps event @UpcastingHandler
    │   │   └── ProtocolRecordTypeValueCodeGeneratorDelegate.java  # AOT code generation
    │   ├── kafka/
    │   │   ├── AggregatePartition.java               # Per-partition command loop + state management
    │   │   ├── AggregatePartitionCommandBus.java     # CommandBus impl for cross-aggregate commands
    │   │   ├── AggregatePartitionState.java          # Partition state enum (INITIALIZING → PROCESSING → …)
    │   │   ├── KafkaAggregateRuntime.java            # AggregateRuntime impl with handler maps
    │   │   ├── AggregateRuntimeFactory.java          # Builder for KafkaAggregateRuntime
    │   │   └── PartitionUtils.java                   # Topic naming, partition calculation (Murmur3)
    │   └── state/
    │       ├── AggregateStateRepository.java          # Interface: prepare/commit/rollback/get
    │       ├── AggregateStateRepositoryFactory.java   # Factory interface
    │       ├── AggregateStateRepositoryException.java # Runtime exception wrapper
    │       ├── RocksDBAggregateStateRepository.java   # Production impl (RocksDB)
    │       ├── RocksDBAggregateStateRepositoryFactory.java
    │       ├── InMemoryAggregateStateRepository.java  # Test impl (ConcurrentHashMap)
    │       └── InMemoryAggregateStateRepositoryFactory.java
    └── test/
        ├── java/org/elasticsoftware/
        │   ├── akces/beans/
        │   │   ├── AotServicesTest.java              # AOT/native-image service loading
        │   │   └── MinInsyncReplicasTest.java        # Quorum calculation
        │   └── akcestest/
        │       ├── RuntimeConfiguration.java          # All-aggregate test config
        │       ├── WalletConfiguration.java           # Wallet-only test config (mock SchemaStorage)
        │       ├── AccountConfiguration.java          # Account-only test config
        │       ├── TestUtils.java                     # Kafka topic creation, schema registration
        │       ├── RuntimeTests.java                  # Full Testcontainers integration
        │       ├── WalletTests.java                   # Schema validation + command handling unit tests
        │       ├── AccountTests.java                  # Schema evolution test
        │       ├── AggregateServiceApplicationTests.java # Single-aggregate Testcontainers test
        │       ├── aggregate/
        │       │   ├── account/                       # Account aggregate (GDPR/PII, upcasting)
        │       │   │   ├── Account.java
        │       │   │   ├── AccountState.java / PreviousAccountState.java
        │       │   │   ├── CreateAccountCommand.java
        │       │   │   ├── AccountCreatedEvent.java / AccountCreatedEventV2.java
        │       │   ├── wallet/                        # Wallet aggregate (core CRUD + errors)
        │       │   │   ├── Wallet.java
        │       │   │   ├── WalletState.java / WalletStateV2.java
        │       │   │   ├── Create/Credit/Reserve commands
        │       │   │   ├── Created/Credited/Reserved events + error events
        │       │   │   └── ExternalAccountCreatedEvent.java
        │       │   └── orders/                        # OrderProcessManager (cross-aggregate)
        │       │       ├── OrderProcessManager.java
        │       │       ├── OrderProcessManagerState.java / OrderProcess.java / BuyOrderProcess.java
        │       │       ├── PlaceBuyOrderCommand.java
        │       │       └── BuyOrder events
        │       ├── control/
        │       │   └── AkcesAggregateControllerTests.java  # Control record serde
        │       ├── protocol/
        │       │   └── ProtocolTests.java             # Protobuf serialization
        │       ├── schemas/
        │       │   ├── JsonSchemaTests.java           # Schema compatibility
        │       │   └── AccountCreatedEvent v1/v2/v3, NotCompatible v4
        │       ├── state/
        │       │   └── RocksDBAggregateStateRepositoryTests.java
        │       ├── old/                               # Legacy schema versions for compatibility tests
        │       └── util/
        │           └── HostUtilsTests.java
        └── resources/
            ├── test-application.yaml
            └── akces-client.properties
```

### Module Dependency Graph

```text
akces-api ──────┐
                ├──► akces-runtime (this module)
akces-shared ───┘         │
                          │ (test scope)
akces-client ─────────────┘
```

## Architecture Decisions

### 1. Per-Partition State Ownership
Each `AggregatePartition` owns a dedicated `AggregateStateRepository` and `GDPRContextRepository`. This avoids cross-partition locking and aligns with Kafka's consumer-group partition assignment model.

### 2. Kafka Transactions for Exactly-Once
Command processing wraps the entire read→handle→produce→state-update cycle in a Kafka transaction. If any step fails, the transaction aborts and the consumer re-reads from the last committed offset.

### 3. RocksDB for State Snapshots
RocksDB provides O(1) key-value lookups for aggregate state, avoiding full event replay on every command. State is bulk-loaded from Kafka on partition assignment and incrementally updated.

### 4. Reflective Handler Discovery
The `AggregateBeanFactoryPostProcessor` uses Java reflection to discover annotated handler methods and wrap them in typed function adapters. This eliminates boilerplate registration code and supports AOT/native-image via code-generated reflection hints.

### 5. Murmur3 Partition Routing
`PartitionUtils` uses Guava's `Hashing.murmur3_32_fixed()` for deterministic, well-distributed partition assignment — matching Kafka's default partitioner characteristics while remaining independent of the Kafka client's partitioner implementation.

## Complexity Tracking

No constitution violations. The module uses a single Maven artifact with clear package boundaries (controller, kafka, beans, state, aggregate).
