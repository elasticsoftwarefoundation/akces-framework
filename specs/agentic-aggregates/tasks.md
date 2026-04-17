# Agentic Aggregates — Tasks

> **Status:** All tasks complete (migrated brownfield)
> **Module:** `main/agentic`

---

## Phase 1: Commands & Events

- [x] **TASK-AA-01** Define `AssignTaskCommand` record with `@CommandInfo(type="AssignTask", version=1)`, fields: `agenticAggregateId` (`@AggregateIdentifier`), `taskDescription`, `requestingParty` (`RequestingParty`), `taskMetadata` (nullable `Map<String,String>`)
  - File: `commands/AssignTaskCommand.java`

- [x] **TASK-AA-02** Define `AgentTaskAssignedEvent` record with `@DomainEventInfo(type="AgentTaskAssigned", version=1)`, fields: `agenticAggregateId`, `agentProcessId`, `taskDescription`, `requestingParty`, `taskMetadata`, `assignedAt` (`Instant`)
  - File: `events/AgentTaskAssignedEvent.java`

- [x] **TASK-AA-03** Define `AgentTaskFinishedEvent` record with `@DomainEventInfo(type="AgentTaskFinished", version=1)`, fields: `agenticAggregateId`, `agentProcessId`, `status` (`AgentProcessStatusCode`), `finishedAt`
  - File: `events/AgentTaskFinishedEvent.java`

- [x] **TASK-AA-04** Define `MemoryStoredEvent` record with `@DomainEventInfo(type="MemoryStored", version=1)`, fields: `agenticAggregateId`, `memoryId`, `subject`, `fact`, `citations`, `reason`, `storedAt`
  - File: `events/MemoryStoredEvent.java`

- [x] **TASK-AA-05** Define `MemoryRevokedEvent` record with `@DomainEventInfo(type="MemoryRevoked", version=1)`, fields: `agenticAggregateId`, `memoryId`, `reason`, `revokedAt`
  - File: `events/MemoryRevokedEvent.java`

- [x] **TASK-AA-06** Define `MemoryDistillationStartedEvent` record with `@DomainEventInfo(type="MemoryDistillationStarted", version=1)`, fields: `agenticAggregateId`, `agentProcessId`, `startedAt`
  - File: `events/MemoryDistillationStartedEvent.java`

- [x] **TASK-AA-07** Define `MemoryDistillationFinishedEvent` record with `@DomainEventInfo(type="MemoryDistillationFinished", version=1)`, fields: `agenticAggregateId`, `agentProcessId`, `finishedAt`
  - File: `events/MemoryDistillationFinishedEvent.java`

- [x] **TASK-AA-08** Define `MemoryDistillationFailedEvent` record with `@DomainEventInfo(type="MemoryDistillationFailed", version=1)`, fields: `agenticAggregateId`, `agentProcessId`, `failedAt`
  - File: `events/MemoryDistillationFailedEvent.java`

- [x] **TASK-AA-09** Define `AgenticAggregateRuntime` interface extending `AggregateRuntime` with: 8 built-in `DomainEventType` constants + 1 `CommandType` constant, `getAgentPlatform()`, `getMemories(AggregateStateRecord)`, `resumeNextAgentTask()`, `hasActiveAgentTasks()`, `initializeState()`
  - File: `AgenticAggregateRuntime.java`

---

## Phase 2: Runtime

- [x] **TASK-AA-10** Implement `KafkaAgenticAggregateRuntime` wrapping `KafkaAggregateRuntime` delegate with: `getMemories()` (materialise state → `MemoryAwareState`), `initializeState()` (auto-create via `getCreateDomainEvent()`), all `AggregateRuntime` delegation methods
  - File: `runtime/KafkaAgenticAggregateRuntime.java`

- [x] **TASK-AA-11** Implement 7 built-in event-sourcing handlers as static methods in `KafkaAgenticAggregateRuntime`: `onMemoryStored`, `onMemoryRevoked`, `onAgentTaskAssigned`, `onAgentTaskFinished`, `onMemoryDistillationStarted`, `onMemoryDistillationFinished`, `onMemoryDistillationFailed`, plus `handleBuiltInEvent()` dispatcher
  - File: `runtime/KafkaAgenticAggregateRuntime.java`

