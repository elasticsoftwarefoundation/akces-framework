# AssignTask Support for AgenticAggregate

## Overview

This plan adds built-in task assignment support to the Akces Framework's agentic aggregates. When an `AssignTask` command is sent to an `AgenticAggregate`, the framework handles it with a built-in command handler that creates an Embabel `AgentProcess` and emits an `AgentTaskAssigned` event containing the process ID and requesting party information.

This feature introduces:
1. A **`RequestingParty`** concept — a sealed interface representing who is requesting the task (an Agent or a Human), each with a `String role`.
2. A **built-in `AssignTaskCommand`** — the first framework-owned command, automatically registered for every `AgenticAggregate`.
3. A **built-in `AgentTaskAssignedEvent`** — emitted when the task is accepted, containing the Embabel `AgentProcess.getId()` value.
4. **Built-in command and event-sourcing handlers** — registered automatically by the framework, following the same pattern as the existing `MemoryStored`/`MemoryRevoked` built-in handlers.

> **Relationship to other plans**: This plan builds on the [Embabel AgentPlatform Integration Plan](../embabel-integration-plan.md) and is a prerequisite for the [Incremental Agent Task Processing Plan](../agenttasks.md). The `AgentTaskAssigned` event creates the link between a requesting party and an active Embabel `AgentProcess`, which the incremental tick model will later use to advance processes across poll loop iterations.

---

## Phase 1: Core Types — `RequestingParty` (api module)

### What

Introduce the `RequestingParty` concept as a sealed interface in the `api` module. This represents the entity that requested the task assignment — either another AI agent or a human user.

### Module

`main/api` — package `org.elasticsoftware.akces.aggregate`

### New Files

| File | Description |
|------|-------------|
| `RequestingParty.java` | Sealed interface with `String role()` method, Jackson `@JsonTypeInfo` / `@JsonSubTypes` for polymorphic serialization |
| `AgentRequestingParty.java` | Record implementing `RequestingParty` for agent requestors — fields: `String agentId`, `String agentName`, `String role` |
| `HumanRequestingParty.java` | Record implementing `RequestingParty` for human requestors — fields: `String userId`, `String displayName`, `String role` |

### Design Decisions

**Sealed interface vs. single record with enum discriminator:**

A sealed interface with `AgentRequestingParty` and `HumanRequestingParty` permits is preferred because:
- It leverages Java 25 sealed types and pattern matching (`switch` expressions with exhaustiveness checking)
- It allows each variant to carry distinct fields (agents have `agentId`/`agentName`, humans have `userId`/`displayName`)
- It aligns with the framework's preference for immutable records
- Jackson 3.x supports sealed type serialization via `@JsonTypeInfo`

**Location in `api` module:**

`RequestingParty` is placed in the `api` module (not `agentic`) because:
- It is a core domain concept that may be referenced by application-level aggregates, query models, and database models
- It follows the pattern of `AgenticAggregateMemory` and `MemoryAwareState`, which are also in `api`
- The `agentic` module depends on `api`, not the other way around

