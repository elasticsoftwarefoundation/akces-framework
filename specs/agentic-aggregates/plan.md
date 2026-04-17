# Agentic Aggregates — Implementation Plan

> **Module:** `main/agentic` (`akces-agentic`)
> **Status:** Migrated — documenting existing implementation

---

## 1 Tech Context

| Dimension | Detail |
|---|---|
| Language | Java 25+ (records, sealed types, pattern matching) |
| Build | Maven, parent `akces-framework-main` v0.12.1-SNAPSHOT |
| Framework | Spring Boot 4.x (no Spring Kafka auto-config) |
| Messaging | Apache Kafka 4.x (KRaft), single-partition hard-assign |
| State store | RocksDB via `AggregateStateRepositoryFactory` |
| Schema | victools 5.x JSON Schema (Jackson 3 / `tools.jackson`) for Akces |
| AI runtime | Embabel Agent Framework (`embabel-agent-starter`), Spring AI 2.x |
| AI models | Spring AI Anthropic (Claude Sonnet 4.6, Opus 4.6, Haiku 4.5) |
| Jackson compat | victools 4.x / Jackson 2 (`com.fasterxml.jackson`) for Embabel; bridged via custom converters |
| Test | JUnit 5, Spring Boot Test, Testcontainers (Kafka), `@EnabledIfEnvironmentVariable` for LLM tests |

### Key Dependencies (from `pom.xml`)

```xml
akces-api, akces-runtime              <!-- Akces core -->
embabel-agent-starter                 <!-- Embabel AI agent framework -->
spring-ai-starter-mcp-client          <!-- Spring AI MCP Client -->
spring-ai-starter-model-anthropic     <!-- Spring AI Anthropic (optional) -->
spring-ai-starter-model-openai        <!-- Spring AI OpenAI (optional) -->
spring-ai-starter-model-google-genai  <!-- Spring AI Google GenAI (optional) -->
spring-boot-jackson2                  <!-- Jackson 2 compat for Embabel -->
spring-boot-starter-web               <!-- Web starter -->
spring-retry                          <!-- Retry support -->
testcontainers-kafka                  <!-- Integration test Kafka -->
```

---

## 2 Architecture

### 2.1 Layer Decomposition

```
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application Layer                │
│  AgenticAggregateServiceApplication                             │
│  ├─ Kafka bean wiring (producers, consumers, admin, serdes)     │
│  ├─ Jackson customiser (BigDecimal, GDPR module)                │
│  └─ LLM service beans (Anthropic models)                        │
├─────────────────────────────────────────────────────────────────┤
│                    Bean Discovery Layer                          │
│  AgenticAggregateBeanFactoryPostProcessor                       │
│  ├─ Scans @AgenticAggregateInfo beans                           │
│  ├─ Processes handler methods (command, event, event-sourcing)  │
│  ├─ Registers AgenticAggregateRuntimeFactory                    │
│  └─ Registers AkcesAgenticAggregateController                   │
├─────────────────────────────────────────────────────────────────┤
│                    Runtime Factory Layer                         │
│  AgenticAggregateRuntimeFactory (FactoryBean)                   │
│  ├─ Creates KafkaAggregateRuntime delegate                      │
│  ├─ Wraps in KafkaAgenticAggregateRuntime                       │
│  ├─ Registers built-in types (1 cmd + 8 events)                 │
│  ├─ Creates AgenticCommandHandlerFunctionAdapter instances       │
│  ├─ Creates AgenticEventHandlerFunctionAdapter instances         │
│  └─ Creates AssignTaskCommandHandlerFunction                    │
├─────────────────────────────────────────────────────────────────┤
│                    Runtime Layer                                 │
│  KafkaAgenticAggregateRuntime (AgenticAggregateRuntime)         │
│  ├─ Delegates to KafkaAggregateRuntime for all standard ops     │
│  ├─ Built-in event-sourcing handlers (7 handlers)               │
│  ├─ resumeNextAgentTask() — round-robin task/distillation tick  │
│  ├─ hasActiveAgentTasks() — fast-path check                     │
│  ├─ initializeState() — auto-create via getCreateDomainEvent()  │
│  ├─ getMemories() — extract from MemoryAwareState               │
│  └─ Memory distillation lifecycle management                    │
├─────────────────────────────────────────────────────────────────┤
│                    Partition Layer                               │
│  AgenticAggregatePartition (Runnable, CommandBus)               │
│  ├─ Hard-assigns partition 0 (no rebalancing)                   │
│  ├─ State initialisation → INITIALIZING → LOADING_STATE         │
│  ├─ Command/event processing loop (FIFO)                        │
│  ├─ Idle-poll cycle → resumeNextAgentTask()                     │
│  └─ Subscribes to all partitions of external topics             │
├─────────────────────────────────────────────────────────────────┤
│                    Controller Layer                              │
│  AkcesAgenticAggregateController                                │
│  ├─ Schema registration & validation                            │
│  ├─ Akces-Control topic interaction                             │
│  ├─ Kafka topic creation (commands, events, state)              │
│  └─ Partition lifecycle management (start, shutdown)            │
├─────────────────────────────────────────────────────────────────┤
│                    Embabel Integration Layer                     │
│  DefaultAgent (UTILITY planner, fallback)                       │
│  MemoryDistillerAgent (GOAP planner, distillation action)       │
│  Jackson3ChatClientLlmOperations (@Primary, converter bridge)   │
│  Jackson3OutputConverter (victools 5.x schema gen + Jackson 2)  │
│  MemoryDistillationInput / MemoryDistillationResult             │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Command Flow (Agent-Handled)

```
Client → AssignTaskCommand → Kafka commands topic
  → AgenticAggregatePartition.process()
    → KafkaAgenticAggregateRuntime.handleCommandRecord()
      → AssignTaskCommandHandlerFunction.apply()
        → Resolve Agent → Create AgentProcess → Emit AgentTaskAssignedEvent
          → [process NOT ticked here]

