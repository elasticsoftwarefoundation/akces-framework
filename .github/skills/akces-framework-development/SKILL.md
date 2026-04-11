---
name: akces-framework-development
description: >
  Guide for developing the Akces CQRS/Event Sourcing Framework internals. Use this skill when
  working on the framework itself — modifying core modules (api, runtime, shared, client,
  query-support, eventcatalog, agentic), the Kubernetes operator, or the build/release
  infrastructure. Activate when asked about framework architecture, internal design, module
  structure, Kafka integration, state management, schema handling, or contributing to the framework.
---

# Akces Framework — Internal Development Guide

This skill provides guidance for **developing the Akces Framework itself**, not for building
applications on top of it. If you are building Embabel-based AI agents that run on Akces, use the
`embabel-agent-framework` skill instead.

---

## Technology Stack

- **Java 25+** — uses modern Java features (records, sealed interfaces, pattern matching, virtual threads)
- **Apache Kafka 4.x** — KRaft mode (no ZooKeeper), exactly-once semantics, transactional producers
- **Spring Boot 4.x** — auto-configuration, dependency injection, Spring Kafka
- **Maven** — multi-module build, Bill of Materials (`bom/`), annotation processors
- **RocksDB** — embedded key–value store for aggregate state snapshots
- **Confluent Schema Registry** — Avro schema management for commands, events, and state
- **Jackson 3.x** — JSON serialization via `tools.jackson` package (not `com.fasterxml.jackson`)
- **Testcontainers** — Kafka integration tests

---

## Module Map

```
akces-framework/
├── main/
│   ├── api/           → akces-api: Core interfaces, annotations, value objects
│   ├── runtime/       → akces-runtime: Kafka event-sourcing engine, aggregate lifecycle
│   ├── shared/        → akces-shared: Serialization, GDPR/PII, schemas, control plane
│   ├── client/        → akces-client: Command submission and routing
│   ├── query-support/ → akces-query-support: Query model and database model runtime
│   ├── eventcatalog/  → akces-eventcatalog: Compile-time annotation processor for docs
│   └── agentic/       → akces-agentic: AgenticAggregate support (Embabel integration)
├── services/
│   └── operator/      → Kubernetes operator (CRDs: Aggregate, CommandService, QueryService, AgenticAggregate)
├── test-apps/
│   └── crypto-trading/ → Example application
├── bom/               → Bill of Materials POM
```

### Module Dependency Order

Dependencies flow **downward** — lower modules never depend on higher ones:

1. `api` — no internal dependencies (defines interfaces and annotations only)
2. `shared` — depends on `api`
3. `runtime` — depends on `api`, `shared`
4. `client` — depends on `api`, `shared`
5. `query-support` — depends on `api`, `shared`, `runtime`
6. `eventcatalog` — depends on `api` (annotation processor, compile-time only)
7. `agentic` — depends on `api`, `shared`, `runtime` (adds Embabel bridge)

---

## Key Source Packages

### `main/api` — The Programming Model

The API module defines the contracts that application developers code against:

| Package | Contents |
|---------|----------|
| `o.e.akces.aggregate` | `Aggregate<S>`, `AgenticAggregate<S>`, `AggregateState`, `MemoryAwareState`, `TaskAwareState`, `CommandHandlerFunction`, `EventSourcingHandlerFunction`, `EventHandlerFunction`, `EventBridgeHandlerFunction`, `UpcastingHandlerFunction`, `CommandType`, `DomainEventType`, `AggregateStateType`, `SchemaType` |
| `o.e.akces.annotations` | `@AggregateInfo`, `@AgenticAggregateInfo`, `@CommandInfo`, `@DomainEventInfo`, `@AggregateStateInfo`, `@CommandHandler`, `@EventSourcingHandler`, `@EventHandler`, `@EventBridgeHandler`, `@UpcastingHandler`, `@PIIData`, `@AggregateIdentifier`, `@QueryModelInfo`, `@QueryModelEventHandler`, `@DatabaseModelInfo`, `@DatabaseModelEventHandler`, `@QueryModelStateInfo`, `@ReflectorInfo`, `@ReflectorEventHandler` |
| `o.e.akces.commands` | `Command`, `CommandBus` interfaces |
| `o.e.akces.events` | `DomainEvent`, `ErrorEvent` interfaces |
| `o.e.akces.processmanager` | `ProcessManager<S, P>` interface |
| `o.e.akces.query` | `QueryModel<S>`, `QueryModelState`, `DatabaseModel` |