### Serialization

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentRequestingParty.class, name = "agent"),
    @JsonSubTypes.Type(value = HumanRequestingParty.class, name = "human")
})
public sealed interface RequestingParty permits AgentRequestingParty, HumanRequestingParty {
    /**
     * The role of this requesting party in the system (e.g., "administrator", "analyst", "orchestrator").
     */
    String role();
}
```

```java
public record AgentRequestingParty(
    String agentId,
    String agentName,
    String role
) implements RequestingParty {}
```

```java
public record HumanRequestingParty(
    String userId,
    String displayName,
    String role
) implements RequestingParty {}
```

### Dependencies

None — uses only Jackson annotations already available in the `api` module.

---

## Phase 2: Built-in Command and Event (agentic module)

### What

Create the `AssignTaskCommand` and `AgentTaskAssignedEvent` as framework-owned types in the `agentic` module, following the established pattern of `MemoryStoredEvent` and `MemoryRevokedEvent`.

### Module

`main/agentic` — new package `org.elasticsoftware.akces.agentic.commands` for the command, existing package `org.elasticsoftware.akces.agentic.events` for the event.

### New Files

| File | Package | Description |
|------|---------|-------------|
| `AssignTaskCommand.java` | `o.e.a.agentic.commands` | Built-in command — fields: `agenticAggregateId` (aggregate identifier), `taskDescription` (what the agent should do), `requestingParty` (who requested it), `taskMetadata` (optional Map of additional context) |
| `AgentTaskAssignedEvent.java` | `o.e.a.agentic.events` | Built-in event — fields: `agenticAggregateId`, `agentProcessId` (from `AgentProcess.getId()`), `taskDescription`, `requestingParty`, `taskMetadata`, `assignedAt` (Instant) |

### AssignTaskCommand

```java
@CommandInfo(type = "AssignTask", version = 1, description = "Assigns a task to an agentic aggregate for AI-assisted processing")
public record AssignTaskCommand(
    @AggregateIdentifier @NotNull String agenticAggregateId,
    @NotNull String taskDescription,
    @NotNull RequestingParty requestingParty,
    Map<String, String> taskMetadata
) implements Command {
    @Override
    @Nonnull
    public String getAggregateId() {
        return agenticAggregateId;
    }
}
```

### AgentTaskAssignedEvent

```java
@DomainEventInfo(type = "AgentTaskAssigned", version = 1, description = "Emitted when a task has been assigned to an agentic aggregate and an AgentProcess has been created")
public record AgentTaskAssignedEvent(
    @AggregateIdentifier String agenticAggregateId,
    @NotNull String agentProcessId,
    @NotNull String taskDescription,
    @NotNull RequestingParty requestingParty,
    Map<String, String> taskMetadata,
    @NotNull Instant assignedAt
) implements DomainEvent {
    @Override
    @Nonnull
    public String getAggregateId() {
        return agenticAggregateId;
    }
}
```

### Design Decisions

**First built-in command:**

This is the first framework-owned `Command` implementation. Until now, all commands were application-defined. The `AssignTask` command is a framework concern because it directly interacts with the Embabel runtime to create an `AgentProcess`, which is an infrastructure operation.

**`taskMetadata` field:**

A `Map<String, String>` provides extensibility for passing additional context to the agent process without changing the command schema. Examples: `correlationId`, `priority`, `deadline`, `sourceSystem`.

**Command module location:**

A new `commands` package is created in the `agentic` module, mirroring the existing `events` package. This keeps the layout symmetric and predictable.

### Type Registration Constants

In `AgenticAggregateRuntime` (or a dedicated constants class), add:

```java
CommandType<AssignTaskCommand> ASSIGN_TASK_COMMAND_TYPE = new CommandType<>(
    "AssignTask", 1, AssignTaskCommand.class, false, false, false);

DomainEventType<AgentTaskAssignedEvent> AGENT_TASK_ASSIGNED_TYPE = new DomainEventType<>(
    "AgentTaskAssigned", 1, AgentTaskAssignedEvent.class, false, false, false, false);
```

---

## Phase 3: Built-in Command Handler and Event-Sourcing Handler (agentic module)

### What

Implement the built-in handler that processes `AssignTaskCommand` and emits `AgentTaskAssignedEvent`, plus the event-sourcing handler that updates the aggregate state when the event is applied.

### Module

`main/agentic` — package `org.elasticsoftware.akces.agentic.runtime`

### Changes to Existing Files

#### `KafkaAgenticAggregateRuntime`

Add two new static methods following the existing `handleMemoryEvent` pattern:

1. **`handleAssignTask(Command command, AggregateState state)`** — Built-in command handler:
   - Casts command to `AssignTaskCommand`
   - Creates an `AgentProcess` via `agentPlatform.createAgentProcess(agent, ProcessOptions.DEFAULT, bindings)` with the task description and requesting party in the bindings
   - Retrieves the process ID via `agentProcess.getId()`
   - Returns a `Stream` containing a single `AgentTaskAssignedEvent` with all relevant fields

   > **Note:** Unlike the memory handlers (which are event-sourcing handlers and are `static`), this is a command handler that needs access to the `AgentPlatform` and `Agent` instances. This means the handler **cannot** be a static method reference. Instead, it will be implemented as a non-static method or as a dedicated adapter class.

2. **`handleAgentTaskAssignedEvent(DomainEvent event, AggregateState state)`** — Built-in event-sourcing handler:
   - Pattern matches on `AgentTaskAssignedEvent`
   - Updates the state to track the assigned task (see Phase 4 for state interface)
   - Returns the updated state

#### `AgenticAggregateRuntimeFactory`

Register the built-in command and event types, following the same pattern as memory events:

```java
// Register built-in AssignTask command handler
runtimeBuilder
    .addCommandHandler(ASSIGN_TASK_COMMAND_TYPE, assignTaskHandler)
    .addCommand(ASSIGN_TASK_COMMAND_TYPE);

// Register built-in AgentTaskAssigned event-sourcing handler
runtimeBuilder
    .addEventSourcingHandler(AGENT_TASK_ASSIGNED_TYPE, KafkaAgenticAggregateRuntime::handleTaskEvent)
    .addDomainEvent(AGENT_TASK_ASSIGNED_TYPE);
