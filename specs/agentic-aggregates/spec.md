# Agentic Aggregates — Feature Specification

> **Status:** Migrated (brownfield)
> **Module:** `main/agentic` (`akces-agentic`)
> **Source:** 26 files, ~5 752 lines | 25 test files, ~4 981 test lines

---

## 1 Overview

The Agentic Aggregates module extends the Akces CQRS/Event Sourcing Framework with
AI-powered event-driven processing by integrating the **Embabel Agent Framework**.
It introduces a new aggregate variant — the **AgenticAggregate** — whose command and
event handlers can be delegated to Embabel AI agents that use GOAP planning, LLM
reasoning, and tool use to produce domain events.

Key capabilities:

| Capability | Description |
|---|---|
| Agent-delegated command handling | Commands annotated in `agentHandledCommands` are routed to an Embabel `AgentProcess` instead of a deterministic handler |
| Agent-delegated event handling | External events annotated in `agentHandledEvents` are routed to an Embabel `AgentProcess` |
| Task assignment | Built-in `AssignTaskCommand` creates a named `AgentProcess` and emits `AgentTaskAssignedEvent` |
| Memory management | Agent memories are persisted as domain events (`MemoryStored`, `MemoryRevoked`) and held in aggregate state via `MemoryAwareState` |
| Memory distillation | Post-task-completion LLM analysis extracts relevant memories and revokes outdated ones |
| Tick-based execution | Agent processes are advanced tick-by-tick during the partition's idle-poll cycle, not inline with command handling |
| Singleton partition model | Each agentic aggregate runs on a single hard-assigned Kafka partition (partition 0) |
| Auto-create | Aggregate state is automatically initialised via `AgenticAggregate.getCreateDomainEvent()` on first boot |
| Jackson 2/3 compatibility | Custom `Jackson3OutputConverter` and `Jackson3ChatClientLlmOperations` bridge victools 4.x (Embabel) ↔ 5.x (Akces) |

---

## 2 User Scenarios

### US-1: Define an agentic aggregate with `@AgenticAggregateInfo`

**Given** a developer annotates a class with `@AgenticAggregateInfo` and implements
`AgenticAggregate<S>`,
**When** the Spring Boot application starts via `AgenticAggregateServiceApplication`,
**Then** `AgenticAggregateBeanFactoryPostProcessor` discovers the bean, scans handler
methods, and registers:
- An `AgenticAggregateRuntimeFactory` that creates a `KafkaAgenticAggregateRuntime`
- An `AkcesAgenticAggregateController` that manages the `AgenticAggregatePartition`
- Built-in command/event types (`ASSIGN_TASK_COMMAND_TYPE`, all 8 built-in event types)
- `AgenticCommandHandlerFunctionAdapter` for each `agentHandledCommands` entry
- `AgenticEventHandlerFunctionAdapter` for each `agentHandledEvents` entry
- `AssignTaskCommandHandlerFunction` (always registered)

### US-2: Assign a task to an AI agent via `AssignTaskCommand`

**Given** a running agentic aggregate with at least one Embabel agent on the platform,
**When** a client sends an `AssignTaskCommand` with `agenticAggregateId`,
`taskDescription`, `requestingParty`, and optional `taskMetadata`,
**Then** `AssignTaskCommandHandlerFunction`:
1. Populates an agent blackboard with command, state, memories, aggregate services,
   and condition flags (`isCommandProcessing=true`, `isTaskAssignment=true`)
2. Resolves an `Agent` via name matching (exact → `{name}Agent` suffix → `DefaultAgent`)
3. Creates an `AgentProcess` on the `AgentPlatform` (does **not** tick)
4. Emits `AgentTaskAssignedEvent` containing the process ID

**And** the built-in event-sourcing handler adds the task to `TaskAwareState`.

**And** the partition idle-poll (`resumeNextAgentTask`) later drives the process
tick-by-tick. When the process finishes, an `AgentTaskFinishedEvent` is emitted.

### US-3: Store and revoke agent memories as domain events

**Given** an agentic aggregate whose state implements `MemoryAwareState`,
**When** the runtime emits `MemoryStoredEvent` or `MemoryRevokedEvent`,
**Then** the built-in event-sourcing handlers:
- `onMemoryStored` → appends `AgenticAggregateMemory` to state via `withMemory()`
- `onMemoryRevoked` → removes the memory by `memoryId` via `withoutMemory()`