**Rules when modifying `api`:**
- Interfaces and annotations here are the public API. Changes are breaking.
- Keep the module dependency-free (no Kafka, no Spring, no Jackson).
- Add `@Nullable` from Jakarta for optional return values.
- Version all info annotations (`version` field) for schema evolution.

### `main/shared` — Serialization, Schemas, GDPR, Control Plane

| Package | Contents |
|---------|----------|
| `o.e.akces.protocol` | Wire-format records: `CommandRecord`, `DomainEventRecord`, `AggregateStateRecord`, `CommandResponseRecord`, `GDPRKeyRecord`, `SchemaRecord`, `ProtocolRecord`, `PayloadEncoding` |
| `o.e.akces.schemas` | `SchemaRegistry` interface, `KafkaSchemaRegistry` (Confluent), `JsonSchema` wrapper, schema diff utilities |
| `o.e.akces.gdpr` | `GDPRContext`, `GDPRContextHolder` (thread-local), `EncryptingGDPRContext`, `GDPRContextRepository` (RocksDB and in-memory), `GDPRAnnotationUtils`, `GDPRKeyUtils` |
| `o.e.akces.serialization` | `ProtocolRecordSerde`, `AkcesControlRecordSerde`, `SchemaRecordSerde`, `BigDecimalSerializer` |
| `o.e.akces.control` | `AkcesControlRecord`, `AkcesRegistry`, `AggregateServiceRecord` |
| `o.e.akces.errors` | Built-in error events: `AggregateAlreadyExistsErrorEvent`, `AggregateNotFoundErrorEvent`, `CommandExecutionErrorEvent` |
| `o.e.akces.kafka` | `CustomKafkaConsumerFactory`, `CustomKafkaProducerFactory`, `RecordAndMetadata` |
| `o.e.akces.util` | `HostUtils`, `KafkaSender` |

**Rules when modifying `shared`:**
- Protocol records are serialized to Kafka — changes must be backward-compatible.
- GDPR encryption uses per-aggregate keys stored in a dedicated Kafka topic.
- `GDPRContextHolder` uses `ThreadLocal` — be careful with virtual threads.
- Jackson is imported as `tools.jackson` (Jackson 3.x), not `com.fasterxml.jackson`.

### `main/runtime` — The Kafka Event-Sourcing Engine

| Class | Role |
|-------|------|
| `AggregateRuntime` | Interface defining the aggregate lifecycle API |
| `KafkaAggregateRuntime` | Main implementation — command dispatch, event sourcing, schema validation, upcasting |
| `AggregatePartition` | Per-partition event loop: poll commands, process, commit transactions |
| `AggregateRuntimeFactory` / `AggregateBeanFactoryPostProcessor` | Spring bean wiring — reflects on `@AggregateInfo` annotations to build handler maps |
| `AggregateStateRepository` | Interface for state persistence (RocksDB / in-memory implementations) |
| `GDPRContextRepository` | Interface for GDPR key persistence (RocksDB / in-memory) |
| `AkcesAggregateController` | Kubernetes-aware lifecycle controller |