```

#### `AkcesAgenticAggregateController`

Register the `AssignTask` and `AgentTaskAssigned` schemas in the schema registry, following the pattern used for `MemoryStored` and `MemoryRevoked`.

### Handler Design — Command Handler Adapter

Because the `AssignTask` handler needs runtime access to `AgentPlatform` and `Agent`, it cannot use the simple `static` method reference pattern of memory handlers. Two options:

**Option A: Dedicated `AssignTaskCommandHandler` class** (recommended)

A new `CommandHandlerFunction` implementation in `o.e.a.agentic.runtime`:

```java
public class AssignTaskCommandHandler<S extends AggregateState>
        implements CommandHandlerFunction<S, AssignTaskCommand, DomainEvent> {

    private final AgentPlatform agentPlatform;
    private final Agent agent;
    private final AgenticAggregate<S> aggregate;

    @Override
    public Stream<DomainEvent> apply(AssignTaskCommand command, S state) {
        // 1. Build bindings from command metadata
        // 2. Create AgentProcess
        // 3. Emit AgentTaskAssignedEvent with process ID
    }

    @Override
    public boolean isCreate() {
        return false;
    }
}
```

**Option B: Lambda registered inline in the factory**

Less clean, but avoids a new class. Not recommended due to complexity of captured variables.

### Dependencies

- Phase 1 (RequestingParty types for the command and event fields)
- Phase 2 (AssignTaskCommand and AgentTaskAssignedEvent for handler signatures)

---

## Phase 4: State Interface for Task Tracking (api module)

### What

Extend the state contracts to support tracking assigned tasks, so the event-sourcing handler for `AgentTaskAssignedEvent` can update the aggregate state.

### Module

`main/api` — package `org.elasticsoftware.akces.aggregate`

### New Files

| File | Description |
|------|-------------|
| `AssignedTask.java` | Record representing an assigned task: `String agentProcessId`, `String taskDescription`, `RequestingParty requestingParty`, `Map<String, String> taskMetadata`, `Instant assignedAt` |
| `TaskAwareState.java` | Interface for states that track assigned tasks: `List<AssignedTask> getAssignedTasks()`, `TaskAwareState withAssignedTask(AssignedTask task)`, `TaskAwareState withoutAssignedTask(String agentProcessId)` |

### Design Decisions

**Separate interface vs. extending `MemoryAwareState`:**

A separate `TaskAwareState` interface is preferred because:
- Not all agentic aggregates need to track both memories and assigned tasks
- It follows the Interface Segregation Principle
- States can implement both `MemoryAwareState` and `TaskAwareState` when needed
- The framework can check `instanceof TaskAwareState` independently

**Relationship to `MemoryAwareState`:**

The `TaskAwareState` pattern mirrors `MemoryAwareState`:
- Immutable update methods (`withAssignedTask`, `withoutAssignedTask`)
- The framework's built-in handler checks for the interface and fails with `IllegalStateException` if not implemented

### Event-Sourcing Handler

The built-in event-sourcing handler in `KafkaAgenticAggregateRuntime`:

```java
public static AggregateState onAgentTaskAssigned(AgentTaskAssignedEvent event, AggregateState state) {
    if (!(state instanceof TaskAwareState tas)) {
        throw new IllegalStateException(
            "Aggregate state " + state.getClass().getName()
                + " does not implement TaskAwareState");
    }
    AssignedTask task = new AssignedTask(
        event.agentProcessId(),
        event.taskDescription(),
        event.requestingParty(),
        event.taskMetadata(),
        event.assignedAt());
    return (AggregateState) tas.withAssignedTask(task);
}
```

---

## Phase 5: Tests

### What

Add comprehensive tests for all new components across both modules.

### Module

`main/api` (for Phase 1 & 4 types) and `main/agentic` (for Phase 2 & 3 handlers)

### Test Categories

#### Unit Tests (api module)

| Test Class | What is Tested |
|------------|----------------|
| `RequestingPartySerializationTest` | Jackson serialization/deserialization of `AgentRequestingParty` and `HumanRequestingParty`, including polymorphic type handling |
| `AssignedTaskTest` | Record equality, construction, immutability |
| `TaskAwareStateTest` | Default behavior of `TaskAwareState` methods with a test implementation |

#### Unit Tests (agentic module)

| Test Class | What is Tested |
|------------|----------------|
| `AssignTaskCommandTest` | Command construction, aggregate ID extraction, validation annotations |
| `AgentTaskAssignedEventTest` | Event construction, aggregate ID extraction |
| `AssignTaskCommandHandlerTest` | Handler creates AgentProcess, emits correct event with process ID, handles errors |
| `AgentTaskAssignedEventSourcingTest` | Event-sourcing handler correctly updates TaskAwareState |

#### Integration Tests (agentic module)

| Test Class | What is Tested |
|------------|----------------|
| `AssignTaskIntegrationTest` | Full flow: send AssignTaskCommand → handler creates AgentProcess → AgentTaskAssignedEvent emitted → state updated with assigned task |

### Test Strategy

- **Mock `AgentPlatform` and `AgentProcess`** for unit tests of the command handler — verify `getId()` is called and the returned ID is used in the event
- **Use existing test infrastructure** (Mockito, AssertJ, TestNG/JUnit 5) — no new test dependencies
- **Follow existing test patterns** from `AgentProcessResultTranslatorTest` and other agentic module tests

---

## Summary of All New and Changed Files

### New Files

| Module | Package | File | Phase |
|--------|---------|------|-------|
| api | `o.e.a.aggregate` | `RequestingParty.java` | 1 |
| api | `o.e.a.aggregate` | `AgentRequestingParty.java` | 1 |
| api | `o.e.a.aggregate` | `HumanRequestingParty.java` | 1 |
| api | `o.e.a.aggregate` | `AssignedTask.java` | 4 |
| api | `o.e.a.aggregate` | `TaskAwareState.java` | 4 |
| agentic | `o.e.a.agentic.commands` | `AssignTaskCommand.java` | 2 |
| agentic | `o.e.a.agentic.events` | `AgentTaskAssignedEvent.java` | 2 |
| agentic | `o.e.a.agentic.runtime` | `AssignTaskCommandHandler.java` | 3 |

### Changed Files

| Module | File | Phase | Change |
|--------|------|-------|--------|
| agentic | `AgenticAggregateRuntime.java` | 2 | Add `ASSIGN_TASK_COMMAND_TYPE` and `AGENT_TASK_ASSIGNED_TYPE` constants |
| agentic | `KafkaAgenticAggregateRuntime.java` | 3 | Add `onAgentTaskAssigned` and `handleTaskEvent` static methods |
| agentic | `AgenticAggregateRuntimeFactory.java` | 3 | Register built-in command handler and event-sourcing handler |
| agentic | `AkcesAgenticAggregateController.java` | 3 | Register schemas for `AssignTask` and `AgentTaskAssigned` |

### Test Files

| Module | Package | File | Phase |
|--------|---------|------|-------|
| api | test | `RequestingPartySerializationTest.java` | 1 |
| api | test | `AssignedTaskTest.java` | 4 |
| api | test | `TaskAwareStateTest.java` | 4 |
| agentic | test | `AssignTaskCommandTest.java` | 2 |
| agentic | test | `AgentTaskAssignedEventTest.java` | 2 |
| agentic | test | `AssignTaskCommandHandlerTest.java` | 3 |
| agentic | test | `AgentTaskAssignedEventSourcingTest.java` | 3 |
| agentic | test | `AssignTaskIntegrationTest.java` | 5 |

---

## Open Questions

1. **Should `AssignTaskCommand` trigger the actual Embabel `AgentProcess` creation, or should it only record the assignment and defer process creation to a later stage?** The current design creates the process immediately and captures the ID. An alternative is to only emit the event and defer process creation to when the task is actually started (e.g., via a separate `StartTask` command).

2. **Should there be a `TaskCompleted` / `TaskFailed` event?** The current scope only covers assignment. Task completion tracking would naturally follow and could be planned as a separate feature.

3. **Should `taskMetadata` be `Map<String, String>` or `Map<String, Object>`?** `String` values are simpler and schema-safe. `Object` values are more flexible but require careful serialization handling.

4. **Relationship to `AgentTask` from the incremental tick plan:** The `AssignedTask` record in this plan represents the *persisted* task assignment in aggregate state. The `AgentTask` record from the [agenttasks plan](../agenttasks.md) represents an *in-memory* reference to an active process. These are complementary — `AssignedTask` is the source of truth, `AgentTask` is the runtime handle.

---

## Phase Dependencies

```
Phase 1 (RequestingParty)
    ↓
Phase 2 (Command & Event)  ←  Phase 4 (State Interfaces)
    ↓                              ↓
Phase 3 (Handlers & Registration)
    ↓
Phase 5 (Tests)
```

Phases 1 and 4 can be implemented in parallel since they are both in the `api` module and have no mutual dependencies. Phases 2 and 3 depend on Phase 1. Phase 3 also depends on Phase 4. Phase 5 depends on all other phases.
