# Incremental Agent Task Processing Plan

## Overview

This plan describes the **incremental tick** support for agentic aggregates — the ability for an `AgentProcess` to advance one `tick()` at a time across multiple Kafka poll loop iterations, rather than running the entire agent process to completion in a single `apply()` call.

This is a follow-up to the [Embabel AgentPlatform Integration Plan](embabel-integration-plan.md), which initially implements a **tick-to-completion** model where `apply()` calls `AgentProcess.tick()` in a loop until the process reaches an end state. The incremental model described here replaces that loop with single-tick-per-invocation semantics.

> **Status**: Deferred — to be iterated on in a later stage, after the base Embabel integration is complete and validated.

---

## Motivation

In the tick-to-completion model, the entire agent process (which may involve multiple LLM calls, MCP tool invocations, and GOAP planning steps) runs synchronously inside a single `apply()` call. This has several drawbacks:

1. **Kafka transaction timeout risk**: The `apply()` call happens inside a Kafka transaction (`producer.beginTransaction()` ... `producer.commitTransaction()`). If the agent process takes longer than `transaction.timeout.ms` (default: 60s), the transaction will be aborted.
2. **Partition thread blocking**: The partition's poll loop thread is blocked for the entire duration of the agent process, preventing it from processing other commands or events.
3. **No intermediate state visibility**: Other components cannot observe the agent's intermediate progress — they only see the final result.

The incremental tick model addresses these by advancing the agent process one step at a time, yielding control back to the poll loop between steps.

---

## `AgentTask` Record

An `AgentTask` represents a running agent process that needs further `tick()` calls. It is tracked in the aggregate state's `activeAgentTasks` list so that the Akces runtime knows which processes need to be advanced on subsequent poll loop iterations.

```java
/**
 * Represents an active agent process that requires further tick() calls to complete.
 * Stored in the agentic aggregate state to enable incremental multi-step processing.
 *
 * @param id unique identifier for this agent task (typically the AgentProcess ID)
 */
public record AgentTask(String id) {}
```

The `activeAgentTasks` property is maintained on the agentic aggregate state (any state implementing `MemoryAwareState` for agentic aggregates). When a new agent process starts, an `AgentTask` is added; when a process reaches an end state, the task is removed. The Akces runtime uses this list to determine whether `proceed()` calls are needed on poll iterations with no incoming Kafka records.

---

## `AgenticProcessHandler` Interface

Both agentic adapters (`AgenticCommandHandlerFunctionAdapter` and `AgenticEventHandlerFunctionAdapter`) will implement a new `AgenticProcessHandler` interface that defines the contract for advancing active agent processes beyond the initial `apply()` call:

```java
public interface AgenticProcessHandler<S extends AggregateState & MemoryAwareState, E extends DomainEvent> {

    /**
     * Continue an active AgentProcess by calling tick() and collecting resulting events.
     * Called by the Akces runtime when there is an active process that needs to advance,
     * independent of whether new Kafka records have arrived.
     *
     * @param agentTask the active agent task to advance
     * @param state the current aggregate state
     * @return stream of domain events produced by the agent process step
     */
    Stream<E> proceed(AgentTask agentTask, S state);
}
```

---

## Changes to Agentic Handler Adapters

### `AgenticCommandHandlerFunctionAdapter`

The adapter will change from `implements CommandHandlerFunction<S, C, E>` to `implements CommandHandlerFunction<S, C, E>, AgenticProcessHandler<S, E>`:

```java
public class AgenticCommandHandlerFunctionAdapter<S extends AggregateState & MemoryAwareState, C extends Command, E extends DomainEvent>
        implements CommandHandlerFunction<S, C, E>, AgenticProcessHandler<S, E> {

    @Override
    public Stream<E> apply(C command, S state) {
        // 1. Set up the Blackboard with command, state, memories, aggregate service records
        // 2. Create AgentProcess via agentPlatform
        // 3. Call agentProcess.tick() ONCE (not in a loop)
        // 4. If process not yet complete → add AgentTask to state's activeAgentTasks
        // 5. Collect DomainEvent instances from the blackboard
        // 6. Return them as Stream<E>
    }

    @Override
    public Stream<E> proceed(AgentTask agentTask, S state) {
        // 1. Look up the active AgentProcess by agentTask.id()
        // 2. Call agentProcess.tick() ONCE
        // 3. Collect DomainEvent instances from the blackboard
        // 4. If process reached end state → remove AgentTask from state's activeAgentTasks
        // 5. Return them as Stream<E>
    }
}
```