**Key design decisions:**
- **One thread per partition** — each `AggregatePartition` runs on its own thread with its own Kafka consumer and producer.
- **Exactly-once semantics** — uses Kafka transactions (`beginTransaction`, `commitTransaction`) wrapping all produced records.
- **State snapshots** — after processing, updated state is published to the compacted `*-AggregateState` topic so restarts don't need full replay.
- **Schema validation** — every command is validated against its JSON Schema before processing.
- **Handler function adapters** — reflection-based adapters (`CommandHandlerFunctionAdapter`, `EventSourcingHandlerFunctionAdapter`, etc.) wrap user-annotated methods into `CommandHandlerFunction`, `EventSourcingHandlerFunction` etc.

**Kafka topic layout per aggregate `Foo` with `N` partitions:**

| Topic | Partitions | Purpose |
|-------|------------|---------|
| `Foo-Commands` | N | Inbound commands |
| `Foo-DomainEvents` | N | Event store (append-only) |
| `Foo-AggregateState` | N | Compacted state snapshots |

**Partition lifecycle states** (defined in `AggregatePartitionState`):
`STARTING` → `INITIALIZING` → `CATCHING_UP` → `PROCESSING` → `SHUTTING_DOWN`

For agentic aggregates, there is an additional `INITIALIZING_STATE` step between `INITIALIZING` and `CATCHING_UP` for auto-creating singleton state.

### `main/client` — Command Submission

| Class | Role |
|-------|------|
| `AkcesClient` | High-level client for sending commands and receiving responses |
| `AkcesClientController` | Lifecycle controller for the client-side Kafka consumers/producers |
| `CommandServiceApplication` | Spring Boot application entry point for the Command Service |

The client validates commands against their JSON Schema, serializes to Avro, and sends to the correct `*-Commands` topic partition based on the `@AggregateIdentifier`.

### `main/query-support` — Read Models

| Package | Contents |
|---------|----------|
| `o.e.akces.query.models` | `QueryModel` runtime, `QueryModelEventHandler` adapters |
| `o.e.akces.query.database` | `JdbcDatabaseModel`, `JpaDatabaseModel`, `DatabaseModelEventHandler` adapters |
| `o.e.akces.query.reflector` | `Reflector` model for event reflection/forwarding |

Query models consume from `*-DomainEvents` topics and maintain local read-optimized state.

### `main/eventcatalog` — Documentation Generator

An annotation processor (`EventCatalogProcessor`) that generates MDX documentation at compile time:

- Processes `@AggregateInfo`, `@AgenticAggregateInfo`, `@CommandInfo`, `@DomainEventInfo`
- Generates `services/<name>/index.mdx`, `commands/<type>/index.mdx`, `events/<type>/index.mdx`
- Generates JSON Schema files alongside command/event docs
- Configured via `javac` annotation processor options (`eventcatalog.owners`, `eventcatalog.repositoryBaseUrl`, `eventcatalog.schemaDomain`)

### `main/agentic` — AgenticAggregate / Embabel Bridge

This module bridges the Akces event-sourcing engine with the Embabel Agent Framework. For detailed
Embabel concepts (agents, actions, goals, GOAP planning, Blackboard), see the separate
`embabel-agent-framework` skill.

Key internal classes:

| Class | Role |
|-------|------|
| `KafkaAgenticAggregateRuntime` | Decorates `KafkaAggregateRuntime` — adds memory events, task tracking, agent process management |
| `AgenticAggregatePartition` | Extends partition lifecycle with `INITIALIZING_STATE` for auto-create and `resumeAgentTasks()` idle-poll loop |
| `AgenticCommandHandlerFunctionAdapter` | Wraps agent-handled commands — creates `AgentProcess`, populates Blackboard, does NOT tick immediately |
| `AgenticEventHandlerFunctionAdapter` | Same pattern for agent-handled external events |
| `AssignTaskCommandHandlerFunction` | Built-in handler for `AssignTaskCommand` — resolves agent, creates process, emits `AgentTaskAssignedEvent` |
| `AgentProcessSingleTickRunner` | Executes a single tick of an `AgentProcess` and collects domain events |
| `AgentProcessResultTranslator` | Drains `DomainEvent` objects from the Embabel Blackboard after each tick |
| `AgenticAggregateRuntimeFactory` | Spring bean factory — registers built-in memory/task events and adapters |

