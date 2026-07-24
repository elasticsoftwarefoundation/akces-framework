# Plan: Persistent AgentProcessRepository Implementation

## Problem Statement

Embabel's `AgentProcessRepository` interface currently only has an in-memory implementation
(`InMemoryAgentProcessRepository`). In the Akces Framework, `AgentProcess` instances live only in memory
within the `DefaultAgentPlatform` via this repository. When a pod restarts or crashes, all running agent
processes are lost — any assigned tasks (`AssignedTask` entries in aggregate state) and active memory
distillations (`MemoryDistillation` entries) become orphans with no corresponding `AgentProcess` to resume.

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

3. **Completion**: When `agentProcess.getFinished()` returns true, the runtime may start memory
   distillation (if applicable), emits `AgentTaskFinishedEvent` when the task lifecycle is complete,
   and removes the finished task from aggregate state.

4. **Memory Distillation**: Creates a separate `AgentProcess` for `MemoryDistillerAgent`. Instead of
   running it to completion immediately, the runtime starts it and advances it via the same tick
   mechanism used for other agent processes until it finishes, at which point it extracts
   `MemoryDistillationResult` from the distillation process blackboard.

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

### SimpleAgentProcess / AbstractAgentProcess Field Analysis

To ensure full state restoration, we need to understand every field in the `SimpleAgentProcess` class
hierarchy. `SimpleAgentProcess extends AbstractAgentProcess implements AgentProcess`.

#### AbstractAgentProcess fields (the superclass)

| Field | Type | Mutable? | Persist? | Notes |
|-------|------|----------|----------|-------|
| `id` | `String` | No (final) | **Yes** | Process identity — stored as `processId` |
| `parentId` | `String?` | No (final) | **Yes** | `null` for root processes, parent ID for child (e.g., memory distillation) |
| `agent` | `Agent` | No (final) | **No** — resolve by name | Resolved via `agentName` from `AgentPlatform.agents()` |
| `processOptions` | `ProcessOptions` | No (final) | **No** — use DEFAULT | Akces always uses `ProcessOptions.DEFAULT` |
| `blackboard` | `Blackboard` | No (final) | **Yes** | Core state — serialized as `List<BlackboardEntry>` + bindings + conditions |
| `platformServices` | `PlatformServices` | No (final) | **No** — singleton | Re-injected from `AgentPlatform.getPlatformServices()` |
| `timestamp` | `Instant` | No (final) | **Yes** | Creation time — stored as `createdAt` |
| `_status` | `AtomicReference<AgentProcessStatusCode>` | Yes | **Yes** | Current status (NOT_STARTED, RUNNING, COMPLETED, FAILED, etc.) |
| `_history` | `List<ActionInvocation>` | Yes (append) | **Yes** | Action invocations — each has `actionName`, `timestamp`, `runningTime` |
| `_failureInfo` | `Object?` | Yes | **Yes** | Nullable failure details |
| `_lastWorldState` | `WorldState?` | Yes | **No** — reconstructed | Recomputed by `WorldStateDeterminer` from Blackboard on next `tick()` |
| `_goal` | `Goal?` | Yes | **No** — reconstructed | Set by planner from `WorldState` on next `tick()` |
| `_terminationRequest` | `TerminationSignal?` | Yes | **No** | Transient signal; if set, the process is being killed/terminated — a finished process state is persisted instead |
| `_llmInvocations` | `List<LlmInvocation>` | Yes (append) | **No** | Operational telemetry only; not needed for process resumption |
| `agenticEventListenerToolsStats` | `AgenticEventListenerToolsStats` | Yes | **No** | Telemetry aggregation; rebuilt as tools execute |
| `processContext` | `ProcessContext` | No (final) | **No** — reconstructed | Created from `ProcessOptions` + `PlatformServices` + `AgentProcess` ref |
| `logger` | `Logger` | No (final) | **No** | Infrastructure |

#### SimpleAgentProcess additional fields

| Field | Type | Mutable? | Persist? | Notes |
|-------|------|----------|----------|-------|
| `worldStateDeterminer` | `WorldStateDeterminer` | No (final) | **No** — reconstructed | Created from `Agent` conditions + Blackboard on construction |
| `planner` | `Planner` | No (final) | **No** — reconstructed | Created by `PlannerFactory.createPlanner()` |
| `replanBlacklist` | `Set<String>` | Yes (mutable contents) | **Yes** | Action names that caused failures; prevents re-selecting them during replanning. Must be persisted to avoid repeating failed actions after restart. The field reference is `final`, but the `Set` contents are mutable. |

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
   `ProcessContext`, `WorldStateDeterminer`, and `ProcessOptions` references can be re-injected or
   reconstructed on deserialization since they are singletons or derived from other restored state.

