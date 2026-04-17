# Query Support Module — Implementation Plan

> **Status:** Migrated
> **Module:** `main/query-support/` (`akces-query-support`)

## 1. Technical Context

| Attribute | Value |
|-----------|-------|
| Language | Java 25+ |
| Framework | Spring Boot 4.x |
| Messaging | Apache Kafka 4.x (KRaft mode) |
| Serialization | Jackson 3.x (`tools.jackson`), Protobuf (protocol records) |
| Persistence | Spring JDBC (`JdbcTemplate`), Spring Data JPA |
| Build | Maven (parent `akces-framework-main`) |
| Test | JUnit 5, Testcontainers (Kafka, PostgreSQL), Mockito, Spring Boot Test, Liquibase |
| Artifact | `org.elasticsoftwarefoundation.akces:akces-query-support` |

### Internal Dependencies

| Artifact | Provides |
|----------|----------|
| `akces-api` | `QueryModel`, `QueryModelState`, `DatabaseModel`, `Reflector`, `DomainEvent`, `Command`, `CommandBus`, `CommandBusHolder`, annotations (`@QueryModelInfo`, `@QueryModelEventHandler`, `@DatabaseModelInfo`, `@DatabaseModelEventHandler`, `@ReflectorInfo`, `@ReflectorEventHandler`, `@PIIData`), `QueryModelEventHandlerFunction`, `DatabaseModelEventHandlerFunction`, `ReflectorEventHandlerFunction` |
| `akces-shared` | `SchemaRegistry`, `KafkaSchemaRegistry`, `KafkaTopicSchemaStorage`, `ProtocolRecordSerde`, `SchemaRecordSerde`, `AkcesControlRecordSerde`, `GDPRContextRepository`, `GDPRContextRepositoryFactory`, `RocksDBGDPRContextRepositoryFactory`, `GDPRContextHolder`, `GDPRAnnotationUtils`, `AkcesGDPRModule`, `BigDecimalSerializer`, `KafkaSender`, `HostUtils`, `KafkaUtils`, `EnvironmentPropertiesPrinter`, `CustomKafkaConsumerFactory`, `CustomKafkaProducerFactory` |

### Test Dependencies

