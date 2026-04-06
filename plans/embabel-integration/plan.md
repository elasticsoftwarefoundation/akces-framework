# Embabel AgentPlatform Integration Plan

## Overview

The agentic aggregate module (`main/agentic`) currently has the Embabel Agent Framework (`embabel-agent-starter:0.3.5`) as a dependency, along with Spring AI MCP client and LLM providers. However, the Embabel runtime is not yet integrated into the command processing flow. This plan describes how to inject the Embabel `AgentPlatform` into the `KafkaAgenticAggregateRuntime`, create a reusable set of Goals, Actions and Conditions using `@EmbabelComponent`, and build a foundation for AI-powered Akces platform development agents.

---

## Phase 1: Inject Embabel AgentPlatform into the Runtime

### 1.1 Add `AgentPlatform` to `KafkaAgenticAggregateRuntime`

**What**: Inject the Embabel `AgentPlatform` (Spring-managed bean, auto-configured by `embabel-agent-starter`) into the `KafkaAgenticAggregateRuntime` so that the runtime can create and run `AgentProcess` instances during command processing.

**Changes**:
- Add an `AgentPlatform` field to `KafkaAgenticAggregateRuntime` constructor alongside the existing `delegate`, `objectMapper`, `stateClass` parameters
- Store the `AgentPlatform` reference as a final field
- Expose a `getAgentPlatform()` method on the `AgenticAggregateRuntime` interface

**Why**: The `AgentPlatform` is the entry point for running agents. Without it, the runtime cannot invoke GOAP-based planning, LLM reasoning, or tool use.

### 1.2 Wire `AgentPlatform` through `AgenticAggregateRuntimeFactory`

**What**: Update `AgenticAggregateRuntimeFactory` to resolve the `AgentPlatform` bean from the Spring `ApplicationContext` and pass it to the `KafkaAgenticAggregateRuntime` constructor.

**Changes**:
- In `AgenticAggregateRuntimeFactory.getObject()`, look up the `AgentPlatform` bean from `applicationContext`
- Pass it as a constructor argument to `new KafkaAgenticAggregateRuntime(kafkaRuntime, objectMapper, stateClass, agentPlatform)`
- Handle the case where `AgentPlatform` is not available (e.g., during unit tests without Embabel auto-configuration): fall back to a null reference with a warning log, making agent-based command processing a no-op

**Why**: The factory is the only place that creates `KafkaAgenticAggregateRuntime` instances. It already has access to the `ApplicationContext` via `ApplicationContextAware`.

### 1.3 Update `AgenticAggregateRuntime` Interface

**What**: Add an `AgentPlatform getAgentPlatform()` method to the `AgenticAggregateRuntime` interface.

**Changes**:
- Add `com.embabel.agent.core.AgentPlatform getAgentPlatform()` to the interface
- This allows `AgenticAggregatePartition` to access the platform for agent invocations during command processing

**Why**: The partition processes commands and needs access to the `AgentPlatform` to create `AgentProcess` instances. The runtime interface is the contract between the partition and the runtime.

### 1.4 Expose Memories on AgentProcess Blackboard

**What**: Before each command is processed through an agent, load the current aggregate memories and make them available on the agent's `Blackboard`.

**Changes**:
- In `AgenticAggregatePartition.handleCommand()`, after loading the state record:
  1. Call `runtime.getMemories(stateRecord)` to get the current memories
  2. Create a `ProcessOptions` with a `Blackboard` that contains the memories list
  3. Optionally set a condition `"hasMemories"` on the blackboard based on whether memories exist
- This enables Embabel Actions to access aggregate memories via `blackboard.objectsOfType(AgenticAggregateMemory.class)`

**Why**: Memories are the learned knowledge of the agent. They must be available in the agent's execution context so that actions can use them for reasoning.

---

## Phase 2: Create Akces Embabel Component with Goals, Actions, and Conditions

### 2.1 Create `AkcesAgentComponent` — The `@EmbabelComponent` Class

**What**: Create a new class `AkcesAgentComponent` annotated with Embabel's `@EmbabelComponent` (which includes `@Component`) that houses reusable Goals, Actions, and Conditions for Akces-based agentic systems.

**Package**: `org.elasticsoftware.akces.agentic.agent`

**Why**: Embabel discovers Goals/Actions/Conditions by scanning `@EmbabelComponent`-annotated classes. This provides a centralized place for Akces-specific agent capabilities that are automatically registered with the `AgentPlatform`.

### 2.2 Memory Management Action: `StoreMemoryAction`

**What**: An `@Action`-annotated method that creates a `StoreMemoryCommand` from the current `AgentProcess` context and sends it to the aggregate's command bus.