- [x] **TASK-AA-12** Implement `resumeNextAgentTask()` with: round-robin selection across unified `AgentProcessAware` list, orphan detection, `resumeAssignedTask()`, `resumeMemoryDistillation()`, and `hasActiveAgentTasks()` fast-path
  - File: `runtime/KafkaAgenticAggregateRuntime.java`

- [x] **TASK-AA-13** Implement memory distillation lifecycle in `KafkaAgenticAggregateRuntime`: `startMemoryDistillation()`, `collectDistillationResult()`, `translateDistillationResult()` with net capacity enforcement, `resolveMemoryDistillerAgent()`
  - File: `runtime/KafkaAgenticAggregateRuntime.java`

- [x] **TASK-AA-14** Implement `AssignTaskCommandHandlerFunction` with: blackboard population (command, state, memories, aggregate services, condition flags), agent resolution via `resolveAgentByName()`, `AgentProcess` creation, `AgentTaskAssignedEvent` emission
  - File: `runtime/AssignTaskCommandHandlerFunction.java`

- [x] **TASK-AA-15** Implement `AgenticCommandHandlerFunctionAdapter<S,C,E>` with: blackboard population, agent resolution, `AgentProcess` creation, `isCreate()=false`, `resolveAgentByName()` static method (exact → suffix → DefaultAgent fallback)
  - File: `runtime/AgenticCommandHandlerFunctionAdapter.java`

- [x] **TASK-AA-16** Implement `AgenticEventHandlerFunctionAdapter<S,InputEvent,E>` with: blackboard population (event instead of command, `isExternalEvent=true`), agent resolution, `AgentProcess` creation, `isCreate()=false`
  - File: `runtime/AgenticEventHandlerFunctionAdapter.java`

- [x] **TASK-AA-17** Implement `AgentProcessResultTranslator` with: cursor-based event collection (`_akces_processedIndex` int[1] array on blackboard), `ErrorEvent` filtering against registered types, `WARN` logging for undeclared error events
  - File: `runtime/AgentProcessResultTranslator.java`

- [x] **TASK-AA-18** Implement `AgentProcessSingleTickRunner` utility: `tick()` calls `agentProcess.tick()` then `AgentProcessResultTranslator.collectEvents()`
  - File: `runtime/AgentProcessSingleTickRunner.java`

- [x] **TASK-AA-19** Implement `AgenticAggregatePartition` with: hard-assign partition 0, state init (auto-create if no state), command/event processing loop, idle-poll `resumeNextAgentTask()`, subscribe to all partitions of external topics, transactional Kafka writes
  - File: `runtime/AgenticAggregatePartition.java`

- [x] **TASK-AA-20** Implement `AkcesAgenticAggregateController` with: schema registration/validation, Akces-Control topic interaction, Kafka topic creation, partition lifecycle management (start/shutdown)
  - File: `runtime/AkcesAgenticAggregateController.java`

- [x] **TASK-AA-21** Implement `AgenticAggregateServiceApplication` Spring Boot entry point with: Kafka bean wiring (no auto-config), Jackson customiser, `AgenticAggregateBeanFactoryPostProcessor` registration, Anthropic LLM service beans, `akces-agenticservice.properties` loading
  - File: `runtime/AgenticAggregateServiceApplication.java`

---

## Phase 3: Embabel Integration

- [x] **TASK-AA-22** Implement `DefaultAgent` with `@Agent(name="Default", planner=UTILITY)`, `AGENT_NAME` constant
  - File: `embabel/DefaultAgent.java`

- [x] **TASK-AA-23** Implement `MemoryDistillerAgent` with `@Agent(name="MemoryDistiller", planner=GOAP)`, `@Action`+`@AchievesGoal` on `distillMemories()` method, LLM prompt construction from `MemoryDistillationInput`, JSON serialisation of blackboard objects/history
  - File: `embabel/MemoryDistillerAgent.java`