| Dependency | Purpose |
|------------|---------|
| `akces-runtime` (test-jar) | Test fixtures: aggregate definitions, event types used by test query/database/reflector models |
| `akces-client` (test) | `AkcesClient` for end-to-end reflector integration tests |
| Testcontainers Kafka | KRaft-mode Kafka broker for integration tests |
| Testcontainers PostgreSQL | PostgreSQL for JDBC DatabaseModel integration tests |
| Liquibase | Database schema migration for test PostgreSQL |
| `spring-boot-starter-test` | Test framework utilities |
| `spring-boot-starter-jdbc` | JDBC auto-configuration for tests |
| `spring-boot-starter-data-jpa` | JPA auto-configuration for tests |

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Spring Boot Application                          │
│                                                                         │
│  AutoConfiguration.imports → conditionally activates one or more of:    │
│  ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────────────┐ │
│  │ QueryModel Config│ │ DatabaseModel    │ │ Reflector Config         │ │
│  │                  │ │ Config           │ │                          │ │
│  │ @Conditional:    │ │ @Conditional:    │ │ @Conditional:            │ │
│  │ QueryModelImpl   │ │ DatabaseModelImpl│ │ ReflectorImpl            │ │
│  │ PresentCondition │ │ PresentCondition │ │ PresentCondition         │ │
│  └────────┬─────────┘ └────────┬─────────┘ └────────────┬─────────────┘ │
│           │                    │                         │               │
│           ▼                    ▼                         ▼               │
│  BeanFactoryPostProcessor discovers annotated beans, registers:         │
│  - RuntimeFactory (FactoryBean → *Runtime)                              │
│  - EventHandlerFunctionAdapter (reflective method adapters)             │
│           │                    │                         │               │
│           ▼                    ▼                         ▼               │
│  ┌────────────────┐  ┌──────────────────┐  ┌───────────────────┐       │
│  │ QueryModel     │  │ DatabaseModel    │  │ Reflector         │       │
│  │ Controller     │  │ Controller       │  │ Controller        │       │
│  │ (Thread)       │  │ (Thread)         │  │ (Thread)          │       │
│  │                │  │                  │  │                   │       │
│  │ Hydration loop │  │ Rebalance loop   │  │ Rebalance loop    │       │
│  │ + state cache  │  │ + partition mgmt │  │ + partition mgmt  │       │
│  └───────┬────────┘  └─────────┬────────┘  └──────────┬────────┘       │
│          │                     │                      │                 │
│          │              ┌──────┴──────┐         ┌─────┴──────┐         │
│          │              │ Partition 0 │         │ Partition 0│         │
│          │              │ Partition 1 │         │ Partition 1│         │
│          │              │ Partition N │         │ Partition N│         │
│          │              └─────────────┘         └────────────┘         │
│          │                     │                      │                 │
│          ▼                     ▼                      ▼                 │
│   Kafka: Index topics   Kafka: DomainEvent     Kafka: DomainEvent      │
│   + GDPRKeys            topics + Control       topics + Control        │
│   + Schemas             + GDPRKeys + Schemas   + GDPRKeys + Schemas    │
│                                                + Commands (produce)     │
└─────────────────────────────────────────────────────────────────────────┘
```

## 3. Component Inventory

### 3.1 Source Files (44)

#### QueryModel Engine (13 files)

| # | File | Responsibility | Lines |
|---|------|---------------|-------|
| 1 | `models/AkcesQueryModelAutoConfiguration.java` | Spring config: consumer factories, GDPR repo, KafkaAdmin, controller bean | ~124 |
| 2 | `models/AkcesQueryModelController.java` | Thread-based hydration controller: state cache, GDPR decryption, lifecycle | ~488 |
| 3 | `models/AkcesQueryModelControllerState.java` | Lifecycle enum: `INITIALIZING`, `LOADING_GDPR_KEYS`, `RUNNING`, `SHUTTING_DOWN` | ~24 |
| 4 | `models/KafkaQueryModelRuntime.java` | Event application, domain event type resolution, Builder pattern | ~185 |
| 5 | `models/QueryModelRuntime.java` | Runtime interface: `apply()`, `validateDomainEventSchemas()` | ~41 |
| 6 | `models/QueryModelRuntimeFactory.java` | Spring `FactoryBean` assembling runtime from annotated beans | ~92 |
| 7 | `models/QueryModels.java` | Public API interface: `getHydratedState()` | ~28 |
| 8 | `models/QueryModelImplementationPresentCondition.java` | Classpath scan condition for `@QueryModelInfo` | ~30 |
| 9 | `models/QueryModelExecutionCancelledException.java` | Shutdown cancellation exception | ~30 |
| 10 | `models/QueryModelExecutionDisabledException.java` | Schema-disabled exception | ~30 |
| 11 | `models/QueryModelExecutionException.java` | Event replay IOException wrapper | ~30 |
| 12 | `models/QueryModelIdNotFoundException.java` | Missing aggregate ID exception | ~30 |
| 13 | `models/QueryModelNotFoundException.java` | Unknown query model class exception | ~30 |

#### QueryModel Beans (2 files)

| # | File | Responsibility | Lines |
|---|------|---------------|-------|
| 14 | `models/beans/QueryModelBeanFactoryPostProcessor.java` | Discovers `@QueryModelInfo` beans, registers `QueryModelRuntimeFactory` + handler adapters | ~104 |
| 15 | `models/beans/QueryModelEventHandlerFunctionAdapter.java` | Reflective method adapter for `@QueryModelEventHandler` | ~103 |

#### DatabaseModel Engine (12 files)

| # | File | Responsibility | Lines |
|---|------|---------------|-------|
| 16 | `database/AkcesDatabaseModelAutoConfiguration.java` | Spring config: consumer factories, GDPR repo, control serde, conditional beans | ~121 |
| 17 | `database/AkcesDatabaseModelController.java` | Thread-based controller: rebalancing, partition management, `AkcesRegistry` | ~363 |
| 18 | `database/AkcesDatabaseModelControllerState.java` | Lifecycle enum: 6 states including `INITIAL_REBALANCING` and `ERROR` | ~26 |
| 19 | `database/DatabaseModelRuntime.java` | Runtime interface: `apply()`, `initializeOffsets()`, `getDomainEventTypes()` | ~40 |
| 20 | `database/KafkaDatabaseModelRuntime.java` | Transactional event application with GDPR context, Builder pattern | ~207 |
| 21 | `database/DatabaseModelRuntimeFactory.java` | Spring `FactoryBean` assembling runtime from annotated beans | ~90 |
| 22 | `database/DatabaseModelPartition.java` | Per-partition `Runnable`: event consumption, GDPR keys, lifecycle | ~241 |
| 23 | `database/DatabaseModelPartitionState.java` | Partition lifecycle enum: 4 states | ~24 |
| 24 | `database/DatabaseModelImplementationPresentCondition.java` | Classpath scan condition for `@DatabaseModelInfo` | ~30 |

#### DatabaseModel JDBC/JPA (4 files)

| # | File | Responsibility | Lines |
|---|------|---------------|-------|
| 25 | `database/jdbc/JdbcDatabaseModel.java` | JDBC `DatabaseModel`: transactions, multi-dialect UPSERT offset tracking | ~150 |
| 26 | `database/jpa/JpaDatabaseModel.java` | JPA `DatabaseModel`: transactions, `PartitionOffsetRepository` offset tracking | ~76 |
| 27 | `database/jpa/PartitionOffset.java` | JPA entity: `(partitionId, offset)` | ~30 |
| 28 | `database/jpa/PartitionOffsetRepository.java` | Spring Data JPA repository interface | ~25 |

#### DatabaseModel Beans (2 files)

| # | File | Responsibility | Lines |
|---|------|---------------|-------|
| 29 | `database/beans/DatabaseModelBeanFactoryPostProcessor.java` | Discovers `@DatabaseModelInfo` beans, registers runtime factories + handler adapters | ~100 |
| 30 | `database/beans/DatabaseModelEventHandlerFunctionAdapter.java` | Reflective method adapter for `@DatabaseModelEventHandler` | ~90 |

#### Reflector Engine (11 files)

| # | File | Responsibility | Lines |
|---|------|---------------|-------|
| 31 | `reflector/AkcesReflectorAutoConfiguration.java` | Spring config: consumer + producer factories, GDPR repo, control serde | ~131 |
| 32 | `reflector/AkcesReflectorController.java` | Thread-based controller: rebalancing, partition management, `AkcesRegistry` | ~380 |
| 33 | `reflector/AkcesReflectorControllerState.java` | Lifecycle enum: 6 states including `INITIAL_REBALANCING` and `ERROR` | ~26 |
| 34 | `reflector/ReflectorRuntime.java` | Runtime interface: `apply()`, `handleSuccess()`, `handleFailure()`, retry config | ~95 |
| 35 | `reflector/KafkaReflectorRuntime.java` | Per-partition retry state, linear backoff, GDPR context, Builder pattern | ~351 |
| 36 | `reflector/ReflectorRuntimeFactory.java` | Spring `FactoryBean` assembling runtime from annotated beans | ~90 |
| 37 | `reflector/ReflectorPartition.java` | Per-partition `Runnable` + `CommandBus`: transactional event processing + command production | ~403 |
| 38 | `reflector/ReflectorPartitionState.java` | Partition lifecycle enum: 4 states | ~24 |
| 39 | `reflector/ReflectorResult.java` | Tri-state result: `SUCCESS`, `FAILURE`, `RETRY_PENDING` | ~24 |
| 40 | `reflector/ReflectorPartitionCommandBus.java` | ThreadLocal `CommandBus` registration for partition thread | ~26 |
| 41 | `reflector/ReflectorImplementationPresentCondition.java` | Classpath scan condition for `@ReflectorInfo` | ~30 |

#### Reflector Beans (2 files)

| # | File | Responsibility | Lines |
|---|------|---------------|-------|
| 42 | `reflector/beans/ReflectorBeanFactoryPostProcessor.java` | Discovers `@ReflectorInfo` beans, registers runtime factories + handler adapters | ~118 |
| 43 | `reflector/beans/ReflectorEventHandlerFunctionAdapter.java` | Reflective method adapter for `@ReflectorEventHandler` | ~94 |

#### Shared (1 file)

| # | File | Responsibility | Lines |
|---|------|---------------|-------|
| 44 | `QueryServiceApplication.java` | `@SpringBootApplication` entry point | ~37 |

### 3.2 Test Files (14)

| # | File | Responsibility |
|---|------|---------------|
| 1 | `TestUtils.java` | Shared test utilities for Kafka topic setup and data publishing |
| 2 | `models/QueryModelRuntimeTests.java` | Integration tests: QueryModel hydration with Testcontainers Kafka |
| 3 | `models/QueryModelTestConfiguration.java` | Spring test configuration for QueryModel tests |
| 4 | `models/wallet/WalletQueryModel.java` | Test fixture: Wallet QueryModel implementation |
| 5 | `models/wallet/WalletQueryModelState.java` | Test fixture: Wallet QueryModel state record |
| 6 | `models/account/AccountQueryModel.java` | Test fixture: Account QueryModel implementation |
| 7 | `models/account/AccountQueryModelState.java` | Test fixture: Account QueryModel state record |
| 8 | `database/DatabaseModelRuntimeTests.java` | Integration tests: JDBC DatabaseModel with Testcontainers Kafka + PostgreSQL |
| 9 | `database/DatabaseModelTestConfiguration.java` | Spring test configuration for DatabaseModel tests |
| 10 | `database/model/DefaultJdbcModel.java` | Test fixture: JDBC DatabaseModel implementation |
| 11 | `reflector/KafkaReflectorRuntimeTest.java` | Unit tests for `KafkaReflectorRuntime` retry logic |
| 12 | `reflector/ReflectorIntegrationTest.java` | Integration tests: Reflector with Testcontainers Kafka |
| 13 | `reflector/ReflectorTestConfiguration.java` | Spring test configuration for Reflector tests |
| 14 | `reflector/TestNotificationReflector.java` | Test fixture: Reflector implementation |

### 3.3 Resources

| File | Purpose |
|------|---------|
| `akces-querymodel.properties` | QueryModel Kafka defaults: `read_committed`, CooperativeStickyAssignor, `auto-offset-reset=latest` |
| `akces-databasemodel.properties` | DatabaseModel Kafka defaults: `read_committed`, CooperativeStickyAssignor, `auto-offset-reset=earliest` |
| `akces-reflector.properties` | Reflector Kafka defaults: same as DatabaseModel + `acks=all`, idempotent producer |
| `META-INF/spring/...AutoConfiguration.imports` | Registers all three auto-configuration classes |
| `db/changelog/liquibase.yaml` (test) | Liquibase changelog for test PostgreSQL schema |

## 4. Key Design Decisions

### D-1: Three independent sub-systems in one module

All three query-side engines share the same Maven artifact. They are activated independently via conditional auto-configuration — only the sub-system whose implementation annotation is detected on the classpath will be bootstrapped. This avoids dependency sprawl while keeping the engines decoupled.

### D-2: Controller + Partition architecture (DatabaseModel, Reflector)

The Controller thread owns the Kafka consumer group subscription for the `Akces-Control` topic and handles rebalancing events. Actual event processing is delegated to per-partition `Runnable` instances, each with its own Kafka consumer (hard-assigned). This allows horizontal scaling by adding more application instances.

### D-3: On-demand hydration (QueryModel)

Unlike DatabaseModel and Reflector which continuously stream events, the QueryModel only reads events when a hydration request arrives. It reads the per-entity index topic from beginning (or cached offset) to end, applies events, caches the result, and returns. This avoids maintaining state for all entities in memory.

### D-4: Transactional command sending (Reflector)

Each domain event is processed in its own Kafka transaction to ensure exactly-once semantics. The transaction encompasses: event handler invocation → `onSuccess`/`onFailure` callback (which may send commands via `CommandBus`) → offset commit. If the transaction fails, the consumer seeks back to replay the event.

### D-5: Linear backoff retry (Reflector)

Retries use linear backoff (`attempt × retryBackoffBaseMs`) rather than exponential to provide predictable latency. The retry state is held in-memory per partition; on partition reassignment, retry state is lost (the event will be reprocessed from the committed offset).

### D-6: Multi-dialect offset tracking (JDBC DatabaseModel)

The `JdbcDatabaseModel` detects the database type from JDBC metadata and generates appropriate UPSERT SQL for PostgreSQL, MySQL/MariaDB, Oracle, and SQL Server. This avoids requiring database-specific configuration.

## 5. Integration Points

| System | Protocol | Direction | Sub-System |
|--------|----------|-----------|------------|
| Kafka (`Akces-Schemas`) | `SchemaRecord` serde | Consume | All |
| Kafka (`Akces-GDPRKeys`) | `ProtocolRecord` serde (GDPRKeyRecord) | Consume | All (when PII data present) |
| Kafka (`Akces-Control`) | `AkcesControlRecord` serde | Consume (group) | DatabaseModel, Reflector |
| Kafka (Index topics) | `ProtocolRecord` serde (DomainEventRecord) | Consume (assigned) | QueryModel |
| Kafka (DomainEvent topics) | `ProtocolRecord` serde (DomainEventRecord) | Consume (assigned) | DatabaseModel, Reflector |
| Kafka (Command topics) | `ProtocolRecord` serde (CommandRecord) | Produce (transactional) | Reflector |
| RDBMS | Spring JDBC / JPA | Read/Write | DatabaseModel |
| RocksDB | `GDPRContextRepository` | Read/Write | All (GDPR key storage) |
| Spring Boot | Auto-configuration, Lifecycle, Availability Events | Framework | All |

## 6. Risk & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Schema registry not ready at startup | Query models disabled | Runtimes validate schemas at init; disabled runtimes are clearly reported |
| Aggregate service discovery lag | Cannot resolve event topics for DatabaseModel/Reflector | Control topic consumed from beginning during `INITIAL_REBALANCING`; retry on next rebalance |
| Kafka broker unavailable | Unrecoverable exception | Transition to `SHUTTING_DOWN`; publish `LivenessState.BROKEN` for health checks |
| GDPR key not available for PII event | Hydration blocks | QueryModel waits for GDPR key existence before completing hydration execution |
| External events not ordered across partitions | Possible inconsistency in DatabaseModel | Known TODO — events from different topics for the same partition are not merged in order |
| Reflector handler exception | Event stuck | Configurable retry with linear backoff; after `maxRetries` exhausted, `onFailure` callback fires |
| Database transaction failure (DatabaseModel) | Events not persisted | Transaction rolled back; consumer offsets not committed; events replayed on next poll |

## 7. Test Strategy

| Layer | Tool | Scope |
|-------|------|-------|
| Integration (QueryModel) | Testcontainers Kafka + Spring Boot Test | Full hydration lifecycle: publish events → hydrate → verify state |
| Integration (DatabaseModel) | Testcontainers Kafka + PostgreSQL + Liquibase | Full projection lifecycle: publish events → process → verify DB rows + offset tracking |
| Integration (Reflector) | Testcontainers Kafka + Spring Boot Test | Full reflection lifecycle: publish events → handle → verify command production |
| Unit (Reflector) | JUnit 5 + Mockito | `KafkaReflectorRuntime` retry logic: backoff calculation, retry exhaustion, state management |
| Test Fixtures | Wallet/Account QueryModels, DefaultJdbcModel, TestNotificationReflector | Reusable domain models for integration tests |
