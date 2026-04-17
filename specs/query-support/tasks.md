# Query Support Module — Tasks

> **Status:** Migrated (all tasks completed)
> **Module:** `main/query-support/` (`akces-query-support`)

## Tasks

### Phase 1: QueryModel Engine

#### 1.1 QueryModel Runtime

- [x] **T-1.1.1** Define `QueryModelRuntime<S>` interface with `getName()`, `getIndexName()`, `getQueryModelClass()`, `apply(List<DomainEventRecord>, S)`, `validateDomainEventSchemas()`, `shouldHandlePIIData()`
- [x] **T-1.1.2** Implement `KafkaQueryModelRuntime<S>` with external domain event type resolution (highest version ≤ event record version), event materialization via ObjectMapper, create handler + update handlers dispatch
- [x] **T-1.1.3** Implement `KafkaQueryModelRuntime.Builder<S>` for fluent runtime assembly: `setObjectMapper`, `setStateType`, `setQueryModelClass`, `setCreateHandler`, `addDomainEvent`, `addQueryModelEventHandler`
- [x] **T-1.1.4** Auto-detect `shouldHandlePIIData` from `GDPRAnnotationUtils.hasPIIDataAnnotation()` on registered domain event types during build

#### 1.2 QueryModel Bean Discovery

- [x] **T-1.2.1** Implement `QueryModelImplementationPresentCondition` to scan classpath for `@QueryModelInfo`-annotated classes
- [x] **T-1.2.2** Implement `QueryModelBeanFactoryPostProcessor` to discover `QueryModel` beans, register `QueryModelRuntimeFactory` and `QueryModelEventHandlerFunctionAdapter` beans dynamically
- [x] **T-1.2.3** Implement `QueryModelRuntimeFactory<S>` as Spring `FactoryBean<QueryModelRuntime<S>>`: read `@QueryModelInfo` annotation, resolve `QueryModelStateType`, collect `QueryModelEventHandlerFunction` beans, build `KafkaQueryModelRuntime`
- [x] **T-1.2.4** Implement `QueryModelEventHandlerFunctionAdapter` as reflective adapter bridging `@QueryModelEventHandler` annotated methods to `QueryModelEventHandlerFunction`; handle `create` flag and `DomainEventType` extraction

#### 1.3 QueryModel Controller

- [x] **T-1.3.1** Implement `AkcesQueryModelController` extending `Thread`, implementing `AutoCloseable`, `ApplicationContextAware`, and `QueryModels<S>`
- [x] **T-1.3.2** Implement `INITIALIZING` phase: validate domain event schemas for all runtimes via `SchemaRegistry`; disable runtimes with `SchemaException`; transition to `LOADING_GDPR_KEYS` or `RUNNING`
- [x] **T-1.3.3** Implement `LOADING_GDPR_KEYS` phase: assign all `Akces-GDPRKeys` partitions, process GDPR key records into RocksDB repositories, transition to `RUNNING` when caught up to end offsets
- [x] **T-1.3.4** Implement `RUNNING` phase hydration loop: poll `commandQueue`, create `HydrationExecution` per request, assign index topic partitions, seek to beginning or cached offset, poll and apply events, check stop condition
- [x] **T-1.3.5** Implement `getHydratedState()`: check enabled/disabled runtimes, check Caffeine cache for existing state, enqueue `HydrationRequest` with `CompletableFuture`
- [x] **T-1.3.6** Implement Caffeine-based state cache (max 1000 entries) with offset tracking for incremental hydration
- [x] **T-1.3.7** Implement GDPR decryption post-processing: convert state to Map via ObjectMapper, set `GDPRContextHolder`, convert back to typed state
- [x] **T-1.3.8** Implement `SHUTTING_DOWN` phase: drain pending requests/executions with `QueryModelExecutionCancelledException`, close `GDPRContextRepository` instances, publish `LivenessState.BROKEN`
- [x] **T-1.3.9** Implement `close()` with `CountDownLatch` 10-second timeout
- [x] **T-1.3.10** Implement `HydrationRequest` record: `runtime`, `completableFuture`, `id`, `currentState`, `currentOffset`
- [x] **T-1.3.11** Implement `HydrationExecution` record: `runtime`, `completableFuture`, `id`, `currentState`, `currentOffset`, `indexPartition`, `endOffset` with `withEndOffset()`, `withCurrentState()`, `complete()`
- [x] **T-1.3.12** Implement `CachedQueryModelState` record for Caffeine cache entries