Memory events carry: `memoryId` (UUID), `subject`, `fact`, `citations`, `reason`,
and `storedAt`/`revokedAt` timestamps.

### US-4: Distill agent memories after task completion

**Given** an agent process finishes with status `COMPLETED` and a `MemoryDistillerAgent`
is deployed,
**When** `resumeAssignedTask` detects the completed process,
**Then** it:
1. Emits `AgentTaskFinishedEvent`
2. Creates a `MemoryDistillationInput` with task, history, blackboard objects, current
   memories, `maxTotalMemories`, and `maxMemoriesAdded`
3. Launches a `MemoryDistillerAgent` process and emits `MemoryDistillationStartedEvent`
4. On subsequent idle-poll ticks, the distillation process is advanced
5. When finished, `collectDistillationResult` translates the `MemoryDistillationResult`
   into `MemoryStoredEvent`/`MemoryRevokedEvent` instances (enforcing net capacity
   limit) and emits `MemoryDistillationFinishedEvent`

Constraint: `stored.size() - revoked.size() ≤ min(maxTotalMemories - current, maxMemoriesAdded)`.

### US-5: Resume agent processes across aggregate partitions

**Given** an agentic aggregate partition with active assigned tasks or memory
distillations,
**When** the partition idle-poll cycle fires (no pending commands/events),
**Then** `resumeNextAgentTask`:
1. Materialises state from the state repository
2. Collects all `AgentProcessAware` items (assigned tasks + distillations) into a
   unified list
3. Selects the next item via a round-robin `AtomicInteger` counter
4. Retrieves the `AgentProcess` from the `AgentPlatform`
5. If the process is missing (crash/restart), emits a failure event
   (`AgentTaskFinished(FAILED)` or `MemoryDistillationFailed`) to clear state
6. Otherwise, performs a single tick and processes resulting domain events

---

## 3 Requirements

### R-1: Built-in type registration

The `AgenticAggregateRuntime` interface defines 8 built-in `DomainEventType` constants
and 1 built-in `CommandType`:

| Constant | Type name | Version |
|---|---|---|
| `ASSIGN_TASK_COMMAND_TYPE` | `AssignTask` | 1 |
| `AGENT_TASK_ASSIGNED_TYPE` | `AgentTaskAssigned` | 1 |
| `AGENT_TASK_FINISHED_TYPE` | `AgentTaskFinished` | 1 |
| `MEMORY_STORED_TYPE` | `MemoryStored` | 1 |
| `MEMORY_REVOKED_TYPE` | `MemoryRevoked` | 1 |
| `MEMORY_DISTILLATION_STARTED_TYPE` | `MemoryDistillationStarted` | 1 |
| `MEMORY_DISTILLATION_FINISHED_TYPE` | `MemoryDistillationFinished` | 1 |
| `MEMORY_DISTILLATION_FAILED_TYPE` | `MemoryDistillationFailed` | 1 |

All built-in types are automatically registered by `AgenticAggregateRuntimeFactory`
and validated against the schema registry on startup.

### R-2: Agent resolution strategy

Agent resolution follows a deterministic fallback chain:
1. **Exact match:** `agent.getName().equals(aggregateName)`
2. **Suffix match:** `agent.getName().equals(aggregateName + "Agent")`
3. **Default fallback:** agent with name `"Default"` (`DefaultAgent.AGENT_NAME`)
4. **Failure:** `IllegalStateException` if no `DefaultAgent` is deployed

### R-3: Tick-only execution model

Agent processes are **never** ticked inline with command or event handling. The
`apply()` methods of `AgenticCommandHandlerFunctionAdapter`,
`AgenticEventHandlerFunctionAdapter`, and `AssignTaskCommandHandlerFunction` all
return `Stream.empty()` (or only the assignment event) and rely on the partition's
idle-poll cycle to advance processes.

### R-4: Singleton partition

Every agentic aggregate uses a single hard-assigned Kafka partition (`AGENTIC_PARTITION = 0`).
No consumer-group rebalancing is required. The partition subscribes to **all** partitions
of external event topics to ensure no events are missed.

