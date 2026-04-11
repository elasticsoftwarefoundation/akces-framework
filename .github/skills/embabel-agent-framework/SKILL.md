---
name: embabel-agent-framework
description: >
  Guide for the Embabel Agent Framework concepts and how to create AI agents using Embabel,
  including its integration with the Akces CQRS/Event Sourcing Framework. Use this skill when
  asked about Embabel agents, agentic aggregates, agent actions/goals/conditions, the Embabel
  Blackboard, GOAP planning, or how to build AI-powered event-driven systems with Akces and Embabel.
---

# Embabel Agent Framework — Concepts and Akces Integration

## What is Embabel?

Embabel is a JVM framework (written in Kotlin, natural to use from Java) for authoring **agentic flows** that seamlessly mix LLM-prompted interactions with code and domain models. It is created by **Rod Johnson** (the creator of Spring).

Key differentiators:

- **Sophisticated planning** using Goal-Oriented Action Planning (GOAP) — a non-LLM AI algorithm from game AI.
- **Strong typing and OOP** — actions, goals and conditions are informed by a strongly typed domain model.
- **LLM mixing** — different LLMs for different actions (cost-effective, privacy-aware).
- **Built on Spring and the JVM** — full Spring DI, AOP, persistence, and transaction support.
- **Designed for testability** — both unit and agent end-to-end testing.

**Repository:** https://github.com/embabel/embabel-agent
**Documentation:** https://docs.embabel.com/embabel-agent/guide/0.3.5

---

## Core Concepts

### 1. Agents

An agent is a Spring-managed component annotated with `@Agent`. It defines a set of **actions**, **goals**, and **conditions** that the framework uses to plan and execute tasks.

```java
@Agent(description = "Processes customer support tickets")
public class SupportTicketAgent {

    @Action
    public TicketAnalysis analyzeTicket(SupportTicket ticket, OperationContext context) {
        return context.ai()
            .withDefaultLlm()
            .createObject(
                "Analyze this support ticket: " + ticket.content(),
                TicketAnalysis.class
            );
    }

    @AchievesGoal(description = "Ticket has been resolved")
    @Action
    public Resolution resolveTicket(TicketAnalysis analysis) {
        return new Resolution(analysis.suggestedAction());
    }
}
```

### 2. Actions (`@Action`)

Actions are methods annotated with `@Action` on an `@Agent` class. They are the steps an agent takes. Each action:

- Takes **domain objects** as parameters (resolved from the Blackboard by type).
- May also take infrastructure parameters like `OperationContext` or `ActionContext`.
- Returns a domain object that is automatically added to the Blackboard.
- Has implicit preconditions (its parameter types must be available on the Blackboard) and postconditions (its return type is added to the Blackboard).

Key attributes:
- `description` — human-readable description of the action.
- `pre` — additional preconditions (string condition names or SpEL expressions with `spel:` prefix).
- `post` — additional postconditions beyond the output type.
- `canRerun` — whether the action can be re-executed (default: `false`).
- `readOnly` — action has no external side effects (default: `false`).
- `clearBlackboard` — clear the blackboard after this action, keeping only output (default: `false`).
- `cost` / `value` — static cost/value between 0.0 and 1.0.
- `costMethod` / `valueMethod` — reference to `@Cost` method for dynamic cost computation.
- `trigger` — reactive trigger: action only fires when the specified type is the most recently added to the blackboard.
- `outputBinding` — explicit name for the output on the blackboard.

### 3. Goals (`@AchievesGoal`)

Goals represent what an agent is trying to achieve. An `@AchievesGoal` annotation on an `@Action` method indicates that completing that action achieves a specific goal.

```java
@AchievesGoal(description = "Generate a personalized recommendation")
@Action
public Recommendation generateRecommendation(CustomerProfile profile, OperationContext context) {
    // ...
}
```

### 4. Conditions (`@Condition`)