#### 1.4 QueryModel Exceptions

- [x] **T-1.4.1** Implement `QueryModelExecutionCancelledException` for shutdown cancellation
- [x] **T-1.4.2** Implement `QueryModelExecutionDisabledException` for schema-disabled runtimes
- [x] **T-1.4.3** Implement `QueryModelExecutionException` wrapping IOException during event replay
- [x] **T-1.4.4** Implement `QueryModelIdNotFoundException` for missing aggregate IDs (index topic not found or empty)
- [x] **T-1.4.5** Implement `QueryModelNotFoundException` for unregistered query model classes

#### 1.5 QueryModel Auto-Configuration

- [x] **T-1.5.1** Create `AkcesQueryModelAutoConfiguration` with `@Configuration` and `@PropertySource("classpath:akces-querymodel.properties")`
- [x] **T-1.5.2** Define `queryModelBeanFactoryPostProcessor` bean with `@Conditional(QueryModelImplementationPresentCondition.class)`
- [x] **T-1.5.3** Define `akcesQueryModelJsonCustomizer` bean (BigDecimal serializer + `AkcesGDPRModule`)
- [x] **T-1.5.4** Define `akcesQueryModelSchemaRecordSerde` bean (`@ConditionalOnBean`)
- [x] **T-1.5.5** Define `akcesQueryModelSchemaConsumerFactory` and `akcesQueryModelConsumerFactory` beans
- [x] **T-1.5.6** Define `akcesQueryModelGDPRContextRepositoryFactory` bean (RocksDB-backed)
- [x] **T-1.5.7** Define `akcesQueryModelKafkaAdmin` bean
- [x] **T-1.5.8** Define `ackesQueryModelController` bean with `initMethod="start"`, `destroyMethod="close"`
- [x] **T-1.5.9** Create `akces-querymodel.properties` with consumer defaults: `read_committed`, cooperative-sticky, auto-commit disabled, 500 max-poll, `auto-offset-reset=latest`

### Phase 2: DatabaseModel Engine

#### 2.1 DatabaseModel Runtime

- [x] **T-2.1.1** Define `DatabaseModelRuntime` interface with `getName()`, `apply()`, `initializeOffsets()`, `validateDomainEventSchemas()`, `shouldHandlePIIData()`, `getDomainEventTypes()`
- [x] **T-2.1.2** Implement `KafkaDatabaseModelRuntime` with transactional event processing: iterate records by partition, resolve domain event type, set GDPR context per event, invoke handler, commit transaction with offsets
- [x] **T-2.1.3** Implement `KafkaDatabaseModelRuntime.Builder`: `setObjectMapper`, `setDatabaseModelInfo`, `setDatabaseModel`, `addDomainEvent`, `addDatabaseModelEventHandler`
- [x] **T-2.1.4** Implement `initializeOffsets()`: delegate to `DatabaseModel.getOffsets()` to resume from stored partition offsets

#### 2.2 DatabaseModel JDBC Support

- [x] **T-2.2.1** Implement `JdbcDatabaseModel` with `PlatformTransactionManager` and `JdbcTemplate` injection
- [x] **T-2.2.2** Implement `startTransaction()` returning `TransactionStatus` with `PROPAGATION_REQUIRED`, `ISOLATION_READ_COMMITTED`
- [x] **T-2.2.3** Implement `getOffsets()` querying `partition_offsets` table with parameterized IN clause
- [x] **T-2.2.4** Implement `commitTransaction()` with `batchUpdate` for offset UPSERT + transaction commit/rollback
- [x] **T-2.2.5** Implement multi-dialect UPSERT SQL: `detectDatabaseType()` from JDBC metadata, `getUpsertSql()` for PostgreSQL (`ON CONFLICT`), MySQL/MariaDB (`ON DUPLICATE KEY`), Oracle/SQL Server (`MERGE INTO`)

