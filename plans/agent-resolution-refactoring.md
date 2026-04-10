# Agent Resolution Refactoring Plan

## Summary

Refactor `AgenticCommandHandlerFunctionAdapter` and `AgenticEventHandlerFunctionAdapter` to not
inject the `Agent` instance directly. Instead, infer the agent at runtime based on the
`AgenticAggregate` name by searching `agentPlatform.agents()`. If no matching agent is found,
fall back to using `Autonomy` via `agentPlatform.getPlatformServices().autonomy()` to let Embabel
decide on the best solution.

## Motivation

The current approach requires a 1:1 Spring bean naming convention (`{aggregateName}Agent`) and
fails fast at startup if the bean is missing. The new approach:

1. **Decouples** agent registration from the Spring bean lifecycle — agents deployed to the
   `AgentPlatform` at any time will be discovered automatically.
2. **Enables fallback** via Embabel's `Autonomy` when no agent is explicitly named after the
   aggregate, letting the platform choose the best agent/goal combination.
3. **Simplifies** the `AgenticAggregateRuntimeFactory` by removing eager agent resolution.

## Phase 1: Adapter Refactoring

### 1.1 AgenticCommandHandlerFunctionAdapter

- Replace the `Agent agent` constructor parameter and field with `String aggregateName`.
- In `apply()`, resolve the agent lazily:
  1. Search `agentPlatform.agents()` for an `Agent` whose `getName()` matches the aggregate name
     (exact match or `{aggregateName}Agent` suffix match).
  2. If found → use existing `agentPlatform.createAgentProcess(agent, ProcessOptions.DEFAULT, bindings)`
     with the manual tick loop.
  3. If not found → fall back to `agentPlatform.getPlatformServices().autonomy()
     .chooseAndAccomplishGoal(GoalChoiceApprover.Companion.getAPPROVE_ALL(), agentPlatform, bindings)`.
     Extract the `AgentProcess` from the returned `AgentProcessExecution`.
- Collect events from the blackboard identically in both paths.

### 1.2 AgenticEventHandlerFunctionAdapter

- Same changes as 1.1 but for the event handling path.
- The `event` binding (instead of `command`) is placed on the blackboard.

### 1.3 Agent Resolution Helper

- Extract the agent-name-matching logic into a private helper method shared by both adapters
  (or a static utility) to avoid duplication. The method signature:
  ```java
  Optional<Agent> resolveAgentByName(AgentPlatform platform, String aggregateName)
  ```
  Matching rules:
  1. Exact match: `agent.getName().equals(aggregateName)`
  2. Agent-suffix match: `agent.getName().equals(aggregateName + "Agent")`

## Phase 2: Factory Cleanup

### 2.1 AgenticAggregateRuntimeFactory

- Remove the `resolveAgent(String aggregateName)` method.
- Remove the `Agent agent` local variable and the lazy resolution logic in `createRuntime()`.
- Update `processAgentHandledCommand()` and `processAgentHandledEvent()` to pass the aggregate
  name (String) instead of an `Agent` to the adapter constructors.

## Phase 3: Testing

### 3.1 Unit Tests

- Add tests for `AgenticCommandHandlerFunctionAdapter`:
  - Agent found by exact name match → process created via `createAgentProcess`.
  - Agent found by suffix match → process created via `createAgentProcess`.
  - No agent found → Autonomy fallback invoked.
- Add tests for `AgenticEventHandlerFunctionAdapter`:
  - Same three scenarios.
- Verify that the Autonomy fallback correctly collects events from the resulting blackboard.

### 3.2 Existing Test Validation

- Run the full agentic module test suite (`mvn test -pl main/agentic`) to ensure no regressions.

## Dependencies

- Embabel `embabel-agent-api` 0.3.5 — `Autonomy`, `GoalChoiceApprover`, `AgentProcessExecution`.
- No new library dependencies required.

## Risk Assessment

- **Low risk**: The matching logic is straightforward and well-tested.
- **Medium risk**: The `Autonomy.chooseAndAccomplishGoal()` fallback runs the process to completion
  synchronously. If Embabel's internal timeout/budget is not aligned with Kafka consumer polling
  intervals, the consumer may be considered dead. Mitigation: use `ProcessOptions` with an
  appropriate budget in a future iteration.