### R-5: Index-based cursor tracking

`AgentProcessResultTranslator` uses an `int[1]` array stored on the blackboard under
key `"_akces_processedIndex"` to track the last-processed object index. This avoids
maintaining a full set of processed events and is more economical for sequential
blackboard access.

### R-6: Jackson 2/3 compatibility layer

Akces uses victools 5.x (Jackson 3 / `tools.jackson`). Embabel uses victools 4.x
(Jackson 2 / `com.fasterxml.jackson`). The compatibility layer:

- `Jackson3OutputConverter`: Generates JSON Schema via victools 5.x + Jackson 3 for
  the `getFormat()` prompt, deserialises LLM responses via Jackson 2 with a lenient
  mapper (trailing commas, single quotes, unquoted field names, malformed escaped
  quotes)
- `Jackson3ChatClientLlmOperations`: `@Primary` `@Service` that overrides
  `createOutputConverter()` to use `Jackson3OutputConverter` wrapped in the standard
  Embabel decorator chain (SuppressThinking → WithExample → ExceptionWrapping)

### R-7: Built-in event-sourcing handlers

`KafkaAgenticAggregateRuntime` provides static `handleBuiltInEvent()` dispatcher
routing all 7 built-in event types:

| Event | Handler | State interface |
|---|---|---|
| `MemoryStoredEvent` | `onMemoryStored` | `MemoryAwareState.withMemory()` |
| `MemoryRevokedEvent` | `onMemoryRevoked` | `MemoryAwareState.withoutMemory()` |
| `AgentTaskAssignedEvent` | `onAgentTaskAssigned` | `TaskAwareState.withAssignedTask()` |
| `AgentTaskFinishedEvent` | `onAgentTaskFinished` | `TaskAwareState.withoutAssignedTask()` |
| `MemoryDistillationStartedEvent` | `onMemoryDistillationStarted` | `MemoryAwareState.withMemoryDistillation()` |
| `MemoryDistillationFinishedEvent` | `onMemoryDistillationFinished` | `MemoryAwareState.withoutMemoryDistillation()` |
| `MemoryDistillationFailedEvent` | `onMemoryDistillationFailed` | `MemoryAwareState.withoutMemoryDistillation()` |

### R-8: Auto-create on first boot

When `AgenticAggregatePartition` detects no existing state during initialisation, it
calls `AgenticAggregateRuntime.initializeState()` which invokes
`AgenticAggregate.getCreateDomainEvent()` and applies it through the create handler.

### R-9: Orphan process recovery

When `resumeNextAgentTask` cannot find an `AgentProcess` on the platform (e.g. after
restart), it emits:
- `AgentTaskFinishedEvent(FAILED)` for orphaned `AssignedTask`s
- `MemoryDistillationFailedEvent` for orphaned `MemoryDistillation`s

This ensures aggregate state is cleaned up without manual intervention.

### R-10: Spring Boot application entry point

`AgenticAggregateServiceApplication` mirrors `AggregateServiceApplication` but:
- Loads `akces-agenticservice.properties`
- Scans `org.elasticsoftware.akces.agentic.embabel` for component scanning
- Registers manual Kafka factories (no Spring Kafka auto-configuration)
- Registers `BigDecimalSerializer`, GDPR module, `ObservationRegistry.NOOP`
- Registers Anthropic LLM services (Sonnet 4.6, Opus 4.6, Haiku 4.5) as
  `SpringAiLlmService` beans

---

## 4 Non-functional Requirements

- **NFR-1:** Agent process ticking must not block the Kafka consumer poll loop beyond
  the configured `max.poll.interval.ms`
- **NFR-2:** Memory distillation must enforce capacity limits to prevent unbounded
  state growth
- **NFR-3:** The Jackson 2/3 compatibility layer must be transparent to agent
  developers — no manual Jackson version handling required
- **NFR-4:** Schema validation for all built-in types must occur at startup, failing
  fast on incompatible schemas

---

## 5 Out of Scope

- Multi-partition agentic aggregates (currently singleton partition 0 only)
- EventBridge handlers on agentic aggregates (not supported)
- GDPR-keyed agentic aggregates
- Indexed agentic aggregates
- PII-annotated agentic aggregate state