#### 2.3 DatabaseModel JPA Support

- [x] **T-2.3.1** Implement `JpaDatabaseModel` with `PartitionOffsetRepository` and `PlatformTransactionManager` injection
- [x] **T-2.3.2** Implement `getOffsets()` delegating to `repository.findByPartitionIdIn()`
- [x] **T-2.3.3** Implement `commitTransaction()` with `repository.saveAll()` + transaction commit/rollback
- [x] **T-2.3.4** Define `PartitionOffset` JPA entity with `partitionId` (primary key) and `offset` fields
- [x] **T-2.3.5** Define `PartitionOffsetRepository` extending Spring Data JPA `JpaRepository` with `findByPartitionIdIn()` query method

#### 2.4 DatabaseModel Bean Discovery

- [x] **T-2.4.1** Implement `DatabaseModelImplementationPresentCondition` to scan classpath for `@DatabaseModelInfo`-annotated classes
- [x] **T-2.4.2** Implement `DatabaseModelBeanFactoryPostProcessor` to discover `DatabaseModel` beans, register `DatabaseModelRuntimeFactory` and `DatabaseModelEventHandlerFunctionAdapter` beans
- [x] **T-2.4.3** Implement `DatabaseModelRuntimeFactory` as Spring `FactoryBean<DatabaseModelRuntime>`: read `@DatabaseModelInfo`, collect handler functions, build `KafkaDatabaseModelRuntime`
- [x] **T-2.4.4** Implement `DatabaseModelEventHandlerFunctionAdapter` as reflective adapter for `@DatabaseModelEventHandler` methods

#### 2.5 DatabaseModel Partition

- [x] **T-2.5.1** Implement `DatabaseModelPartition` as `Runnable` + `AutoCloseable`: create consumer, resolve external event partitions via `AkcesRegistry.resolveTopics()`, hard-assign partitions
- [x] **T-2.5.2** Implement `INITIALIZING` phase: call `runtime.initializeOffsets()` to seek consumer to stored offsets; handle GDPR key loading if PII data present
- [x] **T-2.5.3** Implement `LOADING_GDPR_KEYS` phase: consume `Akces-GDPRKeys` partition, process via `GDPRContextRepository`, resume external event partitions when caught up
- [x] **T-2.5.4** Implement `PROCESSING` phase: poll all assigned partitions, process GDPR keys first, then delegate to `runtime.apply()` for external events
- [x] **T-2.5.5** Implement `close()` with `CountDownLatch` 10-second timeout and consumer/GDPR repository cleanup

#### 2.6 DatabaseModel Controller

- [x] **T-2.6.1** Implement `AkcesDatabaseModelController` extending `Thread`, implementing `ConsumerRebalanceListener`, `AutoCloseable`, `ApplicationContextAware`, `AkcesRegistry`
- [x] **T-2.6.2** Implement `INITIALIZING` phase: create control consumer, subscribe to `Akces-Control`, initialize schema storage and registry, validate runtime schemas
- [x] **T-2.6.3** Implement `INITIAL_REBALANCING` phase: seek control topic to beginning, load all `AggregateServiceRecord`s into registry, transition to `REBALANCING`
- [x] **T-2.6.4** Implement `REBALANCING` phase: close revoked partitions, create and start new `DatabaseModelPartition` instances in `ExecutorService`
- [x] **T-2.6.5** Implement `RUNNING` phase: poll control records for dynamic service discovery
- [x] **T-2.6.6** Implement `onPartitionsRevoked()` / `onPartitionsAssigned()` from `ConsumerRebalanceListener`: manage rebalance state transitions
- [x] **T-2.6.7** Implement `AkcesRegistry.resolveTopics()`: find aggregate services that produce a given domain event type
- [x] **T-2.6.8** Implement `close()` with partition cleanup, consumer close, `LivenessState.BROKEN` publishing, and shutdown latch