**Important agentic internals:**
- Agent processes are **never ticked in command/event handlers**. All advancement happens via `resumeAgentTasks()` in the partition's idle-poll cycle.
- Agent resolution: exact name match → `{name}Agent` suffix match → `DefaultAgent` fallback.
- Built-in events (`MemoryStoredEvent`, `MemoryRevokedEvent`, `AgentTaskAssignedEvent`, `AgentTaskFinishedEvent`) are auto-registered.
- Agentic aggregates are always **singletons** (single partition, single replica).

### `services/operator` — Kubernetes Operator

CRDs under `akces.elasticsoftwarefoundation.org/v1`:

| Kind | Short | Purpose |
|------|-------|---------|
| `Aggregate` | `agg` | Standard event-sourced aggregate (configurable replicas) |
| `CommandService` | `cs` | Command validation and routing |
| `QueryService` | `qs` | Query/read model service |
| `AgenticAggregate` | `aag` | Singleton AI-agent aggregate (always 1 replica) |

The operator reconciles each CR into a StatefulSet, Service, and ConfigMap.

---

## Build and Test

### Full build

```bash
mvn clean install
```

### Run tests for a specific module

```bash
cd main/api && mvn test
cd main/shared && mvn test
cd main/runtime && mvn test
cd main/client && mvn test
cd main/query-support && mvn test
cd main/agentic && mvn test
```

### Install dependencies before building downstream modules

When working on a single module, install its upstream dependencies first:

```bash
mvn install -pl main/api -q -DskipTests
mvn install -pl main/shared -q -DskipTests
mvn install -pl main/runtime -q -DskipTests
```

### Known build notes

- The `runtime` and `agentic` modules may have pre-existing compilation issues in controller classes. Use `-Dmaven.compiler.failOnError=false` when installing locally if needed.
- Jackson 3.x is used — import from `tools.jackson`, not `com.fasterxml.jackson`.
- Integration tests use Testcontainers with Kafka — ensure Docker is running.

---

## Coding Conventions

### General

- **Java records** for immutable data (commands, events, state, protocol records).
- **Sealed interfaces** where appropriate for type safety.
- **No exceptions in command handlers** — use error events instead.
- **All commands and events are versioned** — `@CommandInfo(version = N)`, `@DomainEventInfo(version = N)`.
- **`@AggregateIdentifier`** is required on the routing field of every command and event.
- **Apache License 2.0 header** on all source files.
- **`@Nullable`** from Jakarta on optional return values and parameters.

### State Management

- State classes are immutable Java records implementing `AggregateState`.
- `getAggregateId()` must return the aggregate's routing key.
- State mutation methods (e.g., on `MemoryAwareState`, `TaskAwareState`) always return new instances.
- State is persisted via RocksDB locally and snapshotted to Kafka's compacted state topic.

### Schema Evolution

- Introduce new versions of events/commands as separate record classes (e.g., `AccountCreatedEventV2`).
- Write `@UpcastingHandler` methods to convert old versions to new versions.
- Never modify existing event schemas in a backward-incompatible way.
- The `SchemaRegistry` enforces backward compatibility checks.

### GDPR / PII

- Fields annotated with `@PIIData` are encrypted/decrypted transparently.
- Encryption keys are per-aggregate, stored in a dedicated `*-GDPRKeys` Kafka topic.
- `GDPRContextHolder` manages the current encryption context via `ThreadLocal`.
- AgenticAggregates do **not** support GDPR/PII.

### Handler Function Adapters

When adding new handler types or modifying the adapter pattern:

