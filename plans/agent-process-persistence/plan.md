# Plan: Persistent AgentProcessRepository Implementation

## Problem Statement

Embabel's `AgentProcessRepository` interface currently only has an in-memory implementation
(`InMemoryAgentProcessRepository`). In the Akces Framework, `AgentProcess` instances live only in memory
within the `DefaultAgentPlatform` via this repository. When a pod restarts or crashes, all running agent
processes are lost — any assigned tasks (`AssignedTask` entries in aggregate state) become orphans with no
corresponding `AgentProcess` to resume.

We need a persistent implementation of `AgentProcessRepository` that can survive restarts, while addressing
the core challenge: **serializing and deserializing the Blackboard** (which holds arbitrary Java/Kotlin
objects) so that agent processes can be faithfully restored.

## Current Architecture

### Embabel Interfaces

```
AgentProcessRepository
├── findById(String id) → AgentProcess
├── findByParentId(String parentId) → List<AgentProcess>
├── save(AgentProcess) → AgentProcess
├── update(AgentProcess)
└── delete(AgentProcess)
```

```
AgentProcess extends Blackboard, Timestamped, Timed, OperationStatus, LlmInvocationHistory
├── getId() → String
├── getParentId() → String
├── getBlackboard() → Blackboard
├── getHistory() → List<ActionInvocation>
├── getStatus() → AgentProcessStatusCode
├── getGoal() → Goal
├── getAgent() → Agent
├── getPlanner() → Planner
├── getProcessOptions() → ProcessOptions
├── getFailureInfo() → Object
├── getLastWorldState() → WorldState
├── tick() → AgentProcess
├── run() → AgentProcess
└── ...
```

```
Blackboard (InMemoryBlackboard)
├── _map: Map<String, Object>        // Named bindings
├── _entries: List<Object>           // Type-based storage (ordered)
├── hiddens: Set<Object>             // Hidden objects
├── protectedKeys: Set<String>       // Protected bindings
├── conditions: Map<String, Boolean> // Boolean conditions
└── spawn() → Blackboard             // Child blackboard creation
```

### How Akces Uses AgentProcess

1. **Creation**: `AssignTaskCommandHandlerFunction` creates an `AgentProcess` via
   `agentPlatform.createAgentProcess(agent, ProcessOptions.DEFAULT, bindings)`. Bindings include the
   command, aggregate state, task description, memories, and control flags.

2. **Tick advancement**: `KafkaAgenticAggregateRuntime.resumeNextAgentTask()` retrieves the process
   via `agentPlatform.getAgentProcess(taskId)`, calls `tick()`, drains `DomainEvent` objects from
   the blackboard via `AgentProcessResultTranslator.collectEvents()`, and processes them.

3. **Completion**: When `agentProcess.getFinished()` returns true, the runtime runs memory distillation
   (if applicable), emits `AgentTaskFinishedEvent`, and the task is removed from aggregate state.

4. **Memory Distillation**: Creates a separate short-lived `AgentProcess` for `MemoryDistillerAgent`,
   runs it to completion, and extracts `MemoryDistillationResult` from its blackboard.

### Storage Engines Available in Akces

| Engine | Current Use | Pros | Cons |
|--------|-------------|------|------|
| **Kafka** | Event log, state replication, command routing | Already core infrastructure; durable; partitioned; exactly-once semantics | Not a random-access store; can't easily update-in-place; adds latency for reads |
| **RocksDB** | Aggregate state snapshots, GDPR keys | Fast local reads/writes; ACID via TransactionDB; already integrated | Local to node; not shared across replicas; needs compaction topic for recovery |
| **PostgreSQL** | Query model offsets (query-support module) | Full SQL capabilities; shared across nodes; ACID | Only used on query side today; adds external dependency to aggregate service; higher latency than local store |

## Core Challenge: Blackboard Serialization

The Blackboard can hold **arbitrary Java/Kotlin objects** with varying types:

### Objects placed on the Blackboard by Akces

| Object | Type | Serializable? |
|--------|------|---------------|
| `AssignTask` command | Akces record | Yes (JSON via Jackson) |
| Aggregate state | Akces `AggregateState` record | Yes (JSON via Jackson, with JSON Schema) |
| `AgenticAggregateMemory` entries | Akces record | Yes (JSON via Jackson) |
| Aggregate service metadata | Akces internal | Possibly |
| Control flags (booleans) | Primitives | Yes |
| `DomainEvent` instances (produced by agent) | Akces records | Yes (JSON via Jackson, with JSON Schema) |
| `MemoryDistillationInput` | Akces record | Yes (JSON via Jackson) |
| `MemoryDistillationResult` | Akces record | Yes (JSON via Jackson) |

### Objects placed on the Blackboard by Embabel/Agent framework

