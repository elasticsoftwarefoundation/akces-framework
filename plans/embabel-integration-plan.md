# Embabel AgentPlatform Integration Plan

## Overview

The agentic aggregate module (`main/agentic`) currently has the Embabel Agent Framework (`embabel-agent-starter:0.3.5`) as a dependency, along with Spring AI MCP client and LLM providers. However, the Embabel runtime is not yet integrated into the command processing flow. This plan describes how to inject the Embabel `AgentPlatform` into the `KafkaAgenticAggregateRuntime`, create a reusable set of Goals, Actions and Conditions using `@EmbabelComponent`, and build a foundation for AI-powered Akces platform development agents.

A key design concern is the handling of **error events** in the agent processing flow. Error events are special domain events that implement the `ErrorEvent` marker interface. They do not have `@EventSourcingHandler` methods (because they don't change aggregate state) and are used to signal error conditions back to command senders. The `@AgenticAggregateInfo` annotation is extended with three new properties to fully declare agent-handled inputs and agent-produced outputs, including error events.

---

## `@AgenticAggregateInfo` Annotation Extensions

### New Properties

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Component
public @interface AgenticAggregateInfo {
    @AliasFor(annotation = Component.class)
    String value();

    Class<? extends AggregateState> stateClass();

    String description() default "";

    int maxMemories() default 100;

    /**
     * Command classes handled by the AI agent (Agent-First processing).
     * These commands are routed to the Embabel agent loop instead of requiring
     * a deterministic @CommandHandler method. The framework registers CommandType
     * entries for these and includes them in the AggregateServiceRecord.
     */
    Class<? extends Command>[] agentHandledCommands() default {};

    /**
     * External domain event classes handled by the AI agent (Agent-First processing).
     * These events are routed to the Embabel agent loop instead of requiring
     * a deterministic @EventHandler method. The framework registers DomainEventType
     * entries (with external=true) and subscribes to all partitions of their topics.
     */
    Class<? extends DomainEvent>[] agentHandledEvents() default {};

    /**
     * Error event classes the AI agent may produce when handling agent commands
     * or agent events. Error events don't have @EventSourcingHandler methods
     * (they don't change aggregate state) but ARE registered for schema validation
     * and service discovery. Each class must implement ErrorEvent and carry
     * @DomainEventInfo. Registered as DomainEventType with error=true.
     */
    Class<? extends ErrorEvent>[] agentProducedErrors() default {};
}
```

### Naming Rationale

| Property | Meaning |
|----------|---------|
| `agentHandledCommands` | Commands **handled by** the agent (input) — no `@CommandHandler` method needed |
| `agentHandledEvents` | External events **handled by** the agent (input) — no `@EventHandler` method needed |
| `agentProducedErrors` | Error events **produced by** the agent (output) — no `@EventSourcingHandler` exists |

The "Handled" prefix clarifies that these are **inputs** consumed by the agent, while "Produced" clarifies that errors are **outputs** emitted by the agent. This naming is consistent with the deterministic handler model where `@CommandHandler` "handles" commands and `errors = {...}` declares "produced" error events.

### How All Event/Command Sources Feed Into `AggregateServiceRecord`

| Source | Types | How Discovered |
|--------|-------|----------------|
| Deterministic `@CommandHandler.produces()` | State-changing events | Standard bean scanning (existing) |
| Deterministic `@CommandHandler.errors()` | Error events from handlers | Standard bean scanning (existing) |
| Deterministic `@EventHandler.produces()` | State-changing events from external event handlers | Standard bean scanning (existing) |
| Deterministic `@EventHandler.errors()` | Error events from external event handlers | Standard bean scanning (existing) |
| Agent state-changing events | All `DomainEventType`s with `@EventSourcingHandler` | Derived from ESH registrations |
| Agent error events | `agentProducedErrors` from `@AgenticAggregateInfo` | Explicit annotation declaration |
| System error events | `AggregateAlreadyExistsError`, `AggregateNotFoundError`, `CommandExecutionError` | Always added by framework |
| Built-in events | `MemoryStored`, `MemoryRevoked` | Always added by framework |

### Why Error Events Need Explicit Declaration

Unlike state-changing events (derivable from `@EventSourcingHandler` registrations), error events have **no handler** to derive from. We cannot scan for "all `ErrorEvent` implementations in the classpath" because:
- That's imprecise (may pick up error events from other aggregates in the same app)
- It violates the principle of explicit declaration
- Error events from different agents would get mixed up

Explicit declaration via `agentProducedErrors` is consistent with how deterministic `@CommandHandler` already declares `errors = {...}`.

### Registration Flow for Agent Properties

In `AgenticAggregateBeanFactoryPostProcessor`:

```
For each class in agentHandledCommands[]:
  1. Validate it implements Command
  2. Validate it has @CommandInfo
  3. Create CommandType (create=false, since agent handles it)
  4. Register it via runtimeBuilder (no CommandHandlerFunctionAdapter needed)

For each class in agentHandledEvents[]:
  1. Validate it implements DomainEvent
  2. Validate it has @DomainEventInfo
  3. Create DomainEventType with external=true
  4. Register it via runtimeBuilder.addDomainEvent()

For each class in agentProducedErrors[]:
  1. Validate it implements ErrorEvent
  2. Validate it has @DomainEventInfo
  3. Create DomainEventType with error=true
  4. Register it via runtimeBuilder.addDomainEvent()
  5. Register JSON schema for the error event
```

### Usage Example

```java
@AgenticAggregateInfo(
    value = "TradingAdvisor",
    stateClass = TradingAdvisorState.class,
    agentHandledCommands = { AnalyzeMarketCommand.class, SuggestTradeCommand.class },
    agentHandledEvents = { MarketDataUpdatedEvent.class },
    agentProducedErrors = { MarketUnavailableErrorEvent.class, InsufficientDataErrorEvent.class }
)
public class TradingAdvisorAggregate implements AgenticAggregate<TradingAdvisorState> {

    // Deterministic creation — standard @CommandHandler
    @CommandHandler(create = true,
                    produces = {TradingAdvisorCreatedEvent.class}, errors = {})
    public Stream<DomainEvent> create(CreateTradingAdvisorCommand cmd, TradingAdvisorState isNull) {
        return Stream.of(new TradingAdvisorCreatedEvent(cmd.id()));
    }

    // NO @CommandHandler for AnalyzeMarketCommand — agent handles it
    // NO @CommandHandler for SuggestTradeCommand — agent handles it
    // NO @EventHandler for MarketDataUpdatedEvent — agent handles it

    // EventSourcingHandlers define the full set of state transitions the agent can produce
    @EventSourcingHandler(create = true)
    public TradingAdvisorState create(TradingAdvisorCreatedEvent e, TradingAdvisorState isNull) { ... }

    @EventSourcingHandler
    public TradingAdvisorState apply(MarketAnalysisCompletedEvent e, TradingAdvisorState s) { ... }

    @EventSourcingHandler
    public TradingAdvisorState apply(TradeSuggestedEvent e, TradingAdvisorState s) { ... }

    // NO EventSourcingHandler for MarketUnavailableErrorEvent — it's an error event, declared in agentProducedErrors
    // NO EventSourcingHandler for InsufficientDataErrorEvent — it's an error event, declared in agentProducedErrors
}
```

The resulting `AggregateServiceRecord.producedEvents` would contain:
- `TradingAdvisorCreated` (from `@CommandHandler.produces`)
- `MarketAnalysisCompleted` (from `@EventSourcingHandler`)
- `TradeSuggested` (from `@EventSourcingHandler`)
- `MarketUnavailableError` (from `agentProducedErrors`)
- `InsufficientDataError` (from `agentProducedErrors`)
- `MemoryStored`, `MemoryRevoked` (built-in)
- `AggregateAlreadyExistsError`, `AggregateNotFoundError`, `CommandExecutionError` (system errors)

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
- If `AgentPlatform` is not available in the `ApplicationContext`, fail fast with a fatal error — the application must not start without a configured `AgentPlatform`. This is a hard requirement for agentic aggregates.

**Why**: The factory is the only place that creates `KafkaAgenticAggregateRuntime` instances. It already has access to the `ApplicationContext` via `ApplicationContextAware`. Since agentic aggregates fundamentally depend on the `AgentPlatform` for command processing, starting without it would lead to silent failures.

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

**What**: An `@Action`-annotated method that produces a `MemoryStoredEvent` directly, bypassing the internal command path. The event is applied to the state via the built-in `@EventSourcingHandler` for `MemoryStoredEvent`.

```
@Action(description = "Store a learned fact as a memory entry for the agentic aggregate")
```

**Behavior**:
1. Reads the current aggregate ID from the blackboard (set by the partition before agent invocation)
2. Takes `subject`, `fact`, `citations`, and `reason` as inputs (from blackboard or method parameters)
3. Creates a `MemoryStoredEvent` and returns it on the blackboard
4. The partition collects the event and applies it through the built-in `@EventSourcingHandler` (which calls `MemoryAwareState.withMemory()`)

**Note**: The internal `StoreMemoryCommand` will be removed in a later stage. Actions should produce events that are applied to the state using the built-in EventSourcingHandlers.

**Why**: This is the most fundamental agent action — the ability to learn and persist knowledge across interactions. Producing events directly (rather than commands) simplifies the flow and is consistent with the event-sourcing model where actions result in domain events.

### 2.3 Memory Management Action: `ForgetMemoryAction`

**What**: An `@Action`-annotated method that produces a `MemoryRevokedEvent` to remove a specific memory by ID or by criteria.

```
@Action(description = "Revoke a memory entry that is no longer relevant or accurate")
```

**Behavior**:
1. Takes `memoryId` and `reason` from the blackboard
2. Creates a `MemoryRevokedEvent` and returns it on the blackboard
3. The partition collects the event and applies it through the built-in `@EventSourcingHandler` (which calls `MemoryAwareState.withoutMemory()`)
4. Enables the agent to self-manage its knowledge base

**Note**: The internal `ForgetMemoryCommand` will be removed in a later stage.

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

### ~~2.6 Condition: `HasAggregateStateCondition`~~ — REMOVED

> **Note**: This condition has been removed. The framework will ensure that for an `AgenticAggregate` the state is always created automatically (since agentic aggregates are singletons). There is no need to check for state existence as a precondition.

### 2.7 Goal: `LearnFromProcessGoal`

**What**: A Goal that represents the objective of extracting learned facts from the current agent process and persisting them as memories.

```
Goal: "LearnFromProcess"
Description: "Extract and store relevant facts learned during the current agent process"
```

**Constraints**:
- **Maximum 3 new memories** per agent process execution. This prevents memory bloat from a single interaction.
- **No duplicate memories**: Before storing, the agent must check existing memories and skip any fact that is already stored (same subject + substantially similar fact content).
- **Memory field guidance** (to be included in the Goal's prompt instructions):
  - `subject`: A 1-3 word topic label (e.g., "error handling", "user preferences", "market patterns"). Used for grouping and retrieval.
  - `fact`: A clear, concise factual statement (max ~200 chars). Should be actionable and self-contained.
  - `citations`: Source reference — the command/event type that triggered this learning, or relevant data points from the aggregate state.
  - `reason`: Why this fact is worth remembering — what future decisions it informs. Should be 2-3 sentences explaining the significance.

**Plan**: The agent planner would compose this as:
1. `RecallMemoriesAction` → check existing memories (to avoid duplicates)
2. (LLM reasoning) → determine what new facts were learned, respecting the max-3 limit
3. `StoreMemoryAction` → persist new memories (producing `MemoryStoredEvent`)
4. Optionally `ForgetMemoryAction` → replace outdated memories (producing `MemoryRevokedEvent`)

**Why**: This is the foundational agentic behavior — after processing any command, the agent should learn from the experience and update its knowledge base. The constraints ensure memory quality over quantity.

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
2. Validates them against the aggregate's registered event types (including `agentProducedErrors` error events)
3. Returns them as a structured `AgentResult` that the partition translates into Kafka `DomainEventRecord` entries

**Why**: The fundamental output of any command handler is domain events. This action bridges Embabel's action model with Akces's event emission model. The agent can emit both state-changing events (which have `@EventSourcingHandler` methods) and error events (declared via `agentProducedErrors`).

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
4. `EmitDomainEventsAction` → produce events (including error events from `agentProducedErrors` if applicable)
5. `LearnFromProcessGoal` (sub-goal) → learn from experience

**Why**: This is the top-level goal for command processing in an agentic aggregate. It replaces the deterministic `@CommandHandler` flow with an AI-assisted reasoning loop for commands declared in `agentHandledCommands`.

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
5. `EmitDomainEventsAction` → produce events if needed (including error events from `agentProducedErrors`)
6. `LearnFromProcessGoal` (sub-goal) → learn from experience

**Why**: This is the top-level goal for handling external events declared in `agentHandledEvents`. Agentic aggregates subscribe to all partitions of external event topics, and this goal enables AI-assisted reasoning about those events.

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

**What**: Modify `AgenticAggregatePartition.handleCommand()` to use Agent-First processing for commands declared in `agentHandledCommands` and standard deterministic processing for commands with `@CommandHandler` methods.

**Design: Agent-First Processing for `agentHandledCommands`**

When a command arrives whose type is declared in `agentHandledCommands`:
1. The standard `@CommandHandler` path is bypassed (no deterministic handler exists)
2. The Embabel agent is invoked with the command, current state, and memories on the blackboard
3. The agent produces domain events (state-changing or error events from `agentProducedErrors`) as output
4. State-changing events are applied through `@EventSourcingHandler` methods (deterministic state transitions)
5. Error events are emitted but do NOT change state

For commands with a standard `@CommandHandler` method, the existing deterministic path is used unchanged.

For external events declared in `agentHandledEvents`, the same Agent-First flow applies but triggered by an event instead of a command.

### 4.2 AgentProcess Result Translation

**What**: Define how `AgentProcess` results (from the Embabel `Blackboard`) are translated back into Akces domain events and commands.

**Changes**:
- After `agentProcess.tick()` completes (or the process reaches a terminal state), inspect the blackboard for:
  - `DomainEvent` objects → emit as domain events (both state-changing and error events)
  - `MemoryStoredEvent` / `MemoryRevokedEvent` → apply through the built-in `@EventSourcingHandler` methods
  - `Command` objects → send through the command bus
- Create a `AgentProcessResultTranslator` utility class to handle this translation
- Accept all `ErrorEvent` instances at runtime, even if not declared in `agentProducedErrors`. Log a warning for undeclared error events but do not reject them.

**Note on `tick()` execution**: Use `AgentProcess.tick()` for incremental execution. If there is an active `AgentProcess`, `tick()` must be called on every poll loop iteration, even if no Kafka records are returned.

### 4.3 Agent Context Setup

**What**: Define the standard set of objects placed on the `Blackboard` before agent invocation.

**Standard Blackboard Content**:
- `CommandRecord` or `DomainEventRecord` — the trigger
- `AggregateState` — the current state (always present — the framework ensures singleton state is auto-created)
- `List<AgenticAggregateMemory>` — current memories
- `String agenticAggregateId` — the aggregate identifier
- `AggregateRuntime` — for type information (including registered `agentProducedErrors` types)
- Conditions: `hasMemories`, `isCommandProcessing`/`isExternalEvent`

---

## Phase 5: Testing

### 5.1 Unit Tests

| Test Class | What to Verify |
|-----------|---------------|
| `AkcesAgentComponentTest` | All Actions, Goals, and Conditions are correctly annotated and discoverable |
| `StoreMemoryActionTest` | Produces valid `MemoryStoredEvent` from blackboard context |
| `ForgetMemoryActionTest` | Produces valid `MemoryRevokedEvent` from blackboard context |
| `RecallMemoriesActionTest` | Correctly filters memories by subject/keyword |
| `AgentPlatformInjectionTest` | Verifies `AgentPlatform` is correctly wired through factory → runtime; verifies fatal error when `AgentPlatform` is absent |
| `AgentProcessResultTranslatorTest` | Correctly extracts events and commands from blackboard |
| `AgentProducedErrorsRegistrationTest` | Verifies `agentProducedErrors` are registered as `DomainEventType(error=true)` |
| `AgentHandledCommandsRegistrationTest` | Verifies `agentHandledCommands` create `CommandType` entries without handler adapters |
| `AgentHandledEventsRegistrationTest` | Verifies `agentHandledEvents` create external `DomainEventType` entries |

### 5.2 Integration Tests

| Test Class | What to Verify |
|-----------|---------------|
| `EmbabelAgentLoopIntegrationTest` | Full agent invocation: command → agent → events, using mock LLM |
| `MemoryLearningIntegrationTest` | Agent stores memory via `StoreMemoryAction` → `MemoryStoredEvent` emitted and applied to state |
| `AgentErrorEventIntegrationTest` | Agent produces error event → event emitted, state unchanged, undeclared errors logged with warning |

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
| `AgenticAggregateInfo.java` | Add `agentHandledCommands()`, `agentHandledEvents()`, `agentProducedErrors()` properties |
| `AgenticAggregateRuntime.java` | Add `getAgentPlatform()` method |
| `KafkaAgenticAggregateRuntime.java` | Accept and store `AgentPlatform`; implement `getAgentPlatform()` |
| `AgenticAggregateRuntimeFactory.java` | Resolve `AgentPlatform` from `ApplicationContext`; process `agentHandledCommands`, `agentHandledEvents`, `agentProducedErrors` from annotation |
| `AgenticAggregateBeanFactoryPostProcessor.java` | Read new annotation properties; register `CommandType` for `agentHandledCommands`, `DomainEventType(external=true)` for `agentHandledEvents`, `DomainEventType(error=true)` for `agentProducedErrors` |
| `AgenticAggregatePartition.java` | Add agent context setup and result translation in `handleCommand()`; distinguish agent-handled vs deterministic commands |
| `AkcesAgenticAggregateController.java` | Populate `consumedEvents` in `AggregateServiceRecord` from `getExternalDomainEventTypes()` (including `agentHandledEvents`) |

---

## Dependencies

No new external dependencies required. The existing `embabel-agent-starter:0.3.5` and Spring AI dependencies already provide everything needed:
- `AgentPlatform` — from `embabel-agent-starter` (auto-configured)
- `@EmbabelComponent`, `@Action`, `@Condition`, `@AchievesGoal` — from `embabel-agent-api`
- `Blackboard`, `ProcessOptions`, `Goal`, `Agent`, `AgentProcess` — from `embabel-agent-api`

---

## Implementation Order

1. **Phase 1** (AgentPlatform injection) — foundation, no behavior change
2. **Annotation Extensions** (`agentHandledCommands`, `agentHandledEvents`, `agentProducedErrors`) — metadata foundation
3. **Phase 2** (Goals/Actions/Conditions) — reusable components
4. **Phase 4** (Partition integration) — wire everything together
5. **Phase 3** (Platform development goals) — higher-level capabilities
6. **Phase 5** (Testing) — validate everything

Phases 1 and Annotation Extensions can be developed in parallel as they are independent.

---

## Resolved Design Decisions

The following questions have been resolved by the Architect:

1. **LLM Model Configuration**: LLM models are configured at the **Embabel level** (Embabel code/annotations or application configuration), **not** in the `@AgenticAggregateInfo` annotation. The Akces framework does not manage LLM model selection.

2. **Agent Scope**: Each `AgenticAggregate` gets its **own `Agent` instance** deployed to the `AgentPlatform`. When multiple `AgenticAggregate`s are deployed in a single VM instance (sharing the `AgentPlatform`), they each use their own `Agent` instance.

3. **Synchronous vs. Async Agent Execution**: Use `AgentProcess.tick()` for incremental execution. **Important**: if there is an active `AgentProcess`, `tick()` must be called even if no records come back from the Kafka poll. In the future, multiple running processes per `AgenticAgent` will be supported, but this requires additional state management and is out of scope for now.

4. **MCP Tool Scoping**: MCP tools added in the core `AgenticAggregate` are **available to all Agents**. No per-aggregate tool scoping is needed — shared tools are the default.

5. **Error Event Validation at Runtime**: Accept **all `ErrorEvent`s** at runtime, even if they are not declared in `agentProducedErrors`. If an undeclared error event is emitted, **log a warning** but do not reject it. This provides flexibility while maintaining observability.