```
@Action(description = "Store a learned fact as a memory entry for the agentic aggregate")
```

**Behavior**:
1. Reads the current aggregate ID from the blackboard (set by the partition before agent invocation)
2. Takes `subject`, `fact`, `citations`, and `reason` as inputs (from blackboard or method parameters)
3. Creates a `StoreMemoryCommand` and returns it (the partition then handles sending it through the Kafka command path)
4. The action result is a `StoreMemoryCommand` domain object that the partition processes after the agent completes

**Why**: This is the most fundamental agent action — the ability to learn and persist knowledge across interactions. It bridges the Embabel agent execution model with the Akces event-sourcing model.

### 2.3 Memory Management Action: `ForgetMemoryAction`

**What**: An `@Action`-annotated method that creates a `ForgetMemoryCommand` to remove a specific memory by ID or by criteria.

```
@Action(description = "Revoke a memory entry that is no longer relevant or accurate")
```

**Behavior**:
1. Takes `memoryId` and `reason` from the blackboard
2. Creates a `ForgetMemoryCommand` and returns it
3. Enables the agent to self-manage its knowledge base

**Why**: Agents must be able to correct their knowledge. Outdated or incorrect facts should be removable.

### 2.4 Memory Retrieval Action: `RecallMemoriesAction`

**What**: An `@Action`-annotated method that searches the current memories for relevant entries based on a subject or keyword.

```
@Action(description = "Search stored memories by subject or keyword to retrieve relevant facts", readOnly = true)
```

**Behavior**:
1. Reads the `List<AgenticAggregateMemory>` from the blackboard
2. Filters by subject match, keyword in fact/reason, or returns all
3. Returns matching memories as a structured result
4. Declared as `readOnly = true` since it has no side effects

**Why**: Before an agent decides to store a new memory, it should check what it already knows. This avoids duplicate memories and enables informed decision-making.

### 2.5 Condition: `HasMemoriesCondition`

**What**: A `@Condition`-annotated method that evaluates whether the aggregate currently has any stored memories.

```
@Condition(name = "hasMemories")
```

**Behavior**:
- Returns `true` if the blackboard contains one or more `AgenticAggregateMemory` objects
- Used as a precondition for actions that depend on prior knowledge

**Why**: Some actions only make sense when the agent has prior context. For example, "recall memories" is only meaningful when memories exist.

### 2.6 Condition: `HasAggregateStateCondition`

**What**: A `@Condition`-annotated method that evaluates whether the aggregate has been initialized (state exists).

```
@Condition(name = "hasAggregateState")
```

**Behavior**:
- Returns `true` if the blackboard contains a non-null aggregate state
- Used as a precondition for actions that require the aggregate to exist

**Why**: Creation commands are only valid when the aggregate does NOT exist, and mutation commands require the aggregate to exist. This enables goal planning to reason about aggregate lifecycle.

### 2.7 Goal: `LearnFromProcessGoal`

**What**: A Goal that represents the objective of extracting learned facts from the current agent process and persisting them as memories.

```
Goal: "LearnFromProcess"
Description: "Extract and store relevant facts learned during the current agent process"
Preconditions: hasAggregateState
```

**Plan**: The agent planner would compose this as:
1. `RecallMemoriesAction` → check existing memories
2. (LLM reasoning) → determine what new facts were learned
3. `StoreMemoryAction` → persist new memories
4. Optionally `ForgetMemoryAction` → replace outdated memories

**Why**: This is the foundational agentic behavior — after processing any command, the agent should learn from the experience and update its knowledge base.

---

## Phase 3: Akces Platform Development Goals, Actions, and Conditions

These are higher-level Goals/Actions/Conditions that enable an agentic aggregate to reason about and extend an Akces-based platform.

### 3.1 Condition: `IsCommandProcessingCondition`

```
@Condition(name = "isCommandProcessing")
```

**What**: Returns `true` if the current agent context contains a command to process (as opposed to an external event).

**Why**: Enables different goal planning for command-initiated vs. event-initiated agent loops.

### 3.2 Condition: `IsExternalEventCondition`

```
@Condition(name = "isExternalEvent")
```

**What**: Returns `true` if the current agent context was triggered by an external domain event.

**Why**: Complement to `isCommandProcessing`. External events often require different reasoning strategies (reactive vs. proactive).

### 3.3 Action: `EmitDomainEventsAction`

```
@Action(description = "Emit one or more domain events as the result of agent reasoning")
```

**What**: Takes a list of domain event objects from the agent's reasoning and packages them for emission by the partition.

**Behavior**:
1. Receives domain event objects from the blackboard (produced by prior actions or LLM reasoning)
2. Validates them against the aggregate's registered event types
3. Returns them as a structured `AgentResult` that the partition translates into Kafka `DomainEventRecord` entries