| Object | Type | Serializable? |
|--------|------|---------------|
| Agent action return values | User-defined types | Unknown — depends on user code |
| LLM-generated objects | User-defined types | Likely JSON-serializable (created by Jackson) |
| `ActionInvocation` history | Embabel internal | Not `Serializable`; Kotlin data class |
| `WorldState` snapshots | Embabel internal | Complex planner state |
| Planning artifacts | Embabel internal | Internal framework state |

### Key Insights

1. **Embabel objects are NOT `java.io.Serializable`** — `AgentProcess`, `Blackboard`, `ActionInvocation`,
   etc. don't implement `Serializable`.

2. **The Blackboard stores `List<Object>`** — heterogeneous, type-erased at the collection level. Each
   object must be serialized with its type information to allow correct deserialization.

3. **Objects on the Blackboard are mostly Jackson-friendly** in the Akces context — Akces domain objects
   are records with JSON Schema support, and LLM-generated objects are created via Jackson's
   `ObjectMapper`. However, Embabel internal types (planners, agents, world states) are NOT
   Jackson-friendly.

4. **Not all AgentProcess state needs to be serialized** — The `Agent`, `Planner`, `PlatformServices`,
   `ProcessContext`, and `ProcessOptions` references can be re-injected on deserialization since they are
   singletons or can be resolved from the Spring context.

5. **The process can only be resumed at tick boundaries** — Between ticks, the process is in a quiescent
   state where only the Blackboard contents and process metadata matter.

## Proposed Solution: RocksDB-backed AgentProcessRepository with Kafka Compaction Backup

### Rationale for RocksDB + Kafka