#### 2.7 DatabaseModel Auto-Configuration

- [x] **T-2.7.1** Create `AkcesDatabaseModelAutoConfiguration` with `@Configuration` and `@PropertySource("classpath:akces-databasemodel.properties")`
- [x] **T-2.7.2** Define `databaseModelBeanFactoryPostProcessor` bean with `@Conditional(DatabaseModelImplementationPresentCondition.class)`
- [x] **T-2.7.3** Define `akcesDatabaseModelJsonCustomizer`, `akcesDatabaseModelSchemaRecordSerde`, consumer factories (protocol, schema, control), GDPR context repository factory, and control record serde beans
- [x] **T-2.7.4** Create `akces-databasemodel.properties` with consumer defaults: `read_committed`, cooperative-sticky, `auto-offset-reset=earliest`

### Phase 3: Reflector Engine

#### 3.1 Reflector Runtime

- [x] **T-3.1.1** Define `ReflectorRuntime` interface with `apply()`, `getLastEvent()`, `getLastResult()`, `getLastException()`, `handleSuccess()`, `handleFailure()`, `validateDomainEventSchemas()`, `shouldHandlePIIData()`, `getDomainEventTypes()`, `getMaxRetries()`, `getRetryBackoffBaseMs()`
- [x] **T-3.1.2** Define `ReflectorResult` enum: `SUCCESS`, `FAILURE`, `RETRY_PENDING`
- [x] **T-3.1.3** Implement `KafkaReflectorRuntime` with per-partition `RetryState` record (`attempt`, `nextAttemptAt`, `lastException`, `eventRecord`)
- [x] **T-3.1.4** Implement `apply()`: resolve domain event type, check backoff, materialize event with GDPR context, invoke handler, manage retry state, return `ReflectorResult`
- [x] **T-3.1.5** Implement linear backoff: `delay = attempt × retryBackoffBaseMs`; total attempts = `maxRetries + 1`
- [x] **T-3.1.6** Implement `handleSuccess()`: resolve `Reflector` from handler function, call `reflector.onSuccess(event, result, commandBus)`
- [x] **T-3.1.7** Implement `handleFailure()`: resolve `Reflector` from handler function, call `reflector.onFailure(event, exception, commandBus)`
- [x] **T-3.1.8** Implement `KafkaReflectorRuntime.Builder`: `setObjectMapper`, `setReflectorInfo`, `addDomainEvent`, `addEventHandlerFunction`

#### 3.2 Reflector Bean Discovery

- [x] **T-3.2.1** Implement `ReflectorImplementationPresentCondition` to scan classpath for `@ReflectorInfo`-annotated classes
- [x] **T-3.2.2** Implement `ReflectorBeanFactoryPostProcessor` to discover `Reflector` beans, register `ReflectorRuntimeFactory` and `ReflectorEventHandlerFunctionAdapter` beans
- [x] **T-3.2.3** Implement `ReflectorRuntimeFactory` as Spring `FactoryBean<ReflectorRuntime>`: read `@ReflectorInfo`, collect handler functions, build `KafkaReflectorRuntime`
- [x] **T-3.2.4** Implement `ReflectorEventHandlerFunctionAdapter` as reflective adapter for `@ReflectorEventHandler` methods; extract return type `R` and `DomainEventType`

#### 3.3 Reflector Partition

