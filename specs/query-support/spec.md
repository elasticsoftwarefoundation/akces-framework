# Query Support Module — Feature Specification

> **Status:** Migrated
> **Module:** `main/query-support/` (`akces-query-support`)
> **Version:** 0.12.1-SNAPSHOT
> **Package:** `org.elasticsoftware.akces.query`

## 1. Overview

The Query Support module provides the read-side projection infrastructure for the Akces CQRS/Event Sourcing framework. It delivers three independent sub-systems — **QueryModel**, **DatabaseModel**, and **Reflector** — each consuming domain events from Kafka and serving a different purpose: in-memory state hydration, persistent database projections, and event-driven command reflection. All three share a common pattern: a Spring Boot auto-configuration registers beans conditionally when an implementation is present on the classpath, a threaded Controller manages the Kafka lifecycle, and a Runtime applies events through handler functions discovered via annotation scanning and `BeanFactoryPostProcessor` introspection.

The module depends on `akces-api` (interfaces, annotations) and `akces-shared` (schema registry, GDPR support, Kafka utilities, serialization).

## 2. User Scenarios

### US-1: Define a QueryModel that projects aggregate events into an in-memory read model

**As a** read-side developer,
**I want to** define a `QueryModel` implementation annotated with `@QueryModelInfo` that handles domain events via `@QueryModelEventHandler` methods,
**so that** I can hydrate a read-optimized, in-memory state for a given aggregate instance on demand.

#### Acceptance Criteria

1. The developer implements `QueryModel<S extends QueryModelState>` with `getStateClass()` and `getIndexName()`.
2. The class is annotated with `@QueryModelInfo(value, version, indexName)` which registers it as a Spring component.
3. Methods annotated with `@QueryModelEventHandler(create = true)` receive a domain event and `null` state, returning a new `QueryModelState` instance (creation handler).
4. Methods annotated with `@QueryModelEventHandler` (without `create`) receive a domain event and current state, returning updated state.
5. The `AkcesQueryModelController` consumes the aggregate's per-entity index topic (one topic per `indexName + aggregateId`), replays all `DomainEventRecord`s through the `KafkaQueryModelRuntime`, and returns the hydrated state via `CompletionStage<S>`.
6. Hydrated state is cached using Caffeine (max 1000 entries) with offset tracking for incremental hydration on subsequent requests.
7. When events contain `@PIIData`-annotated fields, the controller loads GDPR encryption keys from the `Akces-GDPRKeys` topic (all partitions) before transitioning to `RUNNING` state, and decrypts the final state after hydration.
8. Domain event schemas are validated against the `SchemaRegistry` (backed by `Akces-Schemas` topic) during initialization; query models with schema validation failures are disabled (moved to `disabledRuntimes`).
9. If all query model runtimes are disabled due to schema errors, the controller shuts down.
10. Disabled query models return `QueryModelExecutionDisabledException`; unknown models return `QueryModelNotFoundException`; missing aggregate IDs return `QueryModelIdNotFoundException`.

### US-2: Define a DatabaseModel that persists event projections via JDBC

**As a** read-side developer,
**I want to** define a `DatabaseModel` backed by `JdbcDatabaseModel` that handles domain events via `@DatabaseModelEventHandler` methods,
**so that** I can project events into relational database tables with transactional offset tracking.

#### Acceptance Criteria

