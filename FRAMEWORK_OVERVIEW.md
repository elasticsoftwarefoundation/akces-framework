# Akces Framework — Architecture Overview

This document provides a detailed description of the Akces framework architecture, module structure,
and core concepts. For quick-start instructions see [README.md](README.md). For Kubernetes deployment
details see [SERVICES.md](SERVICES.md).

---

## Module Structure

| Module | Artifact ID | Purpose |
|--------|-------------|---------|
| `main/api` | `akces-api` | Core interfaces, annotations, and value objects defining the programming model |
| `main/runtime` | `akces-runtime` | Kafka-backed event sourcing engine, aggregate lifecycle, state snapshots |
| `main/shared` | `akces-shared` | Serialisation, GDPR/PII encryption, schema utilities, control-plane records |
| `main/client` | `akces-client` | Command submission client and response handling |
| `main/query-support` | `akces-query-support` | Query model and database model runtime |
| `main/eventcatalog` | `akces-eventcatalog` | Compile-time annotation processor that generates EventCatalog MDX documentation |
| `main/agentic` | `akces-agentic` | AgenticAggregate support: singleton aggregates with AI-agent memory and MCP integration |

All modules are included in the `akces-framework-bom` Bill of Materials.

---

## Core Concepts

### Aggregates

Aggregates are the central consistency boundary in Akces. Each aggregate:

- Is annotated with `@AggregateInfo` (name, state class, optional GDPR key generation, index name).
- Implements `Aggregate<S>` where `S extends AggregateState`.
- Handles commands via `@CommandHandler` methods (returns `Stream<DomainEvent>`).
- Rebuilds state from events via `@EventSourcingHandler` methods (returns a new state instance).
- Is partitioned across Kafka partitions; the aggregate identifier determines the partition.

Multiple replicas of an Aggregate service run in parallel, each owning a disjoint set of
partitions.

### Commands and Events

- **Commands** are `@CommandInfo`-annotated records implementing `Command`. They carry an
  `@AggregateIdentifier` field used for routing.
- **Domain Events** are `@DomainEventInfo`-annotated records implementing `DomainEvent`. They are
  immutable facts stored in the Kafka event log.
- Commands are validated against a generated JSON Schema before dispatch.
- The command–event lifecycle is transactional: either all events produced by a handler are
  committed to Kafka, or none are.

### Query Models and Database Models

- **Query Models** (`@QueryModelInfo`, implements `QueryModel<S>`) subscribe to event topics and
  maintain a local RocksDB state for fast reads.
- **Database Models** (`@DatabaseModelInfo`, extends `JdbcDatabaseModel` or `JpaDatabaseModel`)
  persist events into relational tables via JDBC/JPA for complex analytical queries.

### Process Managers

Process managers coordinate workflows that span multiple aggregates. They listen to events and
emit commands (and their own internal events) to advance a multi-step business process.

### Schema Evolution and Upcasting

Schemas are versioned. When a new version of a command or event is introduced, an
`@UpcastingHandler` method converts old instances to the new type so that event-sourced replays
work across schema versions.

### GDPR / PII Data

Fields marked with `@PIIData` are transparently encrypted using per-aggregate encryption keys
stored in a dedicated Kafka topic. Decryption happens at read time using `AkcesGDPRModule`.

---

## AgenticAggregates

### What is an AgenticAggregate?

An **AgenticAggregate** is a special kind of aggregate designed to host an AI agent (e.g. a
Spring AI or Embabel-powered chatbot). It differs from a regular aggregate in the following ways:

| Aspect | Regular Aggregate | AgenticAggregate |
|--------|-------------------|-----------------|
| Replicas | Multiple (Kafka-partitioned) | Always **1** (singleton) |
| Partition count | Configurable | Fixed at **1** per topic |
| State class | Any `AggregateState` | Must also implement `MemoryAwareState` |
| Built-in commands | None | None (memory commands handled by the Embabel layer) |
| Built-in events | None | `MemoryStoredEvent`, `MemoryRevokedEvent` |
| Memory system | N/A | Sliding-window memory (configurable capacity) |
| External events | Via `@EventBridgeHandler` | Listens to **all** partitions directly |
| GDPR/PII | Supported | Not supported (agent memory is not PII) |
| Annotation | `@AggregateInfo` | `@AgenticAggregateInfo` |
| Kubernetes CRD | `AggregateService` | `AgenticAggregate` (short name `aag`) |

### When to use an AgenticAggregate

Use an AgenticAggregate when:

- You need a stateful AI agent that remembers facts across conversations or tool invocations.
- The agent must react to domain events from other aggregates (e.g. to trigger analyses).
- You want the agent's memory to be durable, event-sourced, and auditable.
- You need exactly-one-instance semantics (the agent is a logical singleton in your domain).