### `AgenticEventHandlerFunctionAdapter`

Same changes apply — adds `AgenticProcessHandler<S, E>` and the `proceed()` method:

```java
public class AgenticEventHandlerFunctionAdapter<S extends AggregateState & MemoryAwareState, InputEvent extends DomainEvent, E extends DomainEvent>
        implements EventHandlerFunction<S, InputEvent, E>, AgenticProcessHandler<S, E> {

    @Override
    public Stream<E> apply(InputEvent event, S state) {
        // Single tick, same as command adapter
    }

    @Override
    public Stream<E> proceed(AgentTask agentTask, S state) {
        // Same as command adapter
    }
}
```

---

## `proceedAgentTasks()` — Active Task Advancement

### What

A new method on `KafkaAggregateRuntime` (or exposed through the delegation layer) that iterates over active `AgentTask` instances in the current state and calls `proceed(agentTask, state)` on the appropriate `AgenticProcessHandler` adapter for each one.

### Invocation

Called from `AgenticAggregatePartition` on poll loop iterations where no Kafka records are returned, ensuring active agent processes continue to advance even without new commands or events.

### Flow

```
AgenticAggregatePartition.run():
  1. Poll Kafka for records
  2. If records found → process normally via handleCommand() / handleEvent()
  3. If no records found → call kafkaAggregateRuntime.proceedAgentTasks()
     a. Read current state
     b. For each AgentTask in state.activeAgentTasks():
        i. Find the AgenticProcessHandler adapter that created this task
        ii. Call adapter.proceed(agentTask, state)
        iii. Apply returned DomainEvents via processDomainEvent()
        iv. If agent process reached end state → remove AgentTask from state
```

---

## State Management Considerations

### `activeAgentTasks` on State

The `MemoryAwareState` interface (or a new `AgentTaskAwareState` interface) will need to expose:

- `List<AgentTask> getActiveAgentTasks()` — returns the currently active tasks
- `<S> S withAgentTask(AgentTask task)` — returns a new state with the task added
- `<S> S withoutAgentTask(String taskId)` — returns a new state with the task removed

### AgentProcess Lifecycle

The adapter must maintain a mapping from `AgentTask.id()` to the live `AgentProcess` instance. This is an in-memory mapping (not serialized to state) because `AgentProcess` objects are not serializable. After a restart, active agent tasks in the state will be detected, but the `AgentProcess` instances will need to be recreated from the blackboard context stored in the state.

### Transaction Boundaries

Each `proceed()` call should produce events within its own Kafka transaction, similar to how command processing works. This ensures that:
- Intermediate events are durably stored
- State is updated after each tick
- A failure mid-process doesn't lose prior progress

---

## New Files

| File | Package | Purpose |
|------|---------|---------|
| `AgentTask.java` | `o.e.a.agentic` | Record with `String id` representing an active agent process |
| `AgenticProcessHandler.java` | `o.e.a.agentic.runtime` | Interface with `proceed(AgentTask, state)` for incremental agent process advancement |

---

## Open Questions

1. **Multiple concurrent processes**: Should multiple `AgentTask`s be supported simultaneously per aggregate instance? The initial design assumes yes (a list of active tasks), but the implementation may start with at most one.

2. **Process recovery after restart**: When the partition restarts and finds `activeAgentTasks` in the state, how should it recreate the `AgentProcess` instances? Options:
   - Re-create from scratch using the original command/event context (stored in state)
   - Mark the tasks as failed and emit error events
   - Support serializable process checkpoints (requires Embabel framework support)

3. **Timeout handling**: Should there be a maximum duration for an agent task? If a task has been active for too long (e.g., across many poll iterations without progress), should it be automatically failed?

4. **Priority between new commands and active tasks**: When both new Kafka records and active agent tasks exist, which should be processed first? The current design processes Kafka records first and only calls `proceedAgentTasks()` when no records are returned.

---

## Relationship to Embabel Integration Plan

This plan extends the following sections of the [Embabel AgentPlatform Integration Plan](embabel-integration-plan.md):

- **Agentic Handler Adapters**: The adapters initially implement tick-to-completion in `apply()`. This plan adds the `AgenticProcessHandler` interface and `proceed()` method for incremental processing.
- **Section 4.1 (Agent-Assisted Command Processing)**: The tick-to-completion note references this plan for the future incremental model.
- **Section 4.3 (Event Application Mechanism)**: The future incremental tick support paragraph references this plan.
- **Resolved Design Decision #3**: The synchronous tick-to-completion model is the initial approach; this plan describes the incremental alternative.