Partition idle-poll:
  → hasActiveAgentTasks() → true
    → Begin Kafka transaction
      → resumeNextAgentTask()
        → Round-robin select task → Get AgentProcess
          → AgentProcessSingleTickRunner.tick()
            → AgentProcessResultTranslator.collectEvents() [cursor-based]
              → Process domain events through event-sourcing pipeline
    → Commit transaction
```

### 2.3 Memory Distillation Flow

```
AgentProcess finishes (COMPLETED)
  → resumeAssignedTask()
    → Emit AgentTaskFinishedEvent
    → startMemoryDistillation()
      → Create MemoryDistillationInput (task, history, blackboard, memories, limits)
      → Launch MemoryDistillerAgent process
      → Emit MemoryDistillationStartedEvent

Subsequent idle-poll:
  → resumeMemoryDistillation()
    → Tick distillation process
    → If finished:
      → collectDistillationResult()
        → Extract MemoryDistillationResult from blackboard
        → translateDistillationResult()
          → Enforce net capacity limit
          → Emit MemoryStoredEvent / MemoryRevokedEvent
        → Emit MemoryDistillationFinishedEvent
```

### 2.4 Agent Resolution

```
resolveAgentByName(platform, aggregateName):
  for agent in platform.agents():
    if agent.name == aggregateName         → return agent    (exact)
    if agent.name == aggregateName+"Agent" → remember suffix
    if agent.name == "Default"             → remember default
  return suffixMatch ?? defaultMatch ?? throw IllegalStateException
