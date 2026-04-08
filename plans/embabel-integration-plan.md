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

In `AgenticAggregateBeanFactoryPostProcessor` / `AgenticAggregateRuntimeFactory`:

```
For each class in agentHandledCommands[]:
  1. Validate it implements Command
  2. Validate it has @CommandInfo
  3. Create CommandType (create=false, since agent handles it)
  4. Create an AgenticCommandHandlerFunctionAdapter (see section below)
  5. Register via runtimeBuilder.addCommandHandler(commandType, adapter)

For each class in agentHandledEvents[]:
  1. Validate it implements DomainEvent
  2. Validate it has @DomainEventInfo
  3. Create DomainEventType with external=true
  4. Create an AgenticEventHandlerFunctionAdapter (see section below)
  5. Register via runtimeBuilder.addExternalEventHandler(eventType, adapter)

For each class in agentProducedErrors[]:
  1. Validate it implements ErrorEvent
  2. Validate it has @DomainEventInfo
  3. Create DomainEventType with error=true
  4. Register it via runtimeBuilder.addDomainEvent()
  5. Register JSON schema for the error event
```

### Agentic Handler Adapters

Instead of bypassing the handler mechanism entirely for agent-handled commands/events, we create new adapter implementations that plug into the existing `CommandHandlerFunction` / `EventHandlerFunction` contracts. This allows the standard `KafkaAggregateRuntime.handleCommand()` and `handleEvent()` flows to work unchanged — the adapters route processing through the Embabel agent instead of a deterministic method.

#### `AgenticCommandHandlerFunctionAdapter`

A new implementation of `CommandHandlerFunction` for agent-handled commands:

```java
public class AgenticCommandHandlerFunctionAdapter<S extends AggregateState & MemoryAwareState, C extends Command, E extends DomainEvent>
        implements CommandHandlerFunction<S, C, E> {

    private final AgenticAggregate<S> aggregate;
    private final CommandType<C> commandType;
    private final AgentPlatform agentPlatform;
    private final Goal processCommandGoal;                          // "ProcessCommand" goal
    private final List<DomainEventType<E>> producedDomainEventTypes; // from @EventSourcingHandler registrations
    private final List<DomainEventType<E>> errorEventTypes;         // from agentProducedErrors

    @Override
    public Stream<E> apply(C command, S state) {
        // 1. Set up the Blackboard with command, state, memories, aggregate service records
        Blackboard blackboard = new Blackboard();
        blackboard.add(command);
        blackboard.add(state);
        blackboard.addAll(((MemoryAwareState) state).getMemories());
        blackboard.addAll(aggregateServiceRecords);
        blackboard.set("agenticAggregateId", state.getAggregateId());
        blackboard.set("isCommandProcessing", true);
        blackboard.set("isExternalEvent", false);
        blackboard.set("hasMemories", !((MemoryAwareState) state).getMemories().isEmpty());

        // 2. Create AgentProcess via agentPlatform with the ProcessCommand goal
        AgentProcess agentProcess = agentPlatform.createAgentProcess(processCommandGoal, blackboard);

        // 3. Call agentProcess.tick() in a loop until end state
        List<E> collectedEvents = new ArrayList<>();
        while (!agentProcess.isComplete()) {
            agentProcess.tick();
            collectedEvents.addAll(AgentProcessResultTranslator.collectEvents(blackboard));
        }

        // 4. Return collected DomainEvent instances as Stream<E>
        return collectedEvents.stream();
    }

    @Override
    public boolean isCreate() { return false; } // agent-handled commands cannot create — see Design Constraint below

    @Override
    public CommandType<C> getCommandType() { return commandType; }

    // ...
}
```

#### `AgenticEventHandlerFunctionAdapter`

A new implementation of `EventHandlerFunction` for agent-handled external events:

```java
public class AgenticEventHandlerFunctionAdapter<S extends AggregateState & MemoryAwareState, InputEvent extends DomainEvent, E extends DomainEvent>
        implements EventHandlerFunction<S, InputEvent, E> {

    private final AgenticAggregate<S> aggregate;
    private final DomainEventType<InputEvent> domainEventType;
    private final AgentPlatform agentPlatform;
    private final Goal reactToExternalEventGoal;                    // "ReactToExternalEvent" goal
    private final List<DomainEventType<E>> producedDomainEventTypes;
    private final List<DomainEventType<E>> errorEventTypes;

    @Override
    public Stream<E> apply(InputEvent event, S state) {
        // 1. Set up the Blackboard with event, state, memories, aggregate service records
        Blackboard blackboard = new Blackboard();
        blackboard.add(event);
        blackboard.add(state);
        blackboard.addAll(((MemoryAwareState) state).getMemories());
        blackboard.addAll(aggregateServiceRecords);
        blackboard.set("agenticAggregateId", state.getAggregateId());
        blackboard.set("isCommandProcessing", false);
        blackboard.set("isExternalEvent", true);
        blackboard.set("hasMemories", !((MemoryAwareState) state).getMemories().isEmpty());

        // 2. Create AgentProcess via agentPlatform with the ReactToExternalEvent goal
        AgentProcess agentProcess = agentPlatform.createAgentProcess(reactToExternalEventGoal, blackboard);

        // 3. Call agentProcess.tick() in a loop until end state
        List<E> collectedEvents = new ArrayList<>();
        while (!agentProcess.isComplete()) {
            agentProcess.tick();
            collectedEvents.addAll(AgentProcessResultTranslator.collectEvents(blackboard));
        }

        // 4. Return collected DomainEvent instances as Stream<E>
        return collectedEvents.stream();
    }

    @Override
    public DomainEventType<InputEvent> getEventType() { return domainEventType; }

    @Override
    public boolean isCreate() { return false; }

    // ...
}
```