1. The developer extends `JdbcDatabaseModel`, which is injected with `PlatformTransactionManager` and `JdbcTemplate`.
2. The class is annotated with `@DatabaseModelInfo(value, version, schemaName)` which registers it as a Spring component.
3. Methods annotated with `@DatabaseModelEventHandler` receive a domain event and perform side-effecting JDBC operations (INSERT, UPDATE, DELETE) within an active transaction.
4. `JdbcDatabaseModel.startTransaction()` opens a Spring `TransactionStatus` with `PROPAGATION_REQUIRED` and `ISOLATION_READ_COMMITTED`.
5. `JdbcDatabaseModel.commitTransaction()` persists partition offsets to a `partition_offsets` table using database-specific UPSERT SQL (PostgreSQL, MySQL/MariaDB, Oracle, and SQL Server dialects auto-detected from JDBC metadata) and commits the transaction; on failure, it rolls back.
6. `JdbcDatabaseModel.getOffsets()` reads stored offsets from `partition_offsets` table for a set of partition IDs, enabling crash-recovery resume.
7. The `AkcesDatabaseModelController` subscribes to `Akces-Control` topic via consumer group rebalancing (CooperativeStickyAssignor), discovers aggregate services, and creates one `DatabaseModelPartition` per assigned partition.
8. Each `DatabaseModelPartition` runs in its own thread, consumes from aggregate event topics (hard-assigned by partition ID) plus optionally `Akces-GDPRKeys`, and processes events through the `KafkaDatabaseModelRuntime`.
9. Events with `@PIIData` fields trigger GDPR key loading during initialization; the `GDPRContextHolder` ThreadLocal is set before each handler invocation and reset afterward.
10. Domain event schemas are validated against the `SchemaRegistry` at startup.

### US-3: Define a DatabaseModel that persists event projections via JPA

**As a** read-side developer,
**I want to** define a `DatabaseModel` backed by `JpaDatabaseModel` that handles domain events via `@DatabaseModelEventHandler` methods,
**so that** I can use Spring Data JPA repositories for persistence with automatic offset tracking.

#### Acceptance Criteria

1. The developer extends `JpaDatabaseModel`, which is injected with a `PartitionOffsetRepository` (Spring Data JPA) and `PlatformTransactionManager`.
2. `JpaDatabaseModel.getOffsets()` delegates to `PartitionOffsetRepository.findByPartitionIdIn()`.
3. `JpaDatabaseModel.commitTransaction()` saves `PartitionOffset` entities via `repository.saveAll()` and commits; rolls back on failure.
4. The `PartitionOffset` JPA entity maps `partitionId` (primary key) and `offset` columns.
5. All other lifecycle and event handling behavior is identical to the JDBC variant (US-2).

### US-4: Define a Reflector that reads events and sends commands back into the system

**As a** workflow developer,
**I want to** define a `Reflector<R>` implementation that handles domain events, optionally calls external APIs, and sends commands back into the system on success or failure,
**so that** I can integrate external systems with the event-driven architecture.

#### Acceptance Criteria

1. The developer implements `Reflector<R>` and annotates the class with `@ReflectorInfo(value, version, schemaName, maxRetries, retryBackoffBaseMs)`.
2. Methods annotated with `@ReflectorEventHandler` receive a domain event and return a result `R`; exceptions trigger the retry mechanism.
3. The `KafkaReflectorRuntime` maintains per-partition retry state (`RetryState` record) with linear backoff: delay = attempt × `retryBackoffBaseMs`.
4. When all retries are exhausted (`maxRetries` + 1 total attempts), the runtime returns `FAILURE` and calls `Reflector.onFailure(event, exception, commandBus)`.
5. On success, the runtime returns `SUCCESS` and calls `Reflector.onSuccess(event, result, commandBus)`.
6. When the backoff period has not yet elapsed, the runtime returns `RETRY_PENDING`; the `ReflectorPartition` pauses the Kafka partition and seeks back to the event offset for redelivery.
7. The `ReflectorPartition` implements `CommandBus`, allowing `send(Command)` calls within the `onSuccess`/`onFailure` callbacks. Commands are serialized to `CommandRecord` and produced within a Kafka transaction.
8. The `ReflectorPartitionCommandBus` registers the partition as the ThreadLocal `CommandBus` via `CommandBusHolder`, ensuring `send()` is only called from the reflector partition thread.
9. Each event is processed in its own Kafka transaction: `beginTransaction` → handler + `onSuccess`/`onFailure` → `sendOffsetsToTransaction` → `commitTransaction`. On `InvalidProducerEpochException` or `KafkaException`, the transaction is aborted and the consumer seeks back.
10. The `AkcesReflectorController` subscribes to `Akces-Control`, discovers aggregate services for topic/partition resolution, and manages `ReflectorPartition` lifecycle through consumer group rebalancing.
11. Domain event schemas are validated against the `SchemaRegistry` at startup.