```

---

## 3 Design Decisions

### DD-1: Tick-only execution

Rationale: Agent processes involve LLM calls that can take seconds. Running them inline
with command handling would violate Kafka's `max.poll.interval.ms` and block the
consumer. The idle-poll tick model decouples AI latency from command processing.

### DD-2: Singleton partition

Rationale: Agentic aggregates are inherently singleton entities (one AI persona per
aggregate). Multi-partition would require distributed agent state management with no
clear benefit. Single partition simplifies the model and avoids rebalancing.

### DD-3: Round-robin task scheduling

Rationale: When multiple tasks and distillations are active, round-robin ensures fair
progress across all items. The `AtomicInteger` counter wraps modulo the active item
count.

### DD-4: Jackson 2/3 bridge

Rationale: Embabel is compiled against Jackson 2 / victools 4.x. Akces uses
Jackson 3 / victools 5.x. A runtime `NoSuchMethodError` occurs when victools 5.x's
`generateSchema()` returns `tools.jackson.databind.node.ObjectNode` instead of
`com.fasterxml.jackson.databind.node.ObjectNode`. The custom converter generates
schemas with victools 5.x but deserialises LLM responses with Jackson 2.

### DD-5: Orphan process recovery

Rationale: After a restart, in-memory `AgentProcess` instances are lost. Rather than
attempting process restoration, the system emits failure events to clean up state,
allowing tasks to be re-submitted.

### DD-6: Cursor-based event collection

Rationale: Events remain on the blackboard so the Embabel planner can evaluate goal
achievement. An index cursor (`int[1]` array) is cheaper than maintaining a `Set` of
processed events and takes advantage of the blackboard's insertion-ordered list.

---

## 4 Key Interfaces

### From `akces-api` (consumed by this module)

- `AgenticAggregate<S>` — aggregate interface with `getCreateDomainEvent()`
- `MemoryAwareState` — state interface with `withMemory()`, `withoutMemory()`,
  `getMemories()`, `withMemoryDistillation()`, `withoutMemoryDistillation()`,
  `getMemoryDistillations()`
- `TaskAwareState` — state interface with `withAssignedTask()`,
  `withoutAssignedTask()`, `getAssignedTasks()`
- `AgentProcessAware` — common interface for items with `getAgentProcessId()`
- `AgenticAggregateMemory` — record carrying memory metadata
- `AssignedTask` — record carrying task metadata (implements `AgentProcessAware`)
- `MemoryDistillation` — record carrying distillation metadata (implements `AgentProcessAware`)
- `@AgenticAggregateInfo` — annotation with `agentHandledCommands`,
  `agentHandledEvents`, `agentProducedErrors`, `maxTotalMemories`, `maxMemoriesAdded`

### From Embabel (consumed by this module)

- `AgentPlatform` — agent lifecycle management
- `AgentProcess` — running agent instance with `tick()`, `getFinished()`, `getStatus()`,
  `getBlackboard()`, `getHistory()`
- `Agent` — resolved agent definition
- `Blackboard` — key-value + typed object store
- `ProcessOptions`, `Verbosity` — process configuration
- `ActionContext` — injected into agent action methods

---

## 5 File Inventory

| Package | File | Role |
|---|---|---|
| `agentic` | `AgenticAggregateRuntime.java` | Extended runtime interface; built-in type constants |
| `agentic.commands` | `AssignTaskCommand.java` | Built-in command record |
| `agentic.events` | `AgentTaskAssignedEvent.java` | Task lifecycle event |
| `agentic.events` | `AgentTaskFinishedEvent.java` | Task lifecycle event |
| `agentic.events` | `MemoryStoredEvent.java` | Memory lifecycle event |
| `agentic.events` | `MemoryRevokedEvent.java` | Memory lifecycle event |
| `agentic.events` | `MemoryDistillationStartedEvent.java` | Distillation lifecycle event |
| `agentic.events` | `MemoryDistillationFinishedEvent.java` | Distillation lifecycle event |
| `agentic.events` | `MemoryDistillationFailedEvent.java` | Distillation lifecycle event |
| `agentic.runtime` | `AgenticAggregateServiceApplication.java` | Spring Boot entry point |
| `agentic.runtime` | `AkcesAgenticAggregateController.java` | Controller for lifecycle management |
| `agentic.runtime` | `KafkaAgenticAggregateRuntime.java` | Core runtime implementation |
| `agentic.runtime` | `AgenticAggregatePartition.java` | Kafka partition handler |
| `agentic.runtime` | `AssignTaskCommandHandlerFunction.java` | Built-in AssignTask handler |
| `agentic.runtime` | `AgenticCommandHandlerFunctionAdapter.java` | Adapter for agent-handled commands |
| `agentic.runtime` | `AgenticEventHandlerFunctionAdapter.java` | Adapter for agent-handled events |
| `agentic.runtime` | `AgentProcessResultTranslator.java` | Cursor-based event collection |
| `agentic.runtime` | `AgentProcessSingleTickRunner.java` | Single-tick utility |
| `agentic.embabel` | `DefaultAgent.java` | Default fallback agent |
| `agentic.embabel` | `MemoryDistillerAgent.java` | Memory distillation agent |
| `agentic.embabel` | `MemoryDistillationInput.java` | Distillation input record |
| `agentic.embabel` | `MemoryDistillationResult.java` | Distillation result record |
| `agentic.embabel` | `Jackson3ChatClientLlmOperations.java` | Jackson 2/3 bridge service |
| `agentic.embabel` | `Jackson3OutputConverter.java` | Victools 5.x schema + Jackson 2 deser |
| `agentic.beans` | `AgenticAggregateBeanFactoryPostProcessor.java` | Bean discovery and registration |
| `agentic.beans` | `AgenticAggregateRuntimeFactory.java` | Runtime factory bean |