**Goal selection per adapter**: Each adapter type is hardcoded to use its specific goal:
- `AgenticCommandHandlerFunctionAdapter` → `ProcessCommandGoal` (precondition: `isCommandProcessing`)
- `AgenticEventHandlerFunctionAdapter` → `ReactToExternalEventGoal` (precondition: `isExternalEvent`)

The goals are resolved from the `AgentPlatform` at adapter construction time (in `AgenticAggregateRuntimeFactory`) and stored as final fields. This makes the goal selection deterministic — the adapter **knows** which goal to use because of its type, not because of runtime conditions.

**Why adapters instead of bypassing**: This approach:
- Reuses the existing `handleCommand()` / `handleEvent()` flow in `KafkaAggregateRuntime` — no need to change its `private` methods or refactor to inheritance
- The adapter receives the materialized `Command` (or `DomainEvent`) and the current `AggregateState` already deserialized
- The returned `Stream<DomainEvent>` is applied through the standard `processDomainEvent()` chain
- `AgentPlatform` and other agent resources are injected at adapter construction time
- The Blackboard setup (command/event, state, memories, aggregate service records) is done inside the adapter's `apply()` method
- `tick()` lifecycle management happens inside the adapter

**Impact on section 4.3 (Event Application Mechanism)**: With the adapter approach, the event application mechanism is greatly simplified. The adapter returns events as a `Stream<DomainEvent>`, and the standard `KafkaAggregateRuntime.handleCommand()` already iterates and calls `processDomainEvent()` for each one. **No need to make `processDomainEvent` protected or refactor to inheritance** — the existing delegation pattern works as-is.

### Design Constraint: Agent-Handled Commands Cannot Create State

Agent-handled commands (declared in `agentHandledCommands`) **always return `isCreate() = false`**. Aggregate creation must use a deterministic `@CommandHandler(create = true)` method.

**Rationale**:
- Creation must be deterministic to ensure the aggregate always starts in a known, valid initial state. An LLM-based agent could produce inconsistent or invalid initial state, leaving the aggregate in an undefined condition.
- The `AgenticCommandHandlerFunctionAdapter.apply(command, state)` receives the current state as a parameter. For create commands, this state is `null`, which the adapter cannot meaningfully pass to an agent's Blackboard.
- This is consistent with how agentic aggregates are designed as singletons with well-defined creation events — the "intelligence" applies to subsequent command processing, not to bootstrapping.

Every `AgenticAggregate` must therefore have at least one deterministic `@CommandHandler(create = true)` method.

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

### ~~1.4 Expose Memories on AgentProcess Blackboard~~ — Moved to Adapter

> **Note**: This section is superseded by the adapter approach. Blackboard setup (including memories, state, aggregate service records, and conditions) is done inside the agentic handler adapters' `apply()` method — see the `AgenticCommandHandlerFunctionAdapter` and `AgenticEventHandlerFunctionAdapter` code in the "Agentic Handler Adapters" section. The adapter already has access to the state (passed as a parameter to `apply()`), so the partition does not need to set up the blackboard.

---

## Phase 2: Create Akces Embabel Component with Goals, Actions, and Conditions

### 2.1 Create `AkcesAgentComponent` — The `@EmbabelComponent` Class

**What**: Create a new class `AkcesAgentComponent` annotated with Embabel's `@EmbabelComponent` (which includes `@Component`) that houses reusable Goals, Actions, and Conditions for Akces-based agentic systems.

**Package**: `org.elasticsoftware.akces.agentic.agent`

**Why**: Embabel discovers Goals/Actions/Conditions by scanning `@EmbabelComponent`-annotated classes. This provides a centralized place for Akces-specific agent capabilities that are automatically registered with the `AgentPlatform`.

### 2.2 Memory Management Action: `StoreMemoryAction`

**What**: An `@Action`-annotated method that produces a `MemoryStoredEvent` directly. The event is applied to the state via the built-in `@EventSourcingHandler` for `MemoryStoredEvent`.

```
@Action(description = "Store a learned fact as a memory entry for the agentic aggregate")
```