### US-5: Auto-configure query services in Spring Boot

**As a** Spring Boot application developer,
**I want** the query sub-systems to be auto-configured when I include `akces-query-support` and provide an implementation,
**so that** I can run QueryModel, DatabaseModel, or Reflector services without manual bean wiring.

#### Acceptance Criteria

1. Three auto-configuration classes are registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: `AkcesQueryModelAutoConfiguration`, `AkcesDatabaseModelAutoConfiguration`, `AkcesReflectorAutoConfiguration`.
2. Each configuration is conditional: a `BeanFactoryPostProcessor` is created only when the corresponding `*ImplementationPresentCondition` detects an implementation class on the classpath (scanning for `@QueryModelInfo`, `@DatabaseModelInfo`, or `@ReflectorInfo` annotations).
3. All downstream beans are `@ConditionalOnBean` on the post-processor, ensuring no infrastructure is created without an implementation.
4. Each configuration creates isolated, `@Qualifier`-named Kafka consumer factories (ProtocolRecord, SchemaRecord, and for DatabaseModel/Reflector: AkcesControlRecord), GDPR context repository factories (RocksDB-backed), and Jackson customizers (BigDecimal + GDPR module).
5. The Reflector configuration additionally creates a transactional `ProducerFactory` for command sending.
6. Default Kafka properties are loaded from `akces-querymodel.properties`, `akces-databasemodel.properties`, and `akces-reflector.properties` respectively. All use `read_committed` isolation, `CooperativeStickyAssignor`, auto-commit disabled, 500 max-poll-records. The Reflector additionally configures `acks=all` and idempotent producer.
7. `QueryServiceApplication` serves as the `@SpringBootApplication` entry point, excluding `KafkaAutoConfiguration` and accepting command-line arguments as additional component-scan packages.

## 3. Domain Model

### 3.1 QueryModel Sub-System

| Type | Name | Description |
|------|------|-------------|
| Class | `AkcesQueryModelAutoConfiguration` | Spring `@Configuration` with conditional bean definitions |
| Class | `AkcesQueryModelController` | Thread-based controller: hydration loop, state caching, GDPR decryption |
| Enum | `AkcesQueryModelControllerState` | `INITIALIZING → LOADING_GDPR_KEYS → RUNNING → SHUTTING_DOWN` |
| Interface | `QueryModelRuntime<S>` | Runtime contract: `apply()`, `validateDomainEventSchemas()`, `shouldHandlePIIData()` |
| Class | `KafkaQueryModelRuntime<S>` | Kafka-specific runtime with event type resolution and Builder |
| Class | `QueryModelRuntimeFactory<S>` | Spring `FactoryBean` that assembles `KafkaQueryModelRuntime` from beans |
| Interface | `QueryModels<S>` | Public hydration API: `getHydratedState(modelClass, id)` |
| Class | `QueryModelBeanFactoryPostProcessor` | Discovers `@QueryModelInfo` beans, registers runtime factories and handler adapters |
| Class | `QueryModelEventHandlerFunctionAdapter` | Reflective adapter bridging `@QueryModelEventHandler` methods to `QueryModelEventHandlerFunction` |
| Class | `QueryModelImplementationPresentCondition` | Classpath condition checking for `@QueryModelInfo`-annotated classes |

### 3.2 DatabaseModel Sub-System