- [x] **T-3.3.1** Implement `ReflectorPartition` as `Runnable`, `AutoCloseable`, and `CommandBus`: create consumer + transactional producer, resolve external event partitions, hard-assign
- [x] **T-3.3.2** Implement `INITIALIZING` phase: handle GDPR key loading, transition to `LOADING_GDPR_KEYS` or `PROCESSING`
- [x] **T-3.3.3** Implement `LOADING_GDPR_KEYS` phase: consume GDPR keys, resume external partitions when caught up
- [x] **T-3.3.4** Implement `PROCESSING` phase with partition pause/resume for backoff: `resumeReadyPartitions()` checks `pausedUntil` map, resumes partitions whose backoff has elapsed
- [x] **T-3.3.5** Implement `processRecords()`: process GDPR keys first, then iterate domain events one at a time calling `processEventRecord()`
- [x] **T-3.3.6** Implement `processEventRecord()`: call `runtime.apply()`, handle `SUCCESS` (clear retry state, commit), `RETRY_PENDING` (pause + seek back), `FAILURE` (clear retry, commit with failure)
- [x] **T-3.3.7** Implement `commitEventWithTransaction()`: `beginTransaction` → `handleSuccess`/`handleFailure` → `sendOffsetsToTransaction` → `commitTransaction`; abort + seek back on `InvalidProducerEpochException` or `KafkaException`
- [x] **T-3.3.8** Implement `CommandBus.send()`: validate thread confinement, resolve command type/topic/partition via `AkcesRegistry`, serialize to `CommandRecord`, produce via `KafkaSender`
- [x] **T-3.3.9** Implement `ReflectorPartitionCommandBus` extending `CommandBusHolder`: register/unregister partition as ThreadLocal `CommandBus`
- [x] **T-3.3.10** Implement `close()` with `CountDownLatch` 10-second timeout, consumer/producer/GDPR repository cleanup

#### 3.4 Reflector Controller

- [x] **T-3.4.1** Implement `AkcesReflectorController` extending `Thread`, implementing `ConsumerRebalanceListener`, `AutoCloseable`, `ApplicationContextAware`, `AkcesRegistry`
- [x] **T-3.4.2** Implement `INITIALIZING` phase: create control consumer, subscribe to `Akces-Control`, initialize schema storage and registry, validate runtime schemas, determine partition count
- [x] **T-3.4.3** Implement `INITIAL_REBALANCING` phase: seek control topic to beginning, load aggregate services
- [x] **T-3.4.4** Implement `REBALANCING` phase: close revoked partitions, create and start `ReflectorPartition` instances (with producer factory)
- [x] **T-3.4.5** Implement `RUNNING` phase: poll control records for dynamic service discovery
- [x] **T-3.4.6** Implement `onPartitionsRevoked()` / `onPartitionsAssigned()` with state transitions
- [x] **T-3.4.7** Implement `AkcesRegistry`: `resolveTopics()`, `resolveType()`, `resolveTopic()`, `resolvePartition()` for command routing with Murmur3 hashing
- [x] **T-3.4.8** Implement `close()` with partition cleanup, consumer close, `LivenessState.BROKEN` publishing

#### 3.5 Reflector Auto-Configuration

- [x] **T-3.5.1** Create `AkcesReflectorAutoConfiguration` with `@Configuration` and `@PropertySource("classpath:akces-reflector.properties")`
- [x] **T-3.5.2** Define `reflectorBeanFactoryPostProcessor` bean with `@Conditional(ReflectorImplementationPresentCondition.class)`
- [x] **T-3.5.3** Define consumer factories (protocol, schema, control), producer factory (transactional), GDPR context repository factory, and serde beans
- [x] **T-3.5.4** Create `akces-reflector.properties` with consumer defaults + `acks=all`, idempotent producer

### Phase 4: Shared Components

- [x] **T-4.1** Create `QueryServiceApplication` with `@SpringBootApplication(exclude = KafkaAutoConfiguration.class)`, `@EnableConfigurationProperties(KafkaProperties.class)`, CLI package scanning
- [x] **T-4.2** Register all three auto-configurations in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- [x] **T-4.3** Define `environmentPropertiesPrinter` bean with `@ConditionalOnMissingBean` in each auto-configuration

### Phase 5: Integration Tests

#### 5.1 QueryModel Tests