- `CommandHandlerFunctionAdapter` — wraps `@CommandHandler` methods
- `EventSourcingHandlerFunctionAdapter` — wraps `@EventSourcingHandler` methods
- `EventHandlerFunctionAdapter` — wraps `@EventHandler` methods
- `EventBridgeHandlerFunctionAdapter` — wraps `@EventBridgeHandler` methods
- `AggregateBeanFactoryPostProcessor` / `AggregateValidator` — validates annotation consistency at startup

Each adapter uses reflection to discover annotated methods, validates their signatures, and wraps them into the corresponding `*Function` interface.

### Kafka Integration

- Producers use `acks=all`, idempotence enabled, exactly-once semantics.
- Consumers use `read_committed` isolation level.
- Each partition runs in its own thread with its own consumer/producer pair.
- Transactions wrap the full command-processing cycle (command read → event write → state snapshot).
- Topic naming convention: `{AggregateName}-Commands`, `{AggregateName}-DomainEvents`, `{AggregateName}-AggregateState`.

---

## Testing Guidelines

### What NOT to test directly

Do not write dedicated unit tests for annotations, records, interfaces, exception classes, or enums.
Test these indirectly through the components that use them.

### Unit tests

- Test aggregate command handlers: given state + command → expected events.
- Test event-sourcing handlers: given state + event → expected new state.
- Test upcasting handlers: given old event → expected new event.
- Mock external dependencies (Kafka, RocksDB, Schema Registry).

### Integration tests

- Use Testcontainers with Kafka for end-to-end flows.
- Test complete command → event → state cycle.
- Test schema registration and validation.
- Test query model event consumption.

### Test structure

Follow the Arrange-Act-Assert pattern. Use `assertThat` (AssertJ) for assertions.

---

## Common Development Tasks

### Adding a new annotation

1. Define the annotation in `main/api` under `o.e.akces.annotations`.
2. Create the corresponding handler function interface in `o.e.akces.aggregate` (if needed).
3. Create the adapter in `main/runtime` under `o.e.akces.beans`.
4. Register the adapter in `AggregateBeanFactoryPostProcessor`.
5. Update `AggregateValidator` to validate the new annotation's constraints.
6. Update `EventCatalogProcessor` in `main/eventcatalog` if the annotation affects documentation.

### Adding a new built-in event or command

1. Define the record in the appropriate module (`main/shared` for shared, `main/agentic` for agentic).
2. Annotate with `@CommandInfo` or `@DomainEventInfo`.
3. Register in the runtime factory (`AggregateRuntimeFactory` or `AgenticAggregateRuntimeFactory`).
4. Add the corresponding event-sourcing handler if state needs updating.
5. Update the `EventCatalogProcessor` if the type should appear in generated docs.

### Adding a new protocol record type

1. Define the record in `o.e.akces.protocol` in `main/shared`.
2. Add serialization support in `ProtocolRecordSerde`.
3. Update `PayloadEncoding` if a new encoding is needed.
4. Ensure backward compatibility with existing wire format.

### Modifying the partition lifecycle

1. Understand the state machine in `AggregatePartitionState`.
2. `AggregatePartition` handles standard aggregates; `AgenticAggregatePartition` extends it for agentic aggregates.
3. Test state transitions thoroughly — incorrect transitions can cause data loss.

### Adding a new Kubernetes CRD

1. Define the CRD spec in `services/operator`.
2. Implement the reconciler.
3. Add the CRD YAML to the Helm chart.
4. Test with a local Kubernetes cluster (kind/minikube).

---

## Reference Links

- [Akces Framework Repository](https://github.com/elasticsoftwarefoundation/akces-framework)
- [FRAMEWORK_OVERVIEW.md](../../FRAMEWORK_OVERVIEW.md) — Detailed architecture documentation
- [SERVICES.md](../../SERVICES.md) — Kubernetes services and deployment
- [README.md](../../README.md) — Project overview and getting started
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Confluent Schema Registry](https://docs.confluent.io/platform/current/schema-registry/)
- [RocksDB Wiki](https://github.com/facebook/rocksdb/wiki)