| Type | Name | Description |
|------|------|-------------|
| Class | `AkcesDatabaseModelAutoConfiguration` | Spring `@Configuration` with conditional bean definitions |
| Class | `AkcesDatabaseModelController` | Thread-based controller with `ConsumerRebalanceListener` and `AkcesRegistry` |
| Enum | `AkcesDatabaseModelControllerState` | `INITIALIZING → INITIAL_REBALANCING → REBALANCING → RUNNING → SHUTTING_DOWN → ERROR` |
| Interface | `DatabaseModelRuntime` | Runtime contract: `apply()`, `initializeOffsets()`, `getDomainEventTypes()` |
| Class | `KafkaDatabaseModelRuntime` | Kafka-specific runtime with transactional event application |
| Class | `DatabaseModelRuntimeFactory` | Spring `FactoryBean` that assembles `KafkaDatabaseModelRuntime` from beans |
| Class | `DatabaseModelPartition` | Per-partition `Runnable`: consumes events + GDPR keys, delegates to runtime |
| Enum | `DatabaseModelPartitionState` | `INITIALIZING → LOADING_GDPR_KEYS → PROCESSING → SHUTTING_DOWN` |
| Class | `JdbcDatabaseModel` | JDBC-based `DatabaseModel` with multi-dialect UPSERT offset tracking |
| Class | `JpaDatabaseModel` | JPA-based `DatabaseModel` using `PartitionOffsetRepository` |
| Entity | `PartitionOffset` | JPA entity for `(partitionId, offset)` offset storage |
| Interface | `PartitionOffsetRepository` | Spring Data JPA repository for `PartitionOffset` |
| Class | `DatabaseModelBeanFactoryPostProcessor` | Discovers `@DatabaseModelInfo` beans, registers runtime factories and handler adapters |
| Class | `DatabaseModelEventHandlerFunctionAdapter` | Reflective adapter bridging `@DatabaseModelEventHandler` methods to `DatabaseModelEventHandlerFunction` |
| Class | `DatabaseModelImplementationPresentCondition` | Classpath condition checking for `@DatabaseModelInfo`-annotated classes |

### 3.3 Reflector Sub-System

| Type | Name | Description |
|------|------|-------------|
| Class | `AkcesReflectorAutoConfiguration` | Spring `@Configuration` with conditional bean definitions |
| Class | `AkcesReflectorController` | Thread-based controller with `ConsumerRebalanceListener` and `AkcesRegistry` |
| Enum | `AkcesReflectorControllerState` | `INITIALIZING → INITIAL_REBALANCING → REBALANCING → RUNNING → SHUTTING_DOWN → ERROR` |
| Interface | `ReflectorRuntime` | Runtime contract: `apply()`, `handleSuccess()`, `handleFailure()`, retry configuration |
| Class | `KafkaReflectorRuntime` | Kafka-specific runtime with per-partition retry state and linear backoff |
| Class | `ReflectorRuntimeFactory` | Spring `FactoryBean` that assembles `KafkaReflectorRuntime` from beans |
| Class | `ReflectorPartition` | Per-partition `Runnable` + `CommandBus`: consumes events, processes with transactions, sends commands |
| Enum | `ReflectorPartitionState` | `INITIALIZING → LOADING_GDPR_KEYS → PROCESSING → SHUTTING_DOWN` |
| Enum | `ReflectorResult` | `SUCCESS`, `FAILURE`, `RETRY_PENDING` |
| Class | `ReflectorPartitionCommandBus` | Registers `ReflectorPartition` as ThreadLocal `CommandBus` |
| Class | `ReflectorBeanFactoryPostProcessor` | Discovers `@ReflectorInfo` beans, registers runtime factories and handler adapters |
| Class | `ReflectorEventHandlerFunctionAdapter` | Reflective adapter bridging `@ReflectorEventHandler` methods to `ReflectorEventHandlerFunction` |
| Class | `ReflectorImplementationPresentCondition` | Classpath condition checking for `@ReflectorInfo`-annotated classes |

### 3.4 Exception Hierarchy (QueryModel)

| Exception | Trigger |
|-----------|---------|
| `QueryModelExecutionCancelledException` | Hydration request cancelled during shutdown |
| `QueryModelExecutionDisabledException` | Query model disabled due to schema validation failure |
| `QueryModelExecutionException` | IOException during event replay |
| `QueryModelIdNotFoundException` | Aggregate ID not found in index topic |
| `QueryModelNotFoundException` | No runtime registered for the requested query model class |

### 3.5 Shared Entry Point

| Type | Name | Description |
|------|------|-------------|
| Class | `QueryServiceApplication` | `@SpringBootApplication` entry point; excludes `KafkaAutoConfiguration`, accepts CLI package args |