**Why**: The fundamental output of any command handler is domain events. This action bridges Embabel's action model with Akces's event emission model.

### 3.4 Action: `SendCommandAction`

```
@Action(description = "Send a command to another aggregate via the command bus")
```

**What**: Enables the agent to send commands to other aggregates as part of its reasoning.

**Behavior**:
1. Takes a `Command` object from the blackboard
2. Routes it through the `CommandBus` (the `AgenticAggregatePartition` implements `CommandBus`)
3. Returns confirmation of command dispatch

**Why**: Agentic aggregates need to coordinate with other aggregates (both regular and agentic). This is the Embabel-wrapped equivalent of `commandBus.send()`.

### 3.5 Action: `AnalyzeAggregateStateAction`

```
@Action(description = "Analyze the current aggregate state using LLM reasoning", readOnly = true)
```

**What**: Takes the current aggregate state and uses LLM reasoning to extract insights, identify patterns, or make recommendations.

**Behavior**:
1. Reads the aggregate state from the blackboard
2. Serializes it to a structured representation
3. Invokes LLM reasoning with context-specific prompts
4. Returns analysis results on the blackboard

**Why**: This is the core "intelligence" action — it leverages the LLM to reason about domain state and make informed decisions.

### 3.6 Goal: `ProcessCommandGoal`

```
Goal: "ProcessCommand"
Description: "Process an incoming command through AI-assisted reasoning and produce domain events"
Preconditions: isCommandProcessing
```

**Plan**:
1. `RecallMemoriesAction` → load relevant prior knowledge
2. `AnalyzeAggregateStateAction` → understand current state
3. (LLM reasoning with MCP tools) → determine actions
4. `EmitDomainEventsAction` → produce events
5. `LearnFromProcessGoal` (sub-goal) → learn from experience

**Why**: This is the top-level goal for command processing in an agentic aggregate. It replaces the deterministic `@CommandHandler` flow with an AI-assisted reasoning loop.

### 3.7 Goal: `ReactToExternalEventGoal`

```
Goal: "ReactToExternalEvent"
Description: "React to an external domain event through AI-assisted reasoning"
Preconditions: isExternalEvent
```

**Plan**:
1. `RecallMemoriesAction` → load relevant prior knowledge
2. `AnalyzeAggregateStateAction` → understand current state
3. (LLM reasoning) → determine appropriate response
4. `SendCommandAction` → send commands if needed
5. `EmitDomainEventsAction` → produce events if needed
6. `LearnFromProcessGoal` (sub-goal) → learn from experience

**Why**: This is the top-level goal for handling external events. Agentic aggregates subscribe to all partitions of external event topics, and this goal enables AI-assisted reasoning about those events.

### 3.8 Goal: `ManageKnowledgeGoal`

```
Goal: "ManageKnowledge"
Description: "Review, update, and optimize the aggregate's memory store"
```

**Plan**:
1. `RecallMemoriesAction` → load all memories
2. (LLM reasoning) → identify outdated, duplicate, or conflicting facts
3. `ForgetMemoryAction` → remove outdated entries
4. `StoreMemoryAction` → store corrected/updated entries

**Why**: Over time, memories accumulate and may become stale. A dedicated knowledge management goal enables periodic memory optimization.

---

## Phase 4: Integration with AgenticAggregatePartition

### 4.1 Agent-Assisted Command Processing Hook

**What**: Modify `AgenticAggregatePartition.handleCommand()` to optionally invoke the Embabel agent loop before/after the standard `runtime.handleCommandRecord()` call.

**Design Options** (to be decided with Architect):

**Option A: Agent-First Processing**
The Embabel agent processes the command, producing events as its output. The standard `handleCommandRecord` path is bypassed.
- Pro: Full AI control over command processing
- Con: Bypasses deterministic command handler validation

**Option B: Agent-Wrapped Processing**
The standard `handleCommandRecord` runs first (deterministic), then the agent runs a `LearnFromProcessGoal` to learn from the interaction.
- Pro: Maintains deterministic guarantees, adds learning layer
- Con: Agent has less control over the outcome