5. **The process can only be resumed at tick boundaries** — Between ticks, the process is in a quiescent
   state where only the Blackboard contents, process metadata, and `replanBlacklist` matter.

6. **`_lastWorldState` and `_goal` are reconstructed automatically** — `AbstractAgentProcess.tick()`
   calls `worldStateDeterminer.determineWorldState()` (which reads from the Blackboard) and stores the
   result in `_lastWorldState`. The planner then derives `_goal` from the world state. No explicit
   persistence needed.

7. **`replanBlacklist` must be persisted** — This `Set<String>` tracks action names that caused failures
   during the current process. Without persisting it, a rehydrated process could re-select a previously
   failed action, causing a retry loop. This field is populated by `SimpleAgentProcess.formulateAndExecutePlan()`
   when an action fails.

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
┌──────────────────────────────────────────────────────────────────┐
│                    AgenticAggregatePartition                      │
│                                                                   │
│  Kafka Transaction (owned by partition's transactional producer) │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  1. producer.beginTransaction()                            │  │
│  │  2. producer.send(AggregateStateRecord → state topic)      │  │
│  │  3. producer.send(DomainEventRecord → event topic)         │  │
│  │  4. producer.send(AgentProcessSnapshotRecord → process     │  │
│  │     state topic) ◄── NEW: write-through to Kafka          │  │
│  │  5. producer.sendOffsetsToTransaction(...)                 │  │
│  │  6. producer.commitTransaction()                           │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                   │
│  After Kafka commit:                                             │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  7. stateRepository.commit()       → RocksDB (agg state)  │  │
│  │  8. processRepository.commitLocal() → RocksDB (processes)  │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                   │
│  On partition assignment:                                        │
│    1. Consume compacted topic to rebuild RocksDB                 │
│    2. Rehydrate AgentProcess objects on demand                   │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

### Kafka Transaction Coordination

**Problem**: The Kafka write-through for agent process snapshots must participate in the **same
Kafka transaction** as the aggregate state writes and domain event produces. This ensures atomicity:
if the transaction aborts, neither aggregate state changes nor process state changes are visible
to consumers.

However, Embabel's `AgentProcessRepository` interface (`save()`/`update()`/`delete()`) is called
by `DefaultAgentPlatform` — it does not have access to `AgenticAggregatePartition`'s transactional
Kafka `Producer`.

**Solution — Deferred Write-Through Pattern**: The `PersistentAgentProcessRepository` captures
snapshot changes in a **pending write buffer** (similar to how `RocksDBAggregateStateRepository`
uses `transactionStateRecordMap`). The actual Kafka send and RocksDB commit happen later, driven
by the `AgenticAggregatePartition`:

```
AgentProcessRepository (SPI — called by DefaultAgentPlatform)
  save()/update()/delete()
    ├── Delegates to InMemoryAgentProcessRepository (immediate in-memory update)
    └── Stages snapshot in pendingWrites buffer (does NOT write to Kafka/RocksDB yet)

AgenticAggregatePartition (transaction coordinator)
  Within a Kafka transaction:
    1. producer.beginTransaction()
    2. runtime.handleCommand() / runtime.resumeNextAgentTask()
       └── internally calls AgentPlatform → AgentProcessRepository.save()/update()
    3. processRepository.sendPending(producer)  ◄── sends staged records to Kafka
    4. producer.commitTransaction()
    5. stateRepository.commit()
    6. processRepository.commitLocal()          ◄── writes staged records to RocksDB
  On abort:
    5. stateRepository.rollback()
    6. processRepository.rollbackLocal()        ◄── discards staged records
```

This pattern mirrors the existing `AggregateStateRepository` coordination exactly:
- `prepare()` → `sendPending()` (buffer records, send to Kafka)
- `commit()` → `commitLocal()` (write to RocksDB after Kafka commit)
- `rollback()` → `rollbackLocal()` (discard buffered records)

The `PersistentAgentProcessRepository` exposes these additional methods (beyond the Embabel SPI)
to the `AgenticAggregatePartition` which calls them at the appropriate transaction boundary points.

**Topic**: `{RuntimeName}-AgentProcessState` (compacted, single partition matching the agentic
aggregate's fixed partition 0). Key = `processId`, Value = `AgentProcessSnapshotRecord` (Protobuf).
Tombstone (null value) on delete.

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
  Instant createdAt             // Maps to AbstractAgentProcess.timestamp
  Object failureInfo            // Nullable, JSON-serializable
  
  // Blackboard state (the core challenge)
  List<BlackboardEntry> entries // Ordered list of blackboard objects
  Map<String, String> namedBindings // name → entry reference
  Set<String> hiddenEntryIds    // References to hidden entries
  Map<String, Boolean> conditions // Boolean conditions
  
  // Execution history
  List<ActionInvocationSnapshot> history
  
  // Planning state
  Set<String> replanBlacklist   // Action names to exclude from replanning (SimpleAgentProcess field)
  // WorldState and Goal are reconstructed by the planner from the Blackboard on the next tick
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

These are re-injected from the Spring/Embabel context or reconstructed on rehydration:

- `Agent` reference → resolved by `agentName` from `AgentPlatform.agents()`
- `Planner` reference → created by `PlannerFactory.createPlanner()` based on agent configuration
- `PlatformServices` → singleton, obtained from `AgentPlatform.getPlatformServices()`
- `ProcessContext` → reconstructed from `ProcessOptions` + `PlatformServices` + `AgentProcess` reference
- `ProcessOptions` → use `ProcessOptions.DEFAULT` (Akces always uses DEFAULT)
- `WorldStateDeterminer` → reconstructed from `Agent` conditions + Blackboard at construction time
- `_lastWorldState` → recomputed from Blackboard by `WorldStateDeterminer` on next `tick()`
- `_goal` → derived from `WorldState` by the planner on next `tick()`
- `_terminationRequest` → transient signal; only set during active kill/terminate operations
- `LlmInvocation` history → operational telemetry, not needed for resumption
- `AgenticEventListenerToolsStats` → telemetry aggregation, rebuilt as tools execute

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
2. Resolve Agent by name from AgentPlatform.agents()
3. Obtain PlatformServices from AgentPlatform.getPlatformServices()
4. Obtain PlannerFactory from Spring context
5. Create new Blackboard (via BlackboardProvider or InMemoryBlackboard)
6. For each BlackboardEntry (in order):
   a. Resolve Class<?> from typeName
   b. Deserialize payload using ObjectMapper
   c. Add to blackboard via addObject()
   d. If entry has named binding, bind it
7. Restore hidden objects
8. Restore conditions
9. Create SimpleAgentProcess directly:
   new SimpleAgentProcess(
     snapshot.processId,      // original ID preserved
     snapshot.parentId,       // parent relationship preserved
     resolvedAgent,           // resolved from AgentPlatform
     ProcessOptions.DEFAULT,  // Akces always uses DEFAULT
     restoredBlackboard,      // rebuilt from snapshot entries
     platformServices,        // from AgentPlatform
     plannerFactory,          // from Spring context
     snapshot.createdAt       // original creation timestamp
   )
10. Restore mutable state:
    - Set status from snapshot (via reflection or Embabel SPI)
    - Replay history entries (List<ActionInvocation>)
    - Set failureInfo if present
    - Restore replanBlacklist contents (access private final Set via reflection, then addAll)
11. Save to AgentProcessRepository so findById(id) returns it
12. Process is ready for tick()
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

### Universal Persistence: All AgentProcess Instances

All `AgentProcess` instances created by an `AgenticAggregate` are persisted — there is no selective
filtering. There are four creation points in Akces:

| Creator | Process Type | Lifecycle | Persist? |
|---------|-------------|-----------|----------|
| `AssignTaskCommandHandlerFunction` | Task assignment | Long-lived, tick-based | **Yes** |
| `AgenticCommandHandlerFunctionAdapter` | Agent-handled command | Long-lived, tick-based | **Yes** |
| `AgenticEventHandlerFunctionAdapter` | Agent-handled external event | Long-lived, tick-based | **Yes** |
| `KafkaAgenticAggregateRuntime.distillMemories()` | Memory distillation | Tick-based | **Yes** |

The implementation uses a **decorator pattern**: `PersistentAgentProcessRepository` wraps the existing
`InMemoryAgentProcessRepository`. On `save()`/`update()`/`delete()`, it delegates to the in-memory
implementation AND stages a snapshot in the pending writes buffer for all processes. This ensures that
memory distillation processes (which also use tick-based processing) survive restarts just like task
assignment processes.

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

**Approach — Direct SimpleAgentProcess Creation**: Create a `SimpleAgentProcess` directly with the
restored Blackboard contents, rather than going through `AgentPlatform.createAgentProcess()` (which
would generate a new ID). The rehydrator:
  - Resolves `Agent` by name from `AgentPlatform.agents()` and obtains `PlatformServices` and
    `PlannerFactory` from the platform/Spring context
  - Constructs a `SimpleAgentProcess(id, parentId, agent, ProcessOptions.DEFAULT, blackboard,
    platformServices, plannerFactory, createdAt)` with the original process ID
  - Restores mutable state: `_status`, `_history`, `_failureInfo`, `replanBlacklist`
  - Note: `_status` can be set via `AbstractAgentProcess.setStatus()` (protected); `_history` items
    can be added; `replanBlacklist` is a `private final` reference to a mutable `Set<String>`. On
    rehydration, the `Set` is initially empty (created during `SimpleAgentProcess` construction).
    To restore its contents, use reflection to access the field and add the persisted entries via
    `Set.addAll()`. Alternatively, a feature request to Embabel for a constructor that accepts an
    initial replan blacklist set may be warranted.
  - The `AgentProcessRepository.findById(id)` returns this recreated process when
    `AgentPlatform.getAgentProcess(id)` is called (since `getAgentProcess()` internally delegates
    to `agentProcessRepository.findById(id)`)
  - `WorldState` and `Goal` are reconstructed by the planner from the Blackboard on the first `tick()`
  - No wrapper/decorator needed — the `SimpleAgentProcess` is a standard Embabel process instance

### Phase 3: RocksDB-backed AgentProcessRepository (Decorator)

**Goal**: Implement the persistent repository using RocksDB with a deferred write-through pattern.

**Deliverables**:
- `PersistentAgentProcessRepository` implementing `AgentProcessRepository` as a **decorator** around
  `InMemoryAgentProcessRepository`
- Embabel SPI methods (`save()`/`update()`/`delete()`/`findById()`/`findByParentId()`):
  - Delegate to the in-memory implementation immediately
  - Stage snapshot in `pendingWrites` buffer for all processes (a `Map<String, AgentProcessSnapshotRecord>`;
    tombstone = null value for deletes)
- Transaction coordination methods (called by `AgenticAggregatePartition`):
  - `sendPending(Producer<String, ProtocolRecord> producer, TopicPartition processStateTp)` — sends
    all pending snapshot records to Kafka using the partition's transactional producer
  - `commitLocal()` — writes all pending snapshots to RocksDB (after Kafka transaction committed)
  - `rollbackLocal()` — discards pending writes buffer, reverts in-memory state if needed
- `findById()` falls back to RocksDB + rehydration when not found in memory
- `findByParentId()` combines results from memory and RocksDB
- RocksDB database configuration (separate DB from aggregate state)
- Snapshot after every `tick()` via the `update()` call from `DefaultAgentPlatform`
- `PersistentAgentProcessRepositoryFactory` matching existing factory patterns
- Integration tests with RocksDB
- **This is the default implementation** (replaces InMemoryAgentProcessRepository as the default)

**Design Decision**: Use a **separate RocksDB database** (not a column family in the aggregate state DB)
to maintain separation of concerns and allow independent lifecycle management.

**Design Decision**: The `PersistentAgentProcessRepository` is both an Embabel SPI implementation
(called by `DefaultAgentPlatform`) and a transaction participant (called by `AgenticAggregatePartition`).
This dual role mirrors how `RocksDBAggregateStateRepository` works: it implements the
`AggregateStateRepository` interface while also providing `prepare()`/`commit()`/`rollback()` for
the partition to coordinate Kafka transactions.

### Phase 4: Kafka Compacted Topic for Cross-Node Recovery

**Goal**: Add Kafka-backed durability so process state survives node failures and partition reassignment.
The writes to Kafka must participate in the same Kafka transaction as aggregate state and domain event
writes.

**Deliverables**:
- New Kafka topic: `{RuntimeName}-AgentProcessState` (compacted, single partition)
- Update `AgenticAggregatePartition` to call `processRepository.sendPending(producer, tp)` inside
  the Kafka transaction, **after** processing commands/events and **before** `commitTransaction()`.
  This mirrors how `stateRepository.prepare()` stages records sent via the transactional producer.
- Update `AgenticAggregatePartition` to call `processRepository.commitLocal()` after
  `producer.commitTransaction()` and `stateRepository.commit()`.
- Update `AgenticAggregatePartition` to call `processRepository.rollbackLocal()` on transaction abort.
- Tombstone records (null value) on `delete()` to enable Kafka compaction cleanup.
- Partition initialization: consume compacted topic to rebuild RocksDB on assignment (same pattern as
  aggregate state topic consumption in the `LOADING_STATE` phase).
- Offset tracking in RocksDB (matching existing aggregate state pattern).
- Integration tests with Kafka (TestContainers).

**Transaction Flow in `processRecords()`**:
```java
producer.beginTransaction();
// ... handle commands, events → produces AggregateStateRecord, DomainEventRecord
// ... DefaultAgentPlatform internally calls processRepository.save()/update()/delete()
//     which stages snapshots in pendingWrites buffer
processRepository.sendPending(producer, processStatePartition);  // ◄── NEW
producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata());
producer.commitTransaction();
stateRepository.commit();                                         // agg state → RocksDB
processRepository.commitLocal();                                  // ◄── NEW: process state → RocksDB
```

**Transaction Flow in `resumeAgentTasks()`**:
```java
producer.beginTransaction();
runtime.resumeNextAgentTask(this::send, stateSupplier, this);
// ... DefaultAgentPlatform internally calls processRepository.update()
processRepository.sendPending(producer, processStatePartition);  // ◄── NEW
producer.commitTransaction();
stateRepository.commit();
processRepository.commitLocal();                                  // ◄── NEW
```

### Phase 5: Integration with Agentic Runtime — Spring Wiring and Startup

**Goal**: Wire the persistent repository into the existing agentic runtime flow as the default.

**Deliverables**:

#### Spring Wiring (in `AgenticAggregateServiceApplication`)

Embabel's `AgentPlatformConfiguration` defines an `agentProcessRepository` `@Bean` method that creates
an `InMemoryAgentProcessRepository`. To override it, Akces defines a `@Bean @Primary` method in the
existing `AgenticAggregateServiceApplication`:

```java
@Bean(name = "agenticServiceAgentProcessRepository")
@Primary
public AgentProcessRepository agentProcessRepository(
        @Value("${akces.rocksdb.baseDir:/tmp/akces}") String baseDir) {
    return new PersistentAgentProcessRepositoryFactory(baseDir).create();
}
```

Since `DefaultAgentPlatform` receives `AgentProcessRepository` via constructor injection, the `@Primary`
bean wins — the `PersistentAgentProcessRepository` (decorator over `InMemoryAgentProcessRepository` +
RocksDB) is injected. **No custom `AgentPlatform` implementation needed.**

#### `AgenticAggregatePartition` Changes

The `AgenticAggregatePartition` already receives the `AggregateStateRepositoryFactory` — it will
additionally receive the `PersistentAgentProcessRepository` (cast from `AgentProcessRepository`) so
it can call the transaction coordination methods (`sendPending()`/`commitLocal()`/`rollbackLocal()`)
at the appropriate points in the transaction lifecycle.

The `PersistentAgentProcessRepository` instance is **shared** between `DefaultAgentPlatform` (which
calls the Embabel SPI methods) and `AgenticAggregatePartition` (which calls the transaction
coordination methods). This is safe because agentic aggregates always use a single partition (partition 0)
and the processing loop is single-threaded.

#### Runtime Integration

- Ensure `KafkaAgenticAggregateRuntime` works correctly with rehydrated processes.
- Handle stale processes: stored processes for tasks no longer in aggregate state → clean up on
  partition initialization.
- Note: Orphan recovery (missing process for assigned task or memory distillation) is **already
  handled** by the existing runtime code. When `KafkaAgenticAggregateRuntime` detects a missing
  `AgentProcess`, it emits failure events (for example `AgentTaskFinishedEvent(FAILED)` or
  `MemoryDistillationFailedEvent`) to clear orphaned aggregate state and avoid retry loops. With
  persistence, this situation should be extremely rare (for example, after data loss or repository
  inconsistency), but the failure-event path remains an important safety net.
- End-to-end integration tests.

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
| Rehydrated `SimpleAgentProcess` doesn't perfectly replicate original process behavior | High | Field analysis confirms all mutable state (`status`, `history`, `failureInfo`, `replanBlacklist`, `blackboard`) is captured; `WorldState`/`Goal` are auto-reconstructed on `tick()`. Comprehensive round-trip tests required. |
| Restoring `replanBlacklist` requires reflection (private final field reference to mutable Set) | Medium | Use reflection to access the field, then `Set.addAll()` to populate contents. Alternatively, request Embabel API extension for a rehydration-friendly constructor. |
| User-defined blackboard objects not Jackson-serializable | Medium | Graceful degradation; log warnings; document requirement |
| Blackboard object ordering semantics change across Embabel versions | Medium | Version the snapshot format; test against Embabel upgrades |
| Large blackboard objects (e.g., full documents) cause storage pressure | Low | Configurable max snapshot size; evict oversized entries |
| Serialization after every tick adds latency | Low | RocksDB writes are fast (local SSD); async Kafka write-through |

## Architect Decisions (Resolved)

The following decisions were made by the architect in response to open questions from the initial plan:

1. **Embabel API Extension**: **No.** Implement the `AgentProcessRepository` interface as a decorator
   around `InMemoryAgentProcessRepository`. Rehydration creates `SimpleAgentProcess` instances
   directly with the original process ID, restored blackboard, and all mutable state (`status`,
   `history`, `failureInfo`, `replanBlacklist`). `WorldState` and `Goal` are auto-reconstructed
   on the first `tick()`. No wrapper/decorator pattern needed. Note: restoring `replanBlacklist`
   (private final `Set<String>`) may require reflection; if this proves fragile, an Embabel API
   extension for a rehydration-friendly constructor may be warranted as a follow-up.

2. **Snapshot Frequency**: **After every tick.** This ensures minimal data loss on crash. The
   performance cost is acceptable given RocksDB's fast local writes.

3. **Universal Persistence**: **Persist all AgentProcess instances.** All processes created by an
   `AgenticAggregate` are persisted, including memory distillation processes. The decorator pattern
   applies the same write-through behavior to every `save()`/`update()`/`delete()` call regardless
   of process type.

4. **Orphan Recovery**: **Already handled.** The existing runtime handles missing processes by
   emitting failure events that clear orphaned `AssignedTask` and `MemoryDistillation` entries when
   a referenced `AgentProcess` cannot be found. With persistent storage, this scenario should be
   extremely rare.

5. **ContextRepository**: **Not in scope.** The Embabel `ContextRepository` stores `Context` objects that
   can persist state across multiple agent processes (similar to a session). Akces does not use this
   feature — `ProcessOptions.DEFAULT` is always used, meaning `contextId` is never set. The Akces
   architecture already provides cross-process state via aggregate state and the memories system
   (`MemoryAwareState`), making `ContextRepository` redundant. If needed in the future, it would be a
   separate effort following the same RocksDB + Kafka pattern.

6. **Default Configuration**: **RocksDB is the default.** The `PersistentAgentProcessRepository`
   (decorator over `InMemoryAgentProcessRepository` + RocksDB) will be the default repository
   configured in `AgenticAggregateServiceApplication`.

7. **Spring Wiring Strategy**: **`@Bean @Primary` in `AgenticAggregateServiceApplication`.** Embabel's
   `AgentPlatformConfiguration.agentProcessRepository()` creates an `InMemoryAgentProcessRepository`.
   Akces overrides it by declaring a `@Bean @Primary` method in the existing
   `AgenticAggregateServiceApplication`. `DefaultAgentPlatform` receives the `@Primary` bean
   via constructor injection — no custom `AgentPlatform` implementation needed.

8. **Kafka Transaction Coordination**: **Deferred write-through pattern.** The
   `PersistentAgentProcessRepository` does not write to Kafka directly. Instead, it stages snapshot
   changes in a pending buffer. The `AgenticAggregatePartition` drives the Kafka writes using its own
   transactional `Producer` (the same one used for aggregate state and domain events), ensuring all
   writes participate in a single Kafka transaction. After the Kafka transaction commits, the partition
   calls `commitLocal()` to persist to RocksDB. This mirrors the existing
   `RocksDBAggregateStateRepository` pattern of `prepare()` → Kafka send → `commit()` → RocksDB write.