Do **not** use an AgenticAggregate for:

- Standard business aggregates that need horizontal scaling across many entities.
- Aggregates that require GDPR key generation or PII encryption.
- Process managers (use `ProcessManager<S, P>` instead).

### Annotation

```java
@AgenticAggregateInfo(
    value = "MyAssistant",           // Bean name (also used as Kafka topic prefix)
    stateClass = MyAssistantState.class,
    description = "My AI assistant",  // Optional — used in EventCatalog docs
    maxMemories = 100                 // Sliding-window capacity (default: 100)
)
public final class MyAssistant implements AgenticAggregate<MyAssistantState> {
    // ... command handlers, event handlers ...
}
```

### State class

The state class must implement both `AggregateState` and `MemoryAwareState`:

```java
@AggregateStateInfo(type = "MyAssistantState", version = 1)
public record MyAssistantState(
    String agenticAggregateId,
    List<AgenticAggregateMemory> memories
) implements AggregateState, MemoryAwareState {

    @Override public String getAggregateId() { return agenticAggregateId(); }

    @Override public List<AgenticAggregateMemory> getMemories() { return memories(); }

    @Override
    public MyAssistantState withMemory(AgenticAggregateMemory m) {
        var updated = new ArrayList<>(memories);
        updated.add(m);
        return new MyAssistantState(agenticAggregateId, updated);
    }

    @Override
    public MyAssistantState withoutMemory(String memoryId) {
        return new MyAssistantState(agenticAggregateId,
            memories.stream().filter(m -> !m.memoryId().equals(memoryId)).toList());
    }
}
```

### Built-in memory events

The framework automatically registers and handles the following built-in events. These are
produced internally by the Embabel layer when the agent stores or revokes memories:

| Event | Type | Description |
|-------|------|-------------|
| `MemoryStoredEvent` | DomainEvent | Emitted when a memory entry is stored |
| `MemoryRevokedEvent` | DomainEvent | Emitted when a memory entry is removed (by the Embabel agent layer) |

You do **not** need to implement event-sourcing handlers for these; the
`KafkaAgenticAggregateRuntime` registers them as built-ins.

### Sliding-window memory

When the number of stored memories exceeds `maxMemories`, the oldest entry is automatically evicted
and a `MemoryRevokedEvent` is emitted by the Embabel agent layer. This keeps memory consumption
bounded while preserving the most recent context.

### Listening to external events

AgenticAggregates can react to domain events from other aggregates using `@EventHandler`-annotated
methods (same as regular aggregates). Internally, the runtime subscribes to **all** partitions of
the external event topic, ensuring the singleton receives every event regardless of which partition
it was written to.

---

## Runtime Architecture

### Kafka topics

For each Aggregate `Foo` with `N` partitions the runtime creates:

| Topic | Description |
|-------|-------------|
| `Foo-Commands` | Inbound command topic (N partitions) |
| `Foo-DomainEvents` | Event store topic (N partitions) |
| `Foo-AggregateState` | Compacted state snapshot topic (N partitions) |

AgenticAggregates always use `N = 1`.

### State storage

Aggregate state is stored in **RocksDB** (embedded key–value store). State snapshots are published
to the `*-AggregateState` compacted topic so that a restarting instance can restore state without
replaying the entire event log.

### Schema Registry

All commands and events are serialised as **Avro** and registered in Confluent Schema Registry.
The `akces-eventcatalog` annotation processor additionally generates **JSON Schema** for
EventCatalog documentation at compile time.

---

## EventCatalog Documentation

The `akces-eventcatalog` annotation processor (`@AutoService(Processor.class)`) runs at
compile time and generates MDX documentation in `META-INF/eventcatalog/services/<AggregateName>/`.

Supported annotations:

| Annotation | Generated output |
|------------|-----------------|
| `@AggregateInfo` | `services/<name>/index.mdx` — standard aggregate service page |
| `@AgenticAggregateInfo` | `services/<name>/index.mdx` — singleton service page with `type: Singleton` and built-in memory commands/events pre-populated |
| `@CommandInfo` | `services/<name>/commands/<type>/index.mdx` + `schema.json` |
| `@DomainEventInfo` | `services/<name>/events/<type>/index.mdx` + `schema.json` |

Configure the processor via `javac` annotation processor options:

| Option | Default | Description |
|--------|---------|-------------|
| `eventcatalog.owners` | `framework-developers` | Comma-separated list of owners |
| `eventcatalog.repositoryBaseUrl` | GitHub URL | Base URL for source links |
| `eventcatalog.schemaDomain` | `akces-framework` | Schema domain prefix |