**Option C: Dual-Mode Processing (Recommended)**
The `@AgenticAggregateInfo` annotation or individual `@CommandHandler` methods gain an `agentAssisted` flag. When `true`, the agent processes the command (Option A). When `false`, the standard path runs (Option B's learning variant).
- Pro: Flexible per-command configuration
- Con: More complex but most versatile

### 4.2 AgentProcess Result Translation

**What**: Define how `AgentProcess` results (from the Embabel `Blackboard`) are translated back into Akces domain events and commands.

**Changes**:
- After `agentProcess.run()` completes, inspect the blackboard for:
  - `DomainEvent` objects → emit as domain events
  - `Command` objects → send through the command bus
  - `StoreMemoryCommand` objects → process through the memory command path
- Create a `AgentProcessResultTranslator` utility class to handle this translation

### 4.3 Agent Context Setup

**What**: Define the standard set of objects placed on the `Blackboard` before agent invocation.

**Standard Blackboard Content**:
- `CommandRecord` or `DomainEventRecord` — the trigger
- `AggregateState` — the current state (or null for creation)
- `List<AgenticAggregateMemory>` — current memories
- `String agenticAggregateId` — the aggregate identifier
- `AggregateRuntime` — for type information
- Conditions: `hasMemories`, `hasAggregateState`, `isCommandProcessing`/`isExternalEvent`

---

## Phase 5: Testing

### 5.1 Unit Tests

| Test Class | What to Verify |
|-----------|---------------|
| `AkcesAgentComponentTest` | All Actions, Goals, and Conditions are correctly annotated and discoverable |
| `StoreMemoryActionTest` | Creates valid `StoreMemoryCommand` from blackboard context |
| `ForgetMemoryActionTest` | Creates valid `ForgetMemoryCommand` from blackboard context |
| `RecallMemoriesActionTest` | Correctly filters memories by subject/keyword |
| `AgentPlatformInjectionTest` | Verifies `AgentPlatform` is correctly wired through factory → runtime |
| `AgentProcessResultTranslatorTest` | Correctly extracts events and commands from blackboard |

### 5.2 Integration Tests

| Test Class | What to Verify |
|-----------|---------------|
| `EmbabelAgentLoopIntegrationTest` | Full agent invocation: command → agent → events, using mock LLM |
| `MemoryLearningIntegrationTest` | Agent stores memory via `StoreMemoryAction` → `MemoryStoredEvent` emitted |

---

## File Inventory

### New Files

| File | Package | Purpose |
|------|---------|---------|
| `AkcesAgentComponent.java` | `o.e.a.agentic.agent` | `@EmbabelComponent` with reusable Actions, Conditions |
| `AkcesAgentGoals.java` | `o.e.a.agentic.agent` | Goal definitions as static factory methods |
| `AgentProcessResultTranslator.java` | `o.e.a.agentic.agent` | Translates `AgentProcess` results to Akces domain objects |

### Modified Files

| File | Changes |
|------|---------|
| `AgenticAggregateRuntime.java` | Add `getAgentPlatform()` method |
| `KafkaAgenticAggregateRuntime.java` | Accept and store `AgentPlatform`; implement `getAgentPlatform()` |
| `AgenticAggregateRuntimeFactory.java` | Resolve `AgentPlatform` from `ApplicationContext` and pass to runtime |
| `AgenticAggregatePartition.java` | Add agent context setup and result translation in `handleCommand()` |

---

## Dependencies

No new external dependencies required. The existing `embabel-agent-starter:0.3.5` and Spring AI dependencies already provide everything needed:
- `AgentPlatform` — from `embabel-agent-starter` (auto-configured)
- `@EmbabelComponent`, `@Action`, `@Condition`, `@AchievesGoal` — from `embabel-agent-api`
- `Blackboard`, `ProcessOptions`, `Goal`, `Agent`, `AgentProcess` — from `embabel-agent-api`

---

## Implementation Order

1. **Phase 1** (AgentPlatform injection) — foundation, no behavior change
2. **Phase 2** (Goals/Actions/Conditions) — reusable components
3. **Phase 4** (Partition integration) — wire everything together
4. **Phase 3** (Platform development goals) — higher-level capabilities
5. **Phase 5** (Testing) — validate everything

Phases 1 and 2 can be developed in parallel as they are independent.

---

## Open Questions for Architect

1. **Processing Mode**: Which option for Phase 4.1 — Agent-First (A), Agent-Wrapped (B), or Dual-Mode (C)?
2. **LLM Model Configuration**: Should the `@AgenticAggregateInfo` annotation include LLM model preferences (model name, temperature), or should this be purely configuration-based (application.yaml)?
3. **Agent Scope**: Should each agentic aggregate get its own `Agent` instance (deployed to the `AgentPlatform`), or should the `AkcesAgentComponent`'s actions be shared across all agentic aggregates?
4. **Synchronous vs. Async Agent Execution**: Should `agentProcess.run()` be called synchronously within the Kafka poll loop, or should we use `agentProcess.tick()` for incremental execution with periodic Kafka heartbeats?
5. **MCP Tool Scoping**: Should MCP tools (from sidecar servers) be automatically added to the agent's tool groups, or should they be explicitly configured per agentic aggregate?