Conditions are boolean methods that evaluate the current state. They can be used as preconditions or postconditions for actions. They drive planning decisions.

```java
@Condition("customerVerified")
public boolean isCustomerVerified(Customer customer) {
    return customer.isVerified();
}

@Action(pre = {"customerVerified"})
public PremiumOffer createPremiumOffer(Customer customer) {
    // Only runs if customerVerified condition is true
    return new PremiumOffer(customer);
}
```

Conditions can also be specified inline with SpEL:

```java
@Action(pre = {"spel:assessment.urgency > 0.5"})
public void handleUrgentIssue(Issue issue, IssueAssessment assessment) {
    // Only runs when urgency exceeds 0.5
}
```

### 5. Blackboard

The Blackboard is the shared memory system for an agent process. It implements the Blackboard architectural pattern.

- **Central repository** for all domain objects, intermediate results, and process state.
- **Type-based access** — objects are indexed and retrieved by type.
- **Ordered storage** — objects maintain insertion order; the most recently added is the default.
- **Immutable objects** — once added, objects cannot be modified (new versions can be added).
- **Condition tracking** — maintains boolean conditions used by the planning system.

Action inputs come from the Blackboard. Action outputs are automatically added to the Blackboard. Conditions are evaluated based on Blackboard contents.

```java
// Retrieve objects by type
Person person = blackboard.last(Person.class);
List<Person> allPersons = blackboard.all(Person.class);

// Conditions
blackboard.setCondition("userVerified", true);
```

### 6. Planning (GOAP)

Planning occurs after each action execution using Goal-Oriented Action Planning:

1. **Analyze current state** — examine Blackboard contents.
2. **Identify available actions** — find actions whose preconditions are satisfied.
3. **Search for action sequences** — use A* to find optimal paths to the goal.
4. **Select optimal plan** — choose the best sequence based on cost and success probability.
5. **Execute next action** — run the first action in the plan and replan.

This creates an **OODA loop** (Observe-Orient-Decide-Act) that allows agents to adapt to unexpected results, recover from failures, and take advantage of new opportunities.

The planning step is pluggable. Embabel also supports **Utility AI** planning (selects actions by dynamic utility scores rather than preconditions/postconditions).

### 7. AgentProcess Lifecycle

An `AgentProcess` manages the complete execution lifecycle with these states:

| State | Description |
|-------|-------------|
| `NOT_STARTED` | Process has not started yet |
| `RUNNING` | Executing without known problems |
| `COMPLETED` | Successfully completed |
| `FAILED` | Failed and cannot continue |
| `TERMINATED` | Killed by an early termination policy |
| `KILLED` | Killed by user or platform |
| `STUCK` | Cannot formulate a plan to progress |
| `WAITING` | Waiting for user input or external event |
| `PAUSED` | Paused due to scheduling policy |

Execution methods:
- `tick()` — perform the next single step and return when an action completes.
- `run()` — execute as far as possible until completion, failure, or waiting.

### 8. Domain Objects

Embabel domain objects are strongly-typed data structures with behavior that can be selectively exposed to LLMs via `@Tool`:

```java
public class Customer {
    @Tool(description = "Calculate the customer's loyalty discount percentage")
    public BigDecimal getLoyaltyDiscount() {
        return loyaltyLevel.calculateDiscount(orders.size());
    }
}
```

Methods without `@Tool` are never exposed to LLMs. Domain objects are provided to LLMs via `context.ai().withToolObject(customer)`.

### 9. Execution Modes

Embabel supports three modes of execution:

- **Focused** — user code explicitly requests a particular agent to run.
- **Closed** — user intent is classified to choose among known agents.
- **Open** — the platform uses all available actions/goals to achieve the user's intent, potentially finding novel paths not envisioned by developers.

### 10. Subagents and Composition

Actions can invoke nested agent processes for composition:

```java
@Action
public Result delegateWork(TaskInput input, ActionContext context) {
    return context.asSubProcess(
        RunSubagent.fromAnnotatedInstance(innerAgent, Result.class)
    );
}
```