- **RocksDB** for the primary store: Fast local reads/writes with ACID transactions. This matches the
  aggregate state storage pattern already used in Akces. Agent processes belong to a specific partition
  (they're created by and resumed from a specific `AgenticAggregatePartition`), so they're inherently
  local to a node — just like aggregate state.

- **Kafka compacted topic** for durability/recovery: When a partition is reassigned to a different node,
  the process state must be rebuilt. A compacted Kafka topic (similar to the existing
  `{AggregateRuntimeName}-AggregateState` pattern) allows rebuilding RocksDB from the log.

- **PostgreSQL excluded**: Adding PostgreSQL as a dependency to the aggregate service would be a
  significant architectural change. The aggregate service is designed to be stateless except for local
  RocksDB + Kafka. PostgreSQL is reserved for the query side.

### Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│              AgenticAggregatePartition               │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │    RocksDBAgentProcessRepository             │   │
│  │    (implements AgentProcessRepository)        │   │
│  │                                              │   │
│  │  Key: processId (String → bytes)             │   │
│  │  Value: AgentProcessSnapshot (Protobuf)      │   │
│  │                                              │   │
│  │  ┌────────────────────────────────────────┐  │   │
│  │  │  RocksDB TransactionDB                 │  │   │
│  │  │  (local to partition)                  │  │   │
│  │  └────────────────────────────────────────┘  │   │
│  └──────────────┬───────────────────────────────┘   │
│                 │ write-through                      │
│  ┌──────────────▼───────────────────────────────┐   │
│  │  Kafka Compacted Topic                       │   │
│  │  "{RuntimeName}-AgentProcessState"           │   │
│  │  Key: processId                              │   │
│  │  Value: AgentProcessSnapshot (Protobuf)      │   │
│  └──────────────────────────────────────────────┘   │
│                                                      │
│  On partition assignment:                            │
│    1. Consume compacted topic to rebuild RocksDB    │
│    2. Rehydrate AgentProcess objects on demand      │
│                                                      │
└─────────────────────────────────────────────────────┘
```

### Serialization Strategy: AgentProcessSnapshot

Instead of trying to serialize the entire `AgentProcess` (which contains non-serializable framework
references), we serialize a **snapshot** of the process state that can be used to reconstruct a
functioning `AgentProcess`.

#### What to Serialize (AgentProcessSnapshot)

```
AgentProcessSnapshot {
  // Process identity
  String processId
  String parentId
  String agentName              // To resolve Agent reference on rehydration
  
  // Process metadata
  AgentProcessStatusCode status
  Instant createdAt
  Object failureInfo            // Nullable, JSON-serializable
  
  // Blackboard state (the core challenge)
  List<BlackboardEntry> entries // Ordered list of blackboard objects
  Map<String, String> namedBindings // name → entry reference
  Set<String> hiddenEntryIds    // References to hidden entries
  Map<String, Boolean> conditions // Boolean conditions
  
  // Execution history
  List<ActionInvocationSnapshot> history
  
  // Planning state
  // WorldState is reconstructed by the planner from the Blackboard on the next tick
}

BlackboardEntry {
  String entryId                // Unique within the snapshot
  String typeName               // Fully qualified class name
  byte[] payload                // JSON-serialized object bytes
  PayloadEncoding encoding      // JSON
}

ActionInvocationSnapshot {
  String actionName
  Instant timestamp
  long runningTimeMillis
}
```

#### What NOT to Serialize

These are re-injected from the Spring/Embabel context on rehydration:

- `Agent` reference → resolved by `agentName` from `AgentPlatform.agents()`
- `Planner` reference → created by `PlannerFactory` based on agent configuration
- `PlatformServices` → singleton, injected from `AgentPlatform`
- `ProcessContext` → reconstructed from process ID and platform services
- `ProcessOptions` → use `ProcessOptions.DEFAULT` (Akces always uses DEFAULT)
- `LlmInvocation` history → operational telemetry, not needed for resumption

#### Blackboard Entry Serialization

For each object on the Blackboard:

1. **Determine the type**: Use `object.getClass().getName()` for the fully qualified class name.
2. **Serialize to JSON**: Use Jackson `ObjectMapper` (already available in Akces) to serialize.
3. **Store type + payload**: Each entry carries its type name so the deserializer knows which class
   to deserialize into.

**Type resolution on deserialization**:
- For Akces domain types (commands, events, state): Resolve via the `SchemaRegistry` using the
  registered `CommandType`, `DomainEventType`, or `AggregateStateType`.
- For Embabel/user types: Use `Class.forName(typeName)` with the application classloader.
- For unknown types: Log a warning and skip the entry (graceful degradation).

#### Handling the Ordering Problem

The Blackboard maintains insertion order, and the planning system relies on "latest of type" semantics.
The serialization preserves this by storing entries as an **ordered list** with stable IDs. The hidden
set references entry IDs to maintain hide semantics across serialization boundaries.

### Rehydration Flow

When a partition is assigned and we need to resume an agent process:

```
1. Load AgentProcessSnapshot from RocksDB (rebuilt from Kafka if needed)
2. Resolve Agent by name from AgentPlatform
3. Create new InMemoryBlackboard
4. For each BlackboardEntry (in order):
   a. Resolve Class<?> from typeName
   b. Deserialize payload using ObjectMapper
   c. Add to blackboard via addObject()
   d. If entry has named binding, bind it
5. Restore hidden objects
6. Restore conditions
7. Create AgentProcess via AgentPlatform.createAgentProcess()
   with restored blackboard contents as bindings
8. Restore status, history, and other metadata
9. Return process ready for tick()
```

### Alternative Considered: Direct AgentProcess Serialization via Kotlin Serialization

Embabel is written in Kotlin. We could potentially use Kotlin serialization (`kotlinx.serialization`)
to serialize `AgentProcess` objects directly. However:

- Embabel classes are not annotated with `@Serializable`
- Internal framework state (planners, world state determiners) cannot be meaningfully serialized
- This would create a tight coupling to Embabel's internal implementation details
- Any Embabel version upgrade could break serialization compatibility

**Conclusion**: The snapshot approach is superior because it serializes only the *meaningful* state
(what's on the blackboard, what actions ran, what's the status) and reconstructs framework references
from the running context.

## Implementation Phases

### Phase 1: AgentProcessSnapshot Model and Serialization

**Goal**: Define the snapshot data model and implement serialization/deserialization.

**Deliverables**:
- `AgentProcessSnapshot` record with all fields defined above
- `BlackboardEntry` record for individual blackboard objects
- `ActionInvocationSnapshot` record for history entries
- `AgentProcessSnapshotSerializer` — converts `AgentProcess` → `AgentProcessSnapshot` → `byte[]`
- `AgentProcessSnapshotDeserializer` — converts `byte[]` → `AgentProcessSnapshot`
- Protobuf schema definition for the snapshot (matching existing Akces patterns)
- Unit tests for round-trip serialization with various blackboard object types

**Key Decisions**:
- Use Protobuf wrapper (matching existing `AggregateStateRecord` pattern) with JSON payloads for
  blackboard entries
- Register the Protobuf schema in the `ProtocolRecordSerde`

### Phase 2: AgentProcess Rehydration

**Goal**: Implement the logic to reconstruct a functioning `AgentProcess` from an `AgentProcessSnapshot`.

**Deliverables**:
- `AgentProcessRehydrator` — takes a snapshot + `AgentPlatform` and produces a live `AgentProcess`
- Type resolution strategy (SchemaRegistry-based for Akces types, classloader-based for others)
- Handle graceful degradation for unresolvable types
- Unit tests for rehydration with mock AgentPlatform

**Key Challenge**: Embabel's `DefaultAgentPlatform.createAgentProcess()` creates a fresh process. We
need a way to create a process and then populate it with restored state. Options:
  - a) Use `createAgentProcess()` with restored bindings, then use reflection to set status/history
  - b) Propose an Embabel API extension (e.g., `restoreAgentProcess(snapshot)`) — preferred long-term
  - c) Create a `RestoredAgentProcess` wrapper that delegates to a fresh process but overrides metadata

The recommended approach is **(c)** for the initial implementation, with a proposal to Embabel for
**(b)** as a follow-up.

### Phase 3: RocksDB-backed AgentProcessRepository

**Goal**: Implement the persistent repository using RocksDB.

**Deliverables**:
- `RocksDBAgentProcessRepository` implementing `AgentProcessRepository`
- RocksDB database configuration (separate DB or column family within existing aggregate state DB)
- Write-through to RocksDB on `save()` and `update()`
- Delete from RocksDB on `delete()`
- `findById()` and `findByParentId()` with RocksDB reads + deserialization
- Lazy rehydration (only rehydrate when the process is actually needed, not on startup)
- `RocksDBAgentProcessRepositoryFactory` matching existing factory patterns
- Integration tests with RocksDB

**Design Decision**: Use a **separate RocksDB database** (not a column family in the aggregate state DB)
to maintain separation of concerns and allow independent lifecycle management.

### Phase 4: Kafka Compacted Topic for Cross-Node Recovery

**Goal**: Add Kafka-backed durability so process state survives node failures and partition reassignment.

**Deliverables**:
- New Kafka topic: `{RuntimeName}-AgentProcessState` (compacted)
- Write-through from `RocksDBAgentProcessRepository` to Kafka on save/update
- Tombstone records on delete
- Partition initialization: consume compacted topic to rebuild RocksDB on assignment
- Integration with existing `AgenticAggregatePartition` startup flow
- Offset tracking in RocksDB (matching existing aggregate state pattern)
- Integration tests with Kafka (TestContainers)

### Phase 5: Integration with Agentic Runtime

**Goal**: Wire the persistent repository into the existing agentic runtime flow.

**Deliverables**:
- Replace `InMemoryAgentProcessRepository` with `RocksDBAgentProcessRepository` in
  `AgenticAggregateServiceApplication` configuration
- Update `AgenticAggregateRuntimeFactory` to create the persistent repository
- Update `KafkaAgenticAggregateRuntime` to handle rehydrated processes correctly
- Handle orphan detection: assigned tasks in aggregate state without a corresponding stored process
  → re-create from task metadata or emit failure event
- Handle stale processes: stored processes for tasks no longer in aggregate state → clean up
- Configuration properties for enabling/disabling persistence
- End-to-end integration tests

### Phase 6: Testing and Documentation

**Goal**: Comprehensive testing and documentation.

**Deliverables**:
- Unit tests for all new components
- Integration tests with Kafka + RocksDB (TestContainers)
- Failure scenario tests (node crash, partition reassignment, corrupt data)
- Documentation updates (JavaDoc, architecture docs)
- Migration guide for existing deployments

## Risk Analysis

| Risk | Impact | Mitigation |
|------|--------|------------|
| Embabel `createAgentProcess()` doesn't support state restoration | High | Use wrapper/decorator pattern; propose API extension to Embabel |
| User-defined blackboard objects not Jackson-serializable | Medium | Graceful degradation; log warnings; document requirement |
| Blackboard object ordering semantics change across Embabel versions | Medium | Version the snapshot format; test against Embabel upgrades |
| Large blackboard objects (e.g., full documents) cause storage pressure | Low | Configurable max snapshot size; evict oversized entries |
| Serialization adds latency to each tick cycle | Low | Async write-through; batch updates; only snapshot on meaningful changes |

## Open Questions for Architect

1. **Embabel API Extension**: Should we propose a `restoreAgentProcess(snapshot)` method to the Embabel
   project, or is the wrapper approach sufficient long-term?

2. **Snapshot Frequency**: Should we snapshot after every `tick()`, or only on status transitions
   (RUNNING → COMPLETED, etc.)? Every tick is safest but most expensive.

3. **Memory Distillation Processes**: The `MemoryDistillerAgent` processes are short-lived (run to
   completion synchronously). Should they also be persisted, or only the main task processes?

4. **Orphan Recovery Strategy**: When an assigned task exists in aggregate state but no stored process
   is found, should we (a) re-create the process from scratch (losing progress), (b) emit a failure
   event, or (c) both with configuration?

5. **ContextRepository**: Embabel also has a `ContextRepository` with only in-memory implementation.
   Should this plan also cover persistent context storage, or is that a separate effort?

6. **Backward Compatibility**: Should the RocksDB repository be the default, or should it be opt-in
   with `InMemoryAgentProcessRepository` remaining the default?