**Behavior**:
1. Reads the current aggregate ID from the blackboard (set by the partition before agent invocation)
2. Takes `subject`, `fact`, `citations`, and `reason` as inputs (from blackboard or method parameters)
3. Creates a `MemoryStoredEvent` and returns it on the blackboard
4. The partition collects the event and applies it through the built-in `@EventSourcingHandler` (which calls `MemoryAwareState.withMemory()`)

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

**What**: A Goal that represents the objective of analyzing the information from the current agent session — which is available on the Blackboard — and distilling useful memories from it. This goal is also responsible for **enforcing the memory capacity limit** defined by `@AgenticAggregateInfo.maxMemories()`.

```
Goal: "LearnFromProcess"
Description: "Analyze current session information and distill useful memories"
```

**Why a Goal and not an Action**: The memory distillation process requires **multi-step LLM reasoning**, not just data manipulation. A simple `@Action` can only perform a single operation (e.g., store a memory, recall memories). The `LearnFromProcessGoal` orchestrates a reasoning pipeline:
1. The Blackboard contains all session information: the original command/event, the current aggregate state, any domain events produced, existing memories, and aggregate service records.
2. The Embabel GOAP planner uses LLM reasoning to analyze this session context and determine what facts are worth remembering — this is the intelligence step that cannot be reduced to a single Action.
3. The goal then uses Actions (`RecallMemoriesAction`, `StoreMemoryAction`, `ForgetMemoryAction`) as execution primitives to actually persist the decisions.

This separation of **analysis** (LLM reasoning within the Goal) from **execution** (Actions) is a fundamental Embabel pattern: Goals define *what* to achieve, Actions define *how* to do it, and the LLM bridges the gap.