### 11. The `@EmbabelComponent` Annotation

Marks a class that provides actions, goals, and conditions but is not an agent itself. Useful with Utility AI planning where actions from multiple components are combined.

### 12. States (`@State`)

The `@State` annotation on data classes enables state-based workflows where actions are defined directly on state objects:

```java
@State
public record ProcessingState(String data, int iteration) {
    @Action(clearBlackboard = true)
    public LoopOutcome process() {
        if (iteration >= 3) return new DoneState(data);
        return new ProcessingState(data + "+", iteration + 1);
    }
}
```

### 13. Configuration

Embabel is configured via standard Spring Boot properties:

```yaml
embabel:
  models:
    default-llm: gpt-4.1-mini
    llms:
      cheapest: gpt-4.1-mini
      best: gpt-4.1
      reasoning: o1-preview
  agent:
    platform:
      name: my-agent-platform
```

---

## Akces Framework Integration with Embabel

The Akces Framework integrates Embabel through the `akces-agentic` module (`main/agentic`), enabling AI-assisted command and event processing within event-sourced aggregates.

### Architecture Overview

The integration bridges two frameworks:
- **Akces** provides the CQRS/Event Sourcing infrastructure (Kafka, state management, schema registry).
- **Embabel** provides the AI agent runtime (GOAP planning, LLM integration, Blackboard).

When a command or event arrives at an agentic aggregate, the Akces runtime creates an Embabel `AgentProcess`, populates its Blackboard with the aggregate's current state, memories, and context, then lets the Embabel planning engine decide which actions to execute. The resulting `DomainEvent` objects are collected from the Blackboard and fed back into the Akces event-sourcing pipeline.

### Key Components

#### `@AgenticAggregateInfo` Annotation

Marks a class as an agentic aggregate. In addition to standard aggregate capabilities, it declares:

- `agentHandledCommands` — command classes processed by the AI agent (no `@CommandHandler` needed).
- `agentHandledEvents` — external domain events processed by the AI agent (no `@EventHandler` needed).
- `agentProducedErrors` — error event types the AI agent may emit.
- `stateClass` — the aggregate state class.
- `maxMemories` — sliding-window capacity for the memory system (default: 100).

#### `AgenticAggregate<S>` Interface

Extends `Aggregate<S>` with:

- `getCreateDomainEvent()` — returns the domain event to auto-create initial state (agentic aggregates are singletons).
- `getMemories(S state)` — extracts memories from the state.

#### `TaskAwareState` Interface

State classes for agentic aggregates that track assigned tasks must implement:

- `getAssignedTasks()` — returns current assigned tasks.
- `withAssignedTask(AssignedTask)` — returns new state with task added.
- `withoutAssignedTask(String agentProcessId)` — returns new state with task removed.

#### `MemoryAwareState` Interface

State classes that maintain memories must implement:

- `getMemories()` — returns current memories.
- `withMemory(AgenticAggregateMemory)` — returns new state with memory added.
- `withoutMemory(String memoryId)` — returns new state with memory removed.

#### Built-in Commands and Events

| Type | Class | Description |
|------|-------|-------------|
| Command | `AssignTaskCommand` | Assigns a task to an agentic aggregate for AI processing |
| Event | `AgentTaskAssignedEvent` | Emitted when a task is assigned and an `AgentProcess` is created |
| Event | `AgentTaskFinishedEvent` | Emitted when an `AgentProcess` finishes (carries `AgentProcessStatusCode`) |
| Event | `MemoryStoredEvent` | Emitted when a memory entry is stored |
| Event | `MemoryRevokedEvent` | Emitted when a memory entry is revoked |

#### Agent Resolution

When a command/event arrives, the runtime resolves the Embabel `Agent` by matching against the aggregate name:

1. **Exact match**: `agent.getName().equals(aggregateName)`
2. **Agent-suffix match**: `agent.getName().equals(aggregateName + "Agent")`
3. **Default fallback**: `DefaultAgent` (always present, uses `PlannerType.UTILITY`)

#### Blackboard Population

When creating an `AgentProcess` for command processing, the Blackboard is populated with:

- `"command"` — the command being processed
- `"state"` — the current aggregate state
- `"agenticAggregateId"` — the aggregate identifier
- `"memories"` — the list of current memories from state
- `"aggregateServices"` — all known aggregate service records
- `"isCommandProcessing"` (condition) — `true`
- `"isExternalEvent"` (condition) — `false`
- `"hasMemories"` (condition) — whether any memories are present

For event processing, the same structure applies but with `"event"` instead of `"command"`, `"isCommandProcessing"` = `false`, and `"isExternalEvent"` = `true`.

#### Execution Flow

1. Command/event arrives at the agentic aggregate partition.
2. The `AgenticCommandHandlerFunctionAdapter` or `AgenticEventHandlerFunctionAdapter` creates an `AgentProcess` with populated Blackboard.
3. The process is **not** ticked immediately. The partition's idle-poll cycle (`resumeAgentTasks`) exclusively drives all `AgentProcess` advancement via `AgentProcessSingleTickRunner.tick()`.
4. After each tick, `AgentProcessResultTranslator.collectEvents()` drains `DomainEvent` objects from the Blackboard and hides them.
5. Collected events are fed into the Akces event-sourcing pipeline.
6. When `AgentProcess.getFinished()` returns `true`, an `AgentTaskFinishedEvent` is emitted with the `AgentProcessStatusCode`.

### Creating an Agentic Aggregate — Step by Step

#### 1. Define the aggregate state

The state must be a Java record implementing `AggregateState`, `TaskAwareState`, and optionally `MemoryAwareState`:

```java
@AggregateStateInfo(type = "MyAgentState", version = 1)
public record MyAgentState(
    String id,
    List<AssignedTask> assignedTasks,
    List<AgenticAggregateMemory> memories
) implements AggregateState, TaskAwareState, MemoryAwareState {

    @Override
    public String getAggregateId() { return id; }

    @Override
    public List<AssignedTask> getAssignedTasks() { return assignedTasks; }

    @Override
    public TaskAwareState withAssignedTask(AssignedTask task) {
        var tasks = new ArrayList<>(assignedTasks);
        tasks.add(task);
        return new MyAgentState(id, List.copyOf(tasks), memories);
    }

    @Override
    public TaskAwareState withoutAssignedTask(String agentProcessId) {
        return new MyAgentState(id,
            assignedTasks.stream()
                .filter(t -> !t.agentProcessId().equals(agentProcessId))
                .toList(),
            memories);
    }

    @Override
    public List<AgenticAggregateMemory> getMemories() { return memories; }

    @Override
    public MemoryAwareState withMemory(AgenticAggregateMemory memory) {
        var mems = new ArrayList<>(memories);
        mems.add(memory);
        return new MyAgentState(id, assignedTasks, List.copyOf(mems));
    }

    @Override
    public MemoryAwareState withoutMemory(String memoryId) {
        return new MyAgentState(id, assignedTasks,
            memories.stream()
                .filter(m -> !m.memoryId().equals(memoryId))
                .toList());
    }
}
```

#### 2. Define the creation event and its event-sourcing handler

```java
@DomainEventInfo(type = "MyAgentCreated", version = 1)
public record MyAgentCreatedEvent(
    @AggregateIdentifier String id
) implements DomainEvent {
    @Override
    public String getAggregateId() { return id; }
}
```

#### 3. Define the agentic aggregate