- [x] **TASK-AA-24** Define `MemoryDistillationInput` record with fields: `agentTask`, `history` (`List<ActionInvocation>`), `blackboardObjects` (`List<Object>`), `existingMemories`, `maxTotalMemories`, `maxMemoriesAdded`
  - File: `embabel/MemoryDistillationInput.java`

- [x] **TASK-AA-25** Define `MemoryDistillationResult` record with fields: `stored` (`List<MemoryStoredEvent>`), `revoked` (`List<MemoryRevokedEvent>`)
  - File: `embabel/MemoryDistillationResult.java`

- [x] **TASK-AA-26** Implement `Jackson3ChatClientLlmOperations` as `@Primary @Service` extending `ChatClientLlmOperations`, overriding `createOutputConverter()` to use `Jackson3OutputConverter` wrapped in SuppressThinking → WithExample → ExceptionWrapping chain
  - File: `embabel/Jackson3ChatClientLlmOperations.java`

- [x] **TASK-AA-27** Implement `Jackson3OutputConverter<T>` implementing `StructuredOutputConverter<T>` with: victools 5.x schema generation (Jackson 3), Jackson 2 lenient deserialization, markdown unwrapping, malformed escaped-quote fixing, field filter support
  - File: `embabel/Jackson3OutputConverter.java`

---

## Phase 4: Bean Wiring

- [x] **TASK-AA-28** Implement `AgenticAggregateBeanFactoryPostProcessor` implementing `BeanFactoryPostProcessor`, `BeanFactoryInitializationAotProcessor`, `BeanRegistrationExcludeFilter` with: `@AgenticAggregateInfo` scanning, handler method processing, `AgenticAggregateRuntimeFactory` registration, `AkcesAgenticAggregateController` registration
  - File: `beans/AgenticAggregateBeanFactoryPostProcessor.java`

- [x] **TASK-AA-29** Implement `AgenticAggregateRuntimeFactory` as `FactoryBean<AgenticAggregateRuntime>` with: `KafkaAggregateRuntime` delegate creation, `KafkaAgenticAggregateRuntime` wrapping, built-in type registration (8 events + 1 command), `AgenticCommandHandlerFunctionAdapter`/`AgenticEventHandlerFunctionAdapter` creation, `AssignTaskCommandHandlerFunction` creation, `AgentPlatform` resolution
  - File: `beans/AgenticAggregateRuntimeFactory.java`

---

## Phase 5: Tests

- [x] **TASK-AA-30** `Jackson3OutputConverterTest` — JSON schema generation, lenient parsing, markdown unwrapping, field filtering
  - File: `test/.../embabel/Jackson3OutputConverterTest.java`

- [x] **TASK-AA-31** `MemoryDistillationResultTest` — result record serialization/deserialization
  - File: `test/.../embabel/MemoryDistillationResultTest.java`

- [x] **TASK-AA-32** TradingAdvisor test fixtures — `TradingAdvisorAggregate`, `TradingAdvisorState`, `TradingAdvisorAgent`, commands/events, `TradingAdvisorConfiguration`, `TradingAdvisorTestUtils`
  - Files: `test/.../testfixtures/TradingAdvisor*.java`, `AnalyzeMarketCommand.java`, `MarketAnalysisCompletedEvent.java`, `TradingAdvisorCreatedEvent.java`

- [x] **TASK-AA-33** `TradingAdvisorIntegrationTest` — end-to-end with Testcontainers Kafka, `@EnabledIfEnvironmentVariable` for LLM tests
  - File: `test/.../testfixtures/TradingAdvisorIntegrationTest.java`

- [x] **TASK-AA-34** `AgentProcessResultTranslatorTest` — cursor tracking, ErrorEvent filtering, unknown event type warning
  - File: `test/.../runtime/AgentProcessResultTranslatorTest.java`

- [x] **TASK-AA-35** `AgentTaskAssignedEventSchemaTest` — JSON schema validation for AgentTaskAssignedEvent
  - File: `test/.../runtime/AgentTaskAssignedEventSchemaTest.java`