**Constraints**:
- **Maximum 3 new memories** per agent process execution. This prevents memory bloat from a single interaction.
- **No duplicate memories**: Before storing, the agent must check existing memories and skip any fact that is already stored (same subject + substantially similar fact content).
- **Memory capacity enforcement**: After storing new memories, if the total memory count exceeds `maxMemories`, the agent must evict the oldest entries using `ForgetMemoryAction` until the count is within the allowed limit. This replaces the previous `enforceMemorySlidingWindow()` mechanism — the agent itself is responsible for managing its memory capacity as part of the learning process.
- **Memory field guidance** (to be included in the Goal's prompt instructions):
  - `subject`: A 1-3 word topic label (e.g., "error handling", "user preferences", "market patterns"). Used for grouping and retrieval.
  - `fact`: A clear, concise factual statement (max ~200 chars). Should be actionable and self-contained.
  - `citations`: Source reference — the command/event type that triggered this learning, or relevant data points from the aggregate state.
  - `reason`: Why this fact is worth remembering — what future decisions it informs. Should be 2-3 sentences explaining the significance.

**Plan**: The agent planner would compose this as:
1. `RecallMemoriesAction` → check existing memories (to avoid duplicates and assess capacity)
2. (LLM reasoning over Blackboard session context) → determine what new facts were learned, respecting the max-3 limit
3. `StoreMemoryAction` → persist new memories (producing `MemoryStoredEvent`)
4. `ForgetMemoryAction` → evict oldest memories if capacity exceeded, or replace outdated memories (producing `MemoryRevokedEvent`)

**Why**: This is the foundational agentic behavior — after processing any command, the agent should learn from the experience and update its knowledge base. The constraints ensure memory quality over quantity. By moving memory capacity enforcement into the agent's learning goal, we eliminate the need for the framework-level `enforceMemorySlidingWindow()` method and the internal `StoreMemoryCommand`/`ForgetMemoryCommand` commands.

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

### ~~3.3 Action: `EmitDomainEventsAction`~~ — REMOVED

> **Note**: This action has been removed. Domain events produced by agent actions (e.g., `@Action` methods that return `DomainEvent` instances, `StoreMemoryAction`, `ForgetMemoryAction`) are placed on the Blackboard directly. The `AgentProcessResultTranslator` collects all `DomainEvent` objects from the blackboard after each `tick()` — there is no need for an explicit "emit" action. The adapter's `apply()` method returns the collected events as `Stream<DomainEvent>`, and the standard `handleCommand()` flow applies them via `processDomainEvent()`.

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

**Why**: This is a framework building block — a reusable Action that can be composed into different Goals. While not strictly required for the initial integration, it provides a general-purpose "analyze state" primitive that higher-level goals and future framework extensions can build upon. Keep for now.

### 3.6 Action: `DiscoverAggregateServicesAction`

```
@Action(description = "Discover other aggregates in the system and their supported commands", readOnly = true)
```

**What**: Exposes the `AggregateServiceRecord`s loaded from the `Akces-Control` topic to the agent, enabling it to discover what other aggregates exist in the system and what commands they accept.

**Behavior**:
1. Reads the `List<AggregateServiceRecord>` from the blackboard (populated by the agentic handler adapter before agent invocation — see "Agentic Handler Adapters" section)
2. For each `AggregateServiceRecord`, provides:
   - `aggregateName` — the logical name of the aggregate
   - `commandTopic` — the Kafka topic for sending commands
   - `domainEventTopic` — the topic where the aggregate publishes events
   - `type` — `STANDARD` or `AGENTIC`
   - `supportedCommands` — list of command types the aggregate accepts (with type name and version)
   - `producedEvents` — list of domain event types the aggregate produces
   - `consumedEvents` — list of external event types the aggregate consumes
3. Returns a structured summary that the LLM can reason about to determine what commands can be sent to achieve a goal

**Blackboard Setup**: The `AkcesAgenticAggregateController` already maintains a `Map<String, AggregateServiceRecord> aggregateServices` populated from the `Akces-Control` topic at startup. The agentic handler adapters (`AgenticCommandHandlerFunctionAdapter` / `AgenticEventHandlerFunctionAdapter`) place these records on the blackboard before invoking the agent, making them available to this action and to LLM tool calls.

**Why**: An agentic aggregate needs to know what other aggregates exist in the system to coordinate with them. For example, a TradingAdvisor agent may need to discover that a `Wallet` aggregate accepts `CreditWalletCommand` and a `Portfolio` aggregate accepts `PlaceOrderCommand`. Without aggregate discovery, the agent can only work with hardcoded knowledge of the system topology. Exposing `AggregateServiceRecord`s enables dynamic, system-aware reasoning.

### 3.7 Goal: `ProcessCommandGoal`

```
Goal: "ProcessCommand"
Description: "Process an incoming command through AI-assisted reasoning and produce domain events"
Preconditions: isCommandProcessing
```

**Plan**:
1. `RecallMemoriesAction` → load relevant prior knowledge
2. `AnalyzeAggregateStateAction` → understand current state
3. (LLM reasoning with MCP tools) → determine actions and produce domain events on the blackboard (including error events from `agentProducedErrors` if applicable)
4. `LearnFromProcessGoal` (sub-goal) → learn from experience

**Why**: This is the top-level goal for command processing in an agentic aggregate. It replaces the deterministic `@CommandHandler` flow with an AI-assisted reasoning loop for commands declared in `agentHandledCommands`.

### 3.8 Goal: `ReactToExternalEventGoal`

```
Goal: "ReactToExternalEvent"
Description: "React to an external domain event through AI-assisted reasoning"
Preconditions: isExternalEvent
```

**Plan**:
1. `RecallMemoriesAction` → load relevant prior knowledge
2. `AnalyzeAggregateStateAction` → understand current state
3. (LLM reasoning) → determine appropriate response and produce domain events on the blackboard (including error events from `agentProducedErrors`)
4. `SendCommandAction` → send commands if needed
5. `LearnFromProcessGoal` (sub-goal) → learn from experience

**Why**: This is the top-level goal for handling external events declared in `agentHandledEvents`. Agentic aggregates subscribe to all partitions of external event topics, and this goal enables AI-assisted reasoning about those events.

### ~~3.9 Goal: `ManageKnowledgeGoal`~~ — REMOVED

> **Note**: This goal has been removed. Memory management (storing, recalling, forgetting, capacity enforcement) is fully handled by `LearnFromProcessGoal` (section 2.7), which runs as a sub-goal after every command/event processing cycle. A separate knowledge management goal would duplicate this responsibility. If periodic memory optimization is needed in the future, it can be revisited as a scheduled task rather than a separate Goal.

---

## Phase 4: Integration with AgenticAggregatePartition

### 4.1 Agent-Assisted Command Processing via Handler Adapters

**What**: Agent-handled commands and events are processed through the standard `KafkaAggregateRuntime` handler flow, but using `AgenticCommandHandlerFunctionAdapter` / `AgenticEventHandlerFunctionAdapter` instead of deterministic adapters.

**Design: Adapter-Based Agent Processing**

When a command arrives whose type is declared in `agentHandledCommands`:
1. `KafkaAggregateRuntime.handleCommand()` looks up the registered `CommandHandlerFunction` — which is an `AgenticCommandHandlerFunctionAdapter`
2. The adapter's `apply(command, state)` method:
   a. Sets up the Blackboard with the command, state, memories, and aggregate service records
   b. Creates an `AgentProcess` via `agentPlatform.createAgentProcess(processCommandGoal, blackboard)`
   c. Calls `agentProcess.tick()` in a loop until the process reaches an end state (completed/failed)
   d. Collects `DomainEvent` instances from the blackboard after each tick
   e. Returns them as `Stream<DomainEvent>`
3. The standard `handleCommand()` flow iterates over the returned events and calls `processDomainEvent()` for each one
4. State-changing events are applied through `@EventSourcingHandler` methods (deterministic state transitions)
5. Error events are emitted but do NOT change state

For commands with a standard `@CommandHandler` method, the existing `CommandHandlerFunctionAdapter` (deterministic) path is used unchanged.

For external events declared in `agentHandledEvents`, the same adapter-based flow applies via `AgenticEventHandlerFunctionAdapter`, which uses `agentPlatform.createAgentProcess(reactToExternalEventGoal, blackboard)`.

**Key advantage**: No changes needed to `KafkaAggregateRuntime` internals. The `handleCommand()` and `handleEvent()` methods already work with the `CommandHandlerFunction` / `EventHandlerFunction` interfaces — the agentic adapters are just new implementations that route through the agent instead of a deterministic method.

> **Note**: Incremental tick support (single tick per `apply()`, with active task tracking across poll iterations) is deferred to a separate plan. See [Incremental Agent Task Processing Plan](../plans/agenttasks.md).

### 4.2 AgentProcess Result Translation

**What**: Define how `AgentProcess` results (from the Embabel `Blackboard`) are translated back into Akces domain events and commands.

**Changes**:
- After each `agentProcess.tick()` call, inspect the blackboard for:
  - `DomainEvent` objects → emit as domain events (both state-changing and error events)
  - `MemoryStoredEvent` / `MemoryRevokedEvent` → apply through the built-in `@EventSourcingHandler` methods
  - `Command` objects → send through the command bus
- Create a `AgentProcessResultTranslator` utility class to handle this translation
- Accept all `ErrorEvent` instances at runtime, even if not declared in `agentProducedErrors`. Log a warning for undeclared error events but do not reject them.

The adapter calls `tick()` in a loop until the process reaches an end state, collecting events after each tick. All collected events are returned as a single `Stream<DomainEvent>` from `apply()`.

### 4.3 Event Application Mechanism — Simplified by Adapter Approach

**What**: The agentic handler adapters (`AgenticCommandHandlerFunctionAdapter` / `AgenticEventHandlerFunctionAdapter`) return `DomainEvent` instances as a `Stream<DomainEvent>` from their `apply()` method. The existing `KafkaAggregateRuntime.handleCommand()` and `handleEvent()` methods already iterate over this stream and call `processDomainEvent()` for each event.

**No changes needed to `KafkaAggregateRuntime`**: Because the adapter pattern plugs into the existing handler contract, the `processDomainEvent()` method does not need to be made `protected` and `KafkaAgenticAggregateRuntime` does not need to extend `KafkaAggregateRuntime`. The delegation pattern remains as-is.

**Event flow**:
```
KafkaAggregateRuntime.handleCommand():
  1. Calls adapter.apply(command, state)  // AgenticCommandHandlerFunctionAdapter
  2. Adapter internally: sets up Blackboard, runs tick() in loop until end state, collects events
  3. Returns Stream<DomainEvent>
  4. handleCommand() iterates and calls processDomainEvent() for each event
  5. State-changing events → @EventSourcingHandler → new state record
  6. Error events → emitted without state change
```

> **Note**: Incremental tick support is deferred to a separate plan. See [Incremental Agent Task Processing Plan](../plans/agenttasks.md).

### 4.4 Transactional Boundaries and Timeout Configuration

**What**: The agent tick-to-completion loop runs inside the Kafka transaction that `AgenticAggregatePartition.processRecords()` opens. All events produced by the agent process — including `MemoryStoredEvent`, `MemoryRevokedEvent`, state-changing events, and error events — are part of that single transaction. This ensures atomicity: either the entire agent process result is persisted, or none of it is.

**Transaction lifecycle**:
```
AgenticAggregatePartition.processRecords():
  1. producer.beginTransaction()
  2. Process external events (if any)
  3. Process commands:
     a. handleCommand() → adapter.apply() → tick() loop → Stream<DomainEvent>
     b. processDomainEvent() for each event → state records + event records written
     c. (agent may take seconds to minutes for LLM calls, MCP tool invocations, GOAP planning)
  4. producer.sendOffsetsToTransaction()
  5. producer.commitTransaction()
```

**Timeout risk**: Agent processing involves LLM calls, MCP tool invocations, and multi-step GOAP planning. This can easily exceed the default Kafka `transaction.timeout.ms` (60 seconds). If the transaction times out, the broker will abort it and all produced records are lost.

**Solution**: The `ProducerFactory` instance used by `AgenticAggregatePartition` must be configured with an increased `transaction.timeout.ms`. This is a separate `ProducerFactory` bean (`agenticServiceProducerFactory`) already created by `AgenticAggregateBeanFactoryPostProcessor`, so it can have different settings from the standard aggregate producer without affecting other services.

**Changes**:
- In `AgenticAggregateServiceApplication` (or wherever `agenticServiceProducerFactory` is configured), set `transaction.timeout.ms` to a higher value appropriate for agent workloads. A recommended starting point is **300000** (5 minutes), configurable via application property `akces.agentic.transaction-timeout-ms`.
- Document this configuration requirement so that operators know to tune it based on their expected agent execution times.
- The adapter's `apply()` method should log a warning if the tick loop exceeds a configurable threshold (e.g., 80% of the transaction timeout) to provide early visibility into potential timeout risks.

**Error handling during agent execution**:
- If `AgentProcess.tick()` throws an exception, the adapter should catch it, log the error, and return an appropriate `ErrorEvent` (e.g., `CommandExecutionError`) so that the transaction can still commit with a meaningful error result rather than aborting silently.
- If the Kafka transaction is aborted (e.g., due to timeout), the standard `processRecords()` catch block calls `producer.abortTransaction()` and `stateRepository.rollback()`, which discards all changes from that processing cycle. The command/event will be re-processed on the next poll iteration.

### 4.5 Agent Context Setup

**What**: Define the standard set of objects placed on the `Blackboard` before agent invocation. This is done inside the agentic handler adapters' `apply()` method.

**Standard Blackboard Content** (set up by `AgenticCommandHandlerFunctionAdapter` / `AgenticEventHandlerFunctionAdapter`):
- `Command` or `DomainEvent` — the materialized trigger (already deserialized by the runtime)
- `AggregateState` — the current state (always present — the framework ensures singleton state is auto-created)
- `List<AgenticAggregateMemory>` — current memories (extracted from state via `MemoryAwareState.getMemories()`)
- `String agenticAggregateId` — the aggregate identifier
- `List<AggregateServiceRecord>` — all registered aggregate services from the `Akces-Control` topic, enabling the agent to discover other aggregates and their supported commands
- `AggregateRuntime` — for type information (including registered `agentProducedErrors` types)
- Conditions: `hasMemories`, `isCommandProcessing`/`isExternalEvent`

---

## Phase 5: Testing

### 5.1 Unit Tests (Phase 2 — Actions, Goals, Conditions)

Phase 2 components (Actions, Goals, Conditions in `AkcesAgentComponent`) are tested at the **unit level only** — no Kafka, no Spring Boot context, no integration test infrastructure. These tests verify the individual building blocks in isolation using mocked Blackboard contexts.

| Test Class | What to Verify |
|-----------|---------------|
| `AkcesAgentComponentTest` | All Actions, Goals, and Conditions are correctly annotated and discoverable |
| `StoreMemoryActionTest` | Produces valid `MemoryStoredEvent` from blackboard context |
| `ForgetMemoryActionTest` | Produces valid `MemoryRevokedEvent` from blackboard context |
| `RecallMemoriesActionTest` | Correctly filters memories by subject/keyword |
| `AgentProcessResultTranslatorTest` | Correctly extracts events and commands from blackboard |

### 5.2 Unit Tests (Phase 1, Annotation Extensions)

These unit tests verify the wiring and registration of agent-specific metadata.

| Test Class | What to Verify |
|-----------|---------------|
| `AgentPlatformInjectionTest` | Verifies `AgentPlatform` is correctly wired through factory → runtime; verifies fatal error when `AgentPlatform` is absent |
| `AgentProducedErrorsRegistrationTest` | Verifies `agentProducedErrors` are registered as `DomainEventType(error=true)` |
| `AgentHandledCommandsRegistrationTest` | Verifies `agentHandledCommands` create `CommandType` entries with `AgenticCommandHandlerFunctionAdapter` |
| `AgentHandledEventsRegistrationTest` | Verifies `agentHandledEvents` create external `DomainEventType` entries with `AgenticEventHandlerFunctionAdapter` |
| `DiscoverAggregateServicesActionTest` | Verifies `AggregateServiceRecord`s are correctly exposed on the blackboard |

### 5.3 Test Agent Implementation: `TestTradingAdvisorAgent`

Based on the TradingAdvisor example from the beginning of this plan, create a concrete test agent implementation in the test sources:

**Package**: `org.elasticsoftware.akces.agentic.testfixtures`

#### Test Aggregate

```java
@AgenticAggregateInfo(
    value = "TestTradingAdvisor",
    stateClass = TestTradingAdvisorState.class,
    agentHandledCommands = { TestAnalyzeMarketCommand.class },
    agentHandledEvents = { TestMarketDataUpdatedEvent.class },
    agentProducedErrors = { TestMarketUnavailableErrorEvent.class }
)
public class TestTradingAdvisorAggregate implements AgenticAggregate<TestTradingAdvisorState> {

    @CommandHandler(create = true,
                    produces = {TestTradingAdvisorCreatedEvent.class}, errors = {})
    public Stream<DomainEvent> create(TestCreateTradingAdvisorCommand cmd, TestTradingAdvisorState isNull) {
        return Stream.of(new TestTradingAdvisorCreatedEvent(cmd.id()));
    }

    @EventSourcingHandler(create = true)
    public TestTradingAdvisorState create(TestTradingAdvisorCreatedEvent e, TestTradingAdvisorState isNull) {
        return new TestTradingAdvisorState(e.id(), List.of(), List.of());
    }

    @EventSourcingHandler
    public TestTradingAdvisorState apply(TestMarketAnalysisCompletedEvent e, TestTradingAdvisorState s) {
        var analyses = new ArrayList<>(s.analyses());
        analyses.add(e.analysis());
        return new TestTradingAdvisorState(s.id(), s.memories(), analyses);
    }
}
```

#### Test State (implements MemoryAwareState)

```java
@AggregateStateInfo(type = "TestTradingAdvisorState", version = 1)
public record TestTradingAdvisorState(
    String id,
    List<AgenticAggregateMemory> memories,
    List<String> analyses
) implements AggregateState, MemoryAwareState {

    @Override
    public String getAggregateId() { return id; }

    @Override
    public List<AgenticAggregateMemory> getMemories() { return memories; }

    @Override
    public TestTradingAdvisorState withMemory(AgenticAggregateMemory memory) {
        var updated = new ArrayList<>(memories);
        updated.add(memory);
        return new TestTradingAdvisorState(id, List.copyOf(updated), analyses);
    }

    @Override
    public TestTradingAdvisorState withoutMemory(String memoryId) {
        return new TestTradingAdvisorState(id,
            memories.stream().filter(m -> !m.id().equals(memoryId)).toList(),
            analyses);
    }
}
```

#### Test Commands and Events

| Type | Class | Notes |
|------|-------|-------|
| Command | `TestCreateTradingAdvisorCommand` | Deterministic creation, has `@CommandHandler` |
| Command | `TestAnalyzeMarketCommand` | Agent-handled, declared in `agentHandledCommands` |
| Event | `TestTradingAdvisorCreatedEvent` | From deterministic `@CommandHandler` |
| Event | `TestMarketAnalysisCompletedEvent` | Produced by agent, has `@EventSourcingHandler` |
| Event (external) | `TestMarketDataUpdatedEvent` | Agent-handled, declared in `agentHandledEvents` |
| ErrorEvent | `TestMarketUnavailableErrorEvent` | Agent-produced, declared in `agentProducedErrors` |

#### Test Embabel Agent Component

```java
@EmbabelComponent
public class TestTradingAdvisorAgentComponent {

    @Action(description = "Analyze market data and produce a market analysis event")
    @AchievesGoal("ProcessCommand")
    public TestMarketAnalysisCompletedEvent analyzeMarket(
            TestAnalyzeMarketCommand command,
            TestTradingAdvisorState state) {
        // Simple deterministic logic for testing — no LLM needed
        return new TestMarketAnalysisCompletedEvent(
            command.id(),
            "Analysis of " + command.market() + ": bullish trend detected");
    }

    @Action(description = "React to market data update")
    @AchievesGoal("ReactToExternalEvent")
    public TestMarketAnalysisCompletedEvent reactToMarketData(
            TestMarketDataUpdatedEvent event,
            TestTradingAdvisorState state) {
        return new TestMarketAnalysisCompletedEvent(
            state.id(),
            "Reactive analysis for " + event.market());
    }

    @Action(description = "Produce an error when market is unavailable")
    @AchievesGoal("ProcessCommand")
    public TestMarketUnavailableErrorEvent marketUnavailable(
            TestAnalyzeMarketCommand command) {
        return new TestMarketUnavailableErrorEvent(
            command.id(),
            "Market " + command.market() + " is currently unavailable");
    }
}
```

**Why**: A concrete test agent implementation enables:
- Integration tests that exercise the full agent loop (command → Embabel → events → state)
- Validation of the event application mechanism from section 4.3
- Testing of `agentHandledCommands`, `agentHandledEvents`, and `agentProducedErrors` registration
- A reference implementation that documents the expected patterns for real-world agents

### 5.4 Integration Tests (Phase 4 — Full Agent Loop)

Integration tests exercise the full agent loop from command/event arrival through Embabel agent invocation to state application. These require the `TestTradingAdvisorAgent` fixtures from section 5.3.

| Test Class | What to Verify |
|-----------|---------------|
| `EmbabelAgentLoopIntegrationTest` | Full agent invocation using `TestTradingAdvisorAgent`: `TestAnalyzeMarketCommand` → agent → `TestMarketAnalysisCompletedEvent` applied to state |
| `MemoryLearningIntegrationTest` | Agent stores memory via `StoreMemoryAction` → `MemoryStoredEvent` emitted and applied to state |
| `AgentErrorEventIntegrationTest` | Agent produces `TestMarketUnavailableErrorEvent` → event emitted, state unchanged, undeclared errors logged with warning |
| `AgentExternalEventIntegrationTest` | `TestMarketDataUpdatedEvent` → agent → `TestMarketAnalysisCompletedEvent` applied to state |
| `EventApplicationMechanismTest` | Verifies that DomainEvents returned by `AgenticCommandHandlerFunctionAdapter.apply()` are correctly applied via the standard `handleCommand()` → `processDomainEvent()` flow (state transitions, generation numbers, error event handling) |

---

## File Inventory

### New Files

| File | Package | Purpose |
|------|---------|---------|
| `AkcesAgentComponent.java` | `o.e.a.agentic.agent` | `@EmbabelComponent` with reusable Actions, Conditions |
| `AkcesAgentGoals.java` | `o.e.a.agentic.agent` | Goal definitions as static factory methods |
| `AgentProcessResultTranslator.java` | `o.e.a.agentic.agent` | Translates `AgentProcess` results to Akces domain objects |
| `AgenticCommandHandlerFunctionAdapter.java` | `o.e.a.agentic.runtime` | `CommandHandlerFunction` impl that routes agent-handled commands through the Embabel agent via `ProcessCommandGoal` |
| `AgenticEventHandlerFunctionAdapter.java` | `o.e.a.agentic.runtime` | `EventHandlerFunction` impl that routes agent-handled external events through the Embabel agent via `ReactToExternalEventGoal` |

### Test Files (new)

| File | Package | Purpose |
|------|---------|---------|
| `TestTradingAdvisorAggregate.java` | `o.e.a.agentic.testfixtures` | Test agentic aggregate with deterministic + agent-handled commands |
| `TestTradingAdvisorState.java` | `o.e.a.agentic.testfixtures` | MemoryAwareState with analyses list |
| `TestCreateTradingAdvisorCommand.java` | `o.e.a.agentic.testfixtures` | Deterministic creation command |
| `TestAnalyzeMarketCommand.java` | `o.e.a.agentic.testfixtures` | Agent-handled command |
| `TestTradingAdvisorCreatedEvent.java` | `o.e.a.agentic.testfixtures` | Creation event from `@CommandHandler` |
| `TestMarketAnalysisCompletedEvent.java` | `o.e.a.agentic.testfixtures` | State-changing event from agent |
| `TestMarketDataUpdatedEvent.java` | `o.e.a.agentic.testfixtures` | External event, agent-handled |
| `TestMarketUnavailableErrorEvent.java` | `o.e.a.agentic.testfixtures` | Agent-produced error event |
| `TestTradingAdvisorAgentComponent.java` | `o.e.a.agentic.testfixtures` | `@EmbabelComponent` with test actions for the agent |

### Modified Files

| File | Changes |
|------|---------|
| `AgenticAggregateInfo.java` | Add `agentHandledCommands()`, `agentHandledEvents()`, `agentProducedErrors()` properties |
| `AgenticAggregateRuntime.java` | Add `getAgentPlatform()` method |
| `KafkaAgenticAggregateRuntime.java` | Accept and store `AgentPlatform`; implement `getAgentPlatform()` (delegation pattern unchanged) |
| `AgenticAggregateRuntimeFactory.java` | Resolve `AgentPlatform` from `ApplicationContext`; create `AgenticCommandHandlerFunctionAdapter` / `AgenticEventHandlerFunctionAdapter` for agent-handled commands/events; process `agentHandledCommands`, `agentHandledEvents`, `agentProducedErrors` from annotation |
| `AgenticAggregateBeanFactoryPostProcessor.java` | Read new annotation properties; register `CommandType` for `agentHandledCommands`, `DomainEventType(external=true)` for `agentHandledEvents`, `DomainEventType(error=true)` for `agentProducedErrors` |
| `AkcesAgenticAggregateController.java` | Expose `aggregateServices` map to adapters; populate `consumedEvents` in `AggregateServiceRecord` from `getExternalDomainEventTypes()` (including `agentHandledEvents`); remove `StoreMemoryCommand`/`ForgetMemoryCommand` from built-in command registrations |
| `AgenticAggregatePartition.java` | Remove `maxMemories` field, `enforceMemorySlidingWindow()` method, and `ForgetMemoryCommand` import; memory capacity enforcement is now handled by the `LearnFromProcessGoal` |
| `AgenticAggregateServiceApplication.java` | Configure `agenticServiceProducerFactory` with increased `transaction.timeout.ms` (default 300000 / 5 minutes); expose `akces.agentic.transaction-timeout-ms` as configurable property |

### Deleted Files

| File | Reason |
|------|--------|
| `StoreMemoryCommand.java` | Memory storage is now handled entirely by `StoreMemoryAction` (Embabel `@Action`) producing `MemoryStoredEvent` directly |
| `ForgetMemoryCommand.java` | Memory revocation is now handled entirely by `ForgetMemoryAction` (Embabel `@Action`) producing `MemoryRevokedEvent` directly |

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

3. **Synchronous Agent Execution**: Use `AgentProcess.tick()` in a loop until the process reaches an end state (tick-to-completion). The adapter calls `agentPlatform.createAgentProcess(goal, blackboard)` and runs the process synchronously inside the `apply()` method. Incremental tick support (single tick per invocation with active task tracking) is deferred to a [separate plan](../plans/agenttasks.md).

4. **MCP Tool Scoping**: MCP tools added in the core `AgenticAggregate` are **available to all Agents**. No per-aggregate tool scoping is needed — shared tools are the default.

5. **Error Event Validation at Runtime**: Accept **all `ErrorEvent`s** at runtime, even if they are not declared in `agentProducedErrors`. If an undeclared error event is emitted, **log a warning** but do not reject it. This provides flexibility while maintaining observability.

6. **Transactional Boundaries**: The agent tick-to-completion loop runs inside the Kafka transaction opened by `AgenticAggregatePartition.processRecords()`. The `agenticServiceProducerFactory` must be configured with an increased `transaction.timeout.ms` (default 300000 / 5 minutes, configurable via `akces.agentic.transaction-timeout-ms`). This is an agreed design — implement as proposed in section 4.4.