```java
@AgenticAggregateInfo(
    value = "MyAgent",
    stateClass = MyAgentState.class,
    description = "AI-powered agent aggregate",
    agentHandledCommands = { ProcessDataCommand.class },
    agentHandledEvents = { ExternalDataEvent.class },
    agentProducedErrors = { ProcessingFailedErrorEvent.class }
)
public class MyAgent implements AgenticAggregate<MyAgentState> {

    @Override
    public Class<MyAgentState> getStateClass() {
        return MyAgentState.class;
    }

    @Override
    public DomainEvent getCreateDomainEvent() {
        return new MyAgentCreatedEvent("MyAgent");
    }

    @EventSourcingHandler(create = true)
    public MyAgentState create(MyAgentCreatedEvent event, MyAgentState isNull) {
        return new MyAgentState(event.id(), List.of(), List.of());
    }

    // Additional @EventSourcingHandler methods for domain events produced by the agent
    @EventSourcingHandler
    public MyAgentState handle(DataProcessedEvent event, MyAgentState state) {
        // Update state based on event
        return state;
    }
}
```

#### 4. Create the corresponding Embabel Agent

Create an Embabel `@Agent` class that matches the aggregate name (e.g., `MyAgent` or `MyAgentAgent`):

```java
@Agent(name = "MyAgent", description = "Processes data using AI")
public class MyAgentAgent {

    @Action
    public DataProcessedEvent processData(
            ProcessDataCommand command,
            MyAgentState state,
            OperationContext context) {
        // Use LLM to analyze and process data
        var analysis = context.ai()
            .withDefaultLlm()
            .createObject(
                "Analyze this data: " + command.data(),
                DataAnalysis.class
            );

        return new DataProcessedEvent(
            state.getAggregateId(),
            analysis.result()
        );
    }

    @AchievesGoal(description = "Data has been processed")
    @Action
    public DataProcessedEvent complete(DataAnalysis analysis) {
        return new DataProcessedEvent(analysis.aggregateId(), analysis.result());
    }
}
```

The Embabel agent's actions receive their inputs from the Blackboard, which the Akces runtime populates with the command/event, aggregate state, and memories.

### Maven Dependency

Add the `akces-agentic` module to your project:

```xml
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-agentic</artifactId>
</dependency>
```

This transitively includes the Embabel agent starter and Spring AI MCP client. You also need to add a model starter for your preferred LLM provider:

```xml
<!-- Choose one or more -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-google-genai</artifactId>
</dependency>
```

### Important Design Rules

1. **Agentic aggregates are singletons** — one instance per partition. The framework auto-creates the initial state via `getCreateDomainEvent()`.
2. **Agent-handled commands cannot create state** — a deterministic `@EventSourcingHandler(create = true)` is always required.
3. **Agent processes are not ticked immediately** — they are advanced by the partition's idle-poll cycle (`resumeAgentTasks`), one tick at a time.
4. **Domain events produced by agents must be registered** — either as produced events on the `@CommandHandler`/`@EventHandler` or declared in `agentProducedErrors`.
5. **Undeclared error events are silently excluded** — they are logged at WARN level but not persisted. Always declare error events in `agentProducedErrors`.
6. **State must be immutable** — use Java records. `TaskAwareState` and `MemoryAwareState` mutation methods must return new instances.
7. **Embabel agent names must match** — the agent name should be `{AggregateName}` or `{AggregateName}Agent` for automatic resolution. Otherwise, the `DefaultAgent` fallback is used.

---

## Reference Links

- [Embabel Documentation](https://docs.embabel.com/embabel-agent/guide/0.3.5)
- [Embabel GitHub Repository](https://github.com/embabel/embabel-agent)
- [Embabel Java Template](https://github.com/embabel/java-agent-template)
- [Embabel Kotlin Template](https://github.com/embabel/kotlin-agent-template)
- [Embabel Examples](https://github.com/embabel/embabel-agent-examples)
- [Akces Framework Repository](https://github.com/elasticsoftwarefoundation/akces-framework)
- [Akces Agentic Module](https://github.com/elasticsoftwarefoundation/akces-framework/tree/main/main/agentic)