- [x] **T-5.1.1** Create `WalletQueryModel` and `WalletQueryModelState` test fixtures with `@QueryModelEventHandler(create=true)` and update handlers
- [x] **T-5.1.2** Create `AccountQueryModel` and `AccountQueryModelState` test fixtures
- [x] **T-5.1.3** Create `QueryModelTestConfiguration` Spring test config
- [x] **T-5.1.4** Implement `QueryModelRuntimeTests`: Testcontainers Kafka setup, publish events to index topics, verify hydration returns correct state

#### 5.2 DatabaseModel Tests

- [x] **T-5.2.1** Create `DefaultJdbcModel` test fixture extending `JdbcDatabaseModel` with `@DatabaseModelEventHandler` methods
- [x] **T-5.2.2** Create `DatabaseModelTestConfiguration` Spring test config with Testcontainers Kafka + PostgreSQL
- [x] **T-5.2.3** Create `db/changelog/liquibase.yaml` for test PostgreSQL schema (partition_offsets table + domain-specific tables)
- [x] **T-5.2.4** Implement `DatabaseModelRuntimeTests`: publish events, verify database rows, verify offset tracking and crash recovery

#### 5.3 Reflector Tests

- [x] **T-5.3.1** Create `TestNotificationReflector` test fixture implementing `Reflector<R>` with `@ReflectorEventHandler`, `onSuccess()`, `onFailure()`
- [x] **T-5.3.2** Create `ReflectorTestConfiguration` Spring test config
- [x] **T-5.3.3** Implement `KafkaReflectorRuntimeTest`: unit tests for retry logic, backoff calculation, retry exhaustion
- [x] **T-5.3.4** Implement `ReflectorIntegrationTest`: Testcontainers Kafka, publish events, verify handler invocation and command production

#### 5.4 Shared Test Utilities

- [x] **T-5.4.1** Implement `TestUtils` with helper methods for Kafka topic creation and test data publishing

---

## Gap Analysis

### Identified Gaps (existing TODOs in source code)

| ID | Location | Description | Severity | Status |
|----|----------|-------------|----------|--------|
| **GAP-1** | `DatabaseModelPartition.java:223` | TODO: external events from different topics are not processed in causal order within a partition batch | Medium | Open |
| **GAP-2** | `DatabaseModelPartition.java:232` | TODO: `IOException` during `runtime.apply()` is logged but not escalated; data may be silently lost | High | Open |
| **GAP-3** | `KafkaDatabaseModelRuntime.java:92` | TODO: records across multiple topic partitions are not merged in timestamp or offset order before processing | Medium | Open |
| **GAP-4** | `AkcesQueryModelController.java:138` | TODO: disabled query models should report schema differences in the exception, not just the class name | Low | Open |
| **GAP-5** | `AkcesQueryModelController.java:479` | TODO: `HydrationExecution.complete()` can encounter null state for an existing index topic (should not happen if index is only created with data) | Low | Open |

### Test Coverage Gaps

| ID | Description | Severity |
|----|-------------|----------|
| **GAP-T1** | No test for JPA `JpaDatabaseModel` integration (only JDBC variant tested) | Medium |
| **GAP-T2** | No test for QueryModel with `@PIIData` events (GDPR decryption path in hydration) | Medium |
| **GAP-T3** | No test for QueryModel schema validation failure (disabled runtime path) | Medium |
| **GAP-T4** | No test for DatabaseModel/Reflector partition rebalancing (revoke + reassign) | Medium |
| **GAP-T5** | No test for Reflector `onFailure` callback after retry exhaustion | Medium |
| **GAP-T6** | No test for Reflector transactional command sending (verifying commands appear on target topic) | Medium |
| **GAP-T7** | No test for multi-dialect UPSERT SQL in `JdbcDatabaseModel` (only PostgreSQL tested) | Low |
| **GAP-T8** | No test for `QueryModelIdNotFoundException` path (missing index topic) | Low |
| **GAP-T9** | No test for unrecoverable `KafkaException` causing controller shutdown in any sub-system | Medium |
| **GAP-T10** | No test for `QueryServiceApplication` entry point with CLI package arguments | Low |