## 4. Kafka Topology

| Topic | Sub-System | Role | Access Pattern |
|-------|-----------|------|----------------|
| `Akces-Schemas` | All | Load/validate JSON Schemas for domain events | Consumer via `KafkaTopicSchemaStorage` |
| `Akces-GDPRKeys` | All | Load GDPR encryption keys for PII decryption | Consumer — all partitions, seek-from-stored-offset |
| `Akces-Control` | DatabaseModel, Reflector | Discover `AggregateServiceRecord`s (event topics, command types) | Consumer — group-based with rebalancing |
| `{IndexName}-{AggregateId}` | QueryModel | Per-entity event index for on-demand hydration | Consumer — single partition 0, seek-to-beginning or cached offset |
| `{Aggregate}-DomainEvents` | DatabaseModel, Reflector | Aggregate event stream | Consumer — hard-assigned by partition ID |
| `{Aggregate}-Commands` | Reflector | Send commands on success/failure callbacks | Producer — transactional, hash-partitioned |

## 5. Concurrency Model

- **QueryModel:** Single `AkcesQueryModelController` thread processes hydration requests from a `LinkedBlockingQueue`. Application threads submit requests and receive results via `CompletableFuture`. No consumer group rebalancing (direct topic/partition assignment).
- **DatabaseModel:** Single `AkcesDatabaseModelController` thread manages control topic subscription and rebalancing. Each assigned partition gets a `DatabaseModelPartition` running in a `CachedThreadPool`. Partitions are independent — no cross-partition coordination.
- **Reflector:** Single `AkcesReflectorController` thread manages control topic subscription and rebalancing. Each assigned partition gets a `ReflectorPartition` running in a `CachedThreadPool`. Each partition owns its own Kafka producer for transactional command sending. `CommandBus.send()` is thread-confined to the partition thread via `CommandBusHolder` ThreadLocal.

## 6. Dependencies

| Dependency | Purpose |
|------------|---------|
| `akces-api` | Core interfaces (`QueryModel`, `DatabaseModel`, `Reflector`, annotations) |
| `akces-shared` | Schema registry, GDPR support, Kafka utilities, serialization |
| `spring-boot-autoconfigure` | Auto-configuration |
| `spring-boot-kafka` | Kafka consumer/producer factories |
| `spring-boot-jackson` / `spring-boot-starter-json` | JSON serialization |
| `spring-context` | ApplicationContext, bean factories |
| `spring-jdbc` | `JdbcTemplate`, `PlatformTransactionManager` |
| `jakarta.persistence-api` | JPA annotations for `PartitionOffset` entity |
| `spring-data-jpa` | `PartitionOffsetRepository` |
| `caffeine` (transitive) | In-memory state cache for QueryModel |
| `guava` (transitive) | Murmur3 hashing for GDPR key partition resolution |

## 7. Non-Functional Requirements

- **Read-committed isolation:** All Kafka consumers use `read_committed` isolation level.
- **Exactly-once semantics (Reflector):** Transactional producer with idempotence enabled; consumer offsets committed within the transaction.
- **Graceful shutdown:** All controllers and partitions use `CountDownLatch` with 10-second timeout. Pending hydration requests are completed exceptionally. `LivenessState.BROKEN` is published on shutdown.
- **Thread safety:** `ConcurrentHashMap` for runtime lookups and service registries; `volatile` state fields; `BlockingQueue` for hydration requests.
- **GDPR compliance:** PII data is transparently decrypted via `GDPRContextHolder` ThreadLocal and `AkcesGDPRModule`. Encryption keys are loaded from RocksDB-backed repositories.
- **Schema evolution:** External domain event types are resolved by finding the highest registered version ≤ the event record's version.

## 8. Out of Scope

- Aggregate-side command handling and event sourcing (handled by `akces-runtime`).
- Command validation, serialization, and client-side routing (handled by `akces-client`).
- Schema registration and creation (handled by aggregate services via `akces-shared`).
- Kubernetes operator deployment configuration (handled by `services/operator`).
- Index topic creation and management (handled by the aggregate runtime).