- [x] **TASK-AA-36** `ResumeNextAgentTaskTest` — round-robin scheduling, orphan process recovery, distillation resume
  - File: `test/.../runtime/ResumeNextAgentTaskTest.java`

- [x] **TASK-AA-37** `MemoryDistillationTest` — distillation lifecycle, capacity enforcement, MemoryDistillerAgent integration
  - File: `test/.../runtime/MemoryDistillationTest.java`

- [x] **TASK-AA-38** `AssignTaskCommandHandlerTest` — blackboard population, agent resolution, AgentProcess creation
  - File: `test/.../runtime/AssignTaskCommandHandlerTest.java`

- [x] **TASK-AA-39** `AgenticCommandHandlerFunctionAdapterTest` — adapter delegation, isCreate=false, agent resolution chain
  - File: `test/.../runtime/AgenticCommandHandlerFunctionAdapterTest.java`

- [x] **TASK-AA-40** `AssignTaskTypeConstantsTest` — built-in type constant values and registration
  - File: `test/.../runtime/AssignTaskTypeConstantsTest.java`

- [x] **TASK-AA-41** `KafkaAgenticAggregateRuntimeTest` — runtime delegation, built-in handler dispatch, getMemories()
  - File: `test/.../runtime/KafkaAgenticAggregateRuntimeTest.java`

- [x] **TASK-AA-42** `MemorySlidingWindowTest` — sliding window eviction when maxTotalMemories exceeded
  - File: `test/.../runtime/MemorySlidingWindowTest.java`

- [x] **TASK-AA-43** `AgenticAggregateAutoCreateTest` — auto-create on first boot, initializeState()
  - File: `test/.../runtime/AgenticAggregateAutoCreateTest.java`

- [x] **TASK-AA-44** `AgentProcessSingleTickRunnerTest` — single tick execution, event collection
  - File: `test/.../runtime/AgentProcessSingleTickRunnerTest.java`

- [x] **TASK-AA-45** `TaskEventSourcingTest` — AgentTaskAssigned/Finished event sourcing handlers
  - File: `test/.../runtime/TaskEventSourcingTest.java`

- [x] **TASK-AA-46** `MemoryEventSourcingTest` — MemoryStored/Revoked event sourcing handlers
  - File: `test/.../runtime/MemoryEventSourcingTest.java`

- [x] **TASK-AA-47** `AgenticEventHandlerFunctionAdapterTest` — adapter delegation for external events
  - File: `test/.../runtime/AgenticEventHandlerFunctionAdapterTest.java`

---

## Gaps & Future Work

| ID | Gap | Priority | Notes |
|---|---|---|---|
| GAP-01 | Multi-partition agentic aggregates | Low | Current singleton model is intentional; would require distributed agent state |
| GAP-02 | EventBridge handler support | Medium | `AgenticAggregateBeanFactoryPostProcessor` explicitly disallows `@EventBridgeHandler`; may be needed for cross-aggregate workflows |
| GAP-03 | Agent process persistence across restarts | Medium | Currently orphaned processes emit failure events; could persist process state to Kafka for true resume |
| GAP-04 | Configurable LLM provider for distillation | Low | `MemoryDistillerAgent` uses `withDefaultLlm()`; may want per-aggregate LLM selection |
| GAP-05 | Metrics and observability | Medium | `ObservationRegistry.NOOP` is registered; real metrics needed for agent process durations, tick counts, memory distillation latency |
| GAP-06 | Stuck process detection timeout | Low | Stuck detection relies on Embabel's `AgentProcessStatusCode.STUCK`; no configurable timeout |
| GAP-07 | GDPR/PII support for agentic state | Low | Agentic aggregates are never GDPR-keyed; may need PII handling if agent memories contain sensitive data |
| GAP-08 | Jackson 2/3 bridge long-term | Medium | The compatibility layer is a workaround; should be removed when Embabel migrates to Jackson 3 / victools 5.x |
