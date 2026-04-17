# Core API Module — Task List

> **Status**: Migrated — all tasks completed
> **Module**: `main/api/` (`akces-api`)

---

## Phase 1: Core Interfaces — Aggregate, Command, DomainEvent, AggregateState

- [x] **1.1** Define `Command` interface in `commands/` with `@JsonIgnore @Nonnull getAggregateId()`
- [x] **1.2** Define `DomainEvent` interface in `events/` with `@JsonIgnore @Nonnull getAggregateId()`
- [x] **1.3** Define `ErrorEvent` interface in `events/` extending `DomainEvent` as a marker for business error outcomes
- [x] **1.4** Define `AggregateState` interface in `aggregate/` with `@JsonIgnore @Nonnull getAggregateId()` and default `getIndexKey()`
- [x] **1.5** Define `Aggregate<S extends AggregateState>` interface with `getName()`, `getStateClass()`, and `getCommandBus()` default methods
- [x] **1.6** Define `CommandBus` interface in `commands/` with `send(Command)` method
- [x] **1.7** Define `CommandBusHolder` class in `commands/` with `ThreadLocal<CommandBus>` and static `getCommandBus(Class)` accessor
- [x] **1.8** Define `AkcesException` abstract class with `aggregateName` and `aggregateId` fields

## Phase 2: Annotation Definitions

- [x] **2.1** Define `@AggregateIdentifier` — `FIELD, METHOD, ANNOTATION_TYPE` target, `RUNTIME` retention
- [x] **2.2** Define `@AggregateInfo` — `TYPE` target, `@Component` meta-annotation, `value`, `stateClass`, `generateGDPRKeyOnCreate`, `indexed`, `indexName`, `description`
- [x] **2.3** Define `@AggregateStateInfo` — `TYPE` target, `type` and `version` attributes
- [x] **2.4** Define `@CommandInfo` — `TYPE` target, `type`, `version`, `description` attributes
- [x] **2.5** Define `@DomainEventInfo` — `TYPE` target, `type`, `version`, `description` attributes
- [x] **2.6** Define `@CommandHandler` — `METHOD` target, `create`, `produces` (DomainEvent[]), `errors` (ErrorEvent[])
- [x] **2.7** Define `@EventSourcingHandler` — `METHOD` target, `create` boolean
- [x] **2.8** Define `@EventHandler` — `METHOD` target, `create`, `produces` (DomainEvent[]), `errors` (ErrorEvent[])
- [x] **2.9** Define `@EventBridgeHandler` — `METHOD` target, no attributes
- [x] **2.10** Define `@UpcastingHandler` — `METHOD` target, no attributes
- [x] **2.11** Define `@PIIData` — `FIELD, METHOD, PARAMETER` target, `@JacksonAnnotation` meta-annotation

## Phase 3: Query/Database Model Interfaces

- [x] **3.1** Define `QueryModelState` interface in `query/` with `@JsonIgnore @Nonnull getIndexKey()`
- [x] **3.2** Define `QueryModel<S extends QueryModelState>` interface with `getName()`, `getStateClass()`, `getIndexName()`
- [x] **3.3** Define `@QueryModelInfo` — `TYPE` target, `@Component`, `value`, `version`, `indexName`
- [x] **3.4** Define `@QueryModelStateInfo` — `type` and `version` attributes
- [x] **3.5** Define `@QueryModelEventHandler` — `METHOD` target, `create` boolean
- [x] **3.6** Define `QueryModelEventHandlerFunction<S, E>` — `@FunctionalInterface`, `apply(E, S) → S`
- [x] **3.7** Define `DatabaseModel` interface with `getOffsets(Set)`, `startTransaction()`, `commitTransaction(Object, Map)`
- [x] **3.8** Define `@DatabaseModelInfo` — `TYPE` target, `@Component`, `value`, `version`, `schemaName`
- [x] **3.9** Define `@DatabaseModelEventHandler` — `METHOD` target, no attributes
- [x] **3.10** Define `DatabaseModelEventHandlerFunction<E>` — `@FunctionalInterface`, `accept(E) → void`
- [x] **3.11** Define `Reflector<R>` interface with default `onSuccess(DomainEvent, R, CommandBus)` and `onFailure(DomainEvent, Exception, CommandBus)`
- [x] **3.12** Define `@ReflectorInfo` — `TYPE` target, `@Component`, `value`, `version`, `schemaName`, `maxRetries`, `retryBackoffBaseMs`
- [x] **3.13** Define `@ReflectorEventHandler` — `METHOD` target, no attributes
- [x] **3.14** Define `ReflectorEventHandlerFunction<E, R>` — `@FunctionalInterface`, `accept(E) → R`

## Phase 4: Process Manager Support

- [x] **4.1** Define `AkcesProcess` interface in `processmanager/` with `@JsonIgnore @Nonnull getProcessId()`
- [x] **4.2** Define `ProcessManagerState<P extends AkcesProcess>` interface extending `AggregateState` with `getAkcesProcess(String)` and `hasAkcesProcess(String)`
- [x] **4.3** Define `ProcessManager<S, P>` interface extending `Aggregate<S>` — marker interface for process managers
- [x] **4.4** Define `UnknownAkcesProcessException` extending `AkcesException` with `processId` field

## Phase 5: Agentic Aggregate Interfaces

- [x] **5.1** Define `AgenticAggregate<S>` interface extending `Aggregate<S>` with `getCreateDomainEvent()` and default `getMemories(S)`
- [x] **5.2** Define `@AgenticAggregateInfo` — `TYPE` target, `@Component`, `value`, `stateClass`, `description`, `maxTotalMemories`, `maxMemoriesAdded`, `agentHandledCommands`, `agentHandledEvents`, `agentProducedErrors`
- [x] **5.3** Define `AgenticAggregateMemory` record — `memoryId`, `subject`, `fact`, `citations`, `reason`, `storedAt`
- [x] **5.4** Define `MemoryDistillation` record implementing `AgentProcessAware` — `agentProcessId`, `startedAt`
- [x] **5.5** Define `MemoryAwareState` interface — `getMemories()`, `withMemory()`, `withoutMemory()`, `getMemoryDistillations()`, `withMemoryDistillation()`, `withoutMemoryDistillation()`
- [x] **5.6** Define `AssignedTask` record implementing `AgentProcessAware` — `agentProcessId`, `taskDescription`, `requestingParty`, `taskMetadata`, `assignedAt`
- [x] **5.7** Define `TaskAwareState` interface — `getAssignedTasks()`, `withAssignedTask()`, `withoutAssignedTask()`
- [x] **5.8** Define `AgentProcessAware` interface — `getAgentProcessId()` for uniform tick-logic
- [x] **5.9** Define `RequestingParty` sealed interface with Jackson `@JsonTypeInfo`/`@JsonSubTypes` — permits `AgentRequestingParty`, `HumanRequestingParty`
- [x] **5.10** Define `AgentRequestingParty` record — `agentId`, `agentName`, `role`
- [x] **5.11** Define `HumanRequestingParty` record — `userId`, `role`

## Phase 6: Type Descriptors and Handler Function Interfaces

- [x] **6.1** Define `ProtocolRecordType<T>` sealed interface — `typeName()`, `version()`, `typeClass()`; permits `SchemaType`, `AggregateStateType`
- [x] **6.2** Define `SchemaType<T>` sealed interface extending `ProtocolRecordType` — `getSchemaPrefix()`, `getSchemaName()`, `relaxExternalValidation()`, `external()`; permits `DomainEventType`, `CommandType`
- [x] **6.3** Define `CommandType<C>` record implementing `SchemaType` — `typeName`, `version`, `typeClass`, `create`, `external`, `piiData`
- [x] **6.4** Define `DomainEventType<T>` record implementing `SchemaType` — `typeName`, `version`, `typeClass`, `create`, `external`, `error`, `piiData`
- [x] **6.5** Define `AggregateStateType<C>` record implementing `ProtocolRecordType` — `typeName`, `version`, `typeClass`, `generateGDPRKeyOnCreate`, `indexed`, `indexName`, `piiData`
- [x] **6.6** Define `QueryModelStateType<C>` record — `typeName`, `version`, `typeClass`, `indexName` (not part of sealed hierarchy)
- [x] **6.7** Define `CommandHandlerFunction<S, C, E>` — `@FunctionalInterface`, `apply(C, S) → Stream<E>`, defaults for `isCreate()`, `getCommandType()`, `getAggregate()`, `getProducedDomainEventTypes()`, `getErrorEventTypes()`, `getCommandBus()`
- [x] **6.8** Define `EventSourcingHandlerFunction<S, E>` — `@FunctionalInterface`, `apply(E, S) → S`, defaults for `getEventType()`, `getAggregate()`, `isCreate()`
- [x] **6.9** Define `EventBridgeHandlerFunction<S, E>` — `@FunctionalInterface`, `apply(E, CommandBus) → void`, defaults for `getEventType()`, `getAggregate()`
- [x] **6.10** Define `EventHandlerFunction<S, InputEvent, E>` — `@FunctionalInterface`, `apply(InputEvent, S) → Stream<E>`, defaults for `getEventType()`, `getAggregate()`, `isCreate()`, `getProducedDomainEventTypes()`, `getErrorEventTypes()`, `getCommandBus()`
- [x] **6.11** Define `UpcastingHandlerFunction<T, R, TR, RR>` — `@FunctionalInterface`, `apply(T) → R`, defaults for `getInputType()`, `getOutputType()`, `getAggregate()`

## Phase 7: Tests

- [x] **7.1** `AgenticAggregateInfoTest` — Verify `@AgenticAggregateInfo` annotation effects: default maxTotalMemories (50), default maxMemoriesAdded (5), value/stateClass/description attributes, `@Component` meta-annotation, absence of GDPR key attribute, agentHandledCommands/Events/Errors defaults and configured values
- [x] **7.2** `AgenticAggregateMemoryTest` — Verify `AgenticAggregateMemory` record construction, equality, and round-trip through `MemoryAwareState` implementation; add/remove memory operations
- [x] **7.3** `MemoryAwareStateTest` — Verify `MemoryAwareState` contract (getMemories, withMemory, withoutMemory) and `AgenticAggregate.getMemories()` default method for both MemoryAwareState and plain state
- [x] **7.4** `RequestingPartySerializationTest` — Verify sealed interface, Jackson annotation configuration (`@JsonTypeInfo`, `@JsonSubTypes`), record construction/equality for both variants, pattern matching with switch
- [x] **7.5** `TaskAwareStateTest` — Verify `TaskAwareState` contract and `AssignedTask` record construction, equality, null metadata support, round-trip through state implementation

---

## Identified Gaps

### Testing Gaps
- ⚠️ **No tests for `ErrorEvent`** — The `ErrorEvent` marker interface is untested (though it's trivial — just `extends DomainEvent`)
- ⚠️ **No tests for `Command` / `DomainEvent` interfaces** — Marker interfaces are untested (per framework guidelines, they'd be tested through consumers in runtime module)
- ⚠️ **No tests for `Aggregate` interface** — `getName()`, `getStateClass()`, `getCommandBus()` default methods are untested at API level
- ⚠️ **No tests for `CommandBusHolder`** — ThreadLocal behavior untested at API level (tested in runtime)
- ⚠️ **No tests for handler function interfaces** — `CommandHandlerFunction`, `EventSourcingHandlerFunction`, etc. — the default `UnsupportedOperationException` methods are untested
- ⚠️ **No tests for type descriptor records** — `CommandType`, `DomainEventType`, `AggregateStateType`, `SchemaType`, `ProtocolRecordType` — untested at API level
- ⚠️ **No tests for process manager types** — `ProcessManager`, `ProcessManagerState`, `AkcesProcess`, `UnknownAkcesProcessException` untested at API level
- ⚠️ **No tests for query-side interfaces** — `QueryModel`, `QueryModelState`, `DatabaseModel`, `Reflector` and their handler functions untested at API level
- ⚠️ **No tests for non-agentic annotations** — `@AggregateInfo`, `@CommandInfo`, `@DomainEventInfo`, `@CommandHandler`, etc. are only tested indirectly via the runtime module
- ⚠️ **No tests for `MemoryDistillation`** — The distillation-related methods on `MemoryAwareState` (`withMemoryDistillation`, `withoutMemoryDistillation`) are implemented in the test fixture but not exercised by dedicated test cases in `MemoryAwareStateTest` or `AgenticAggregateMemoryTest`

### Code Gaps
- ⚠️ **`@QueryModelStateInfo` lacks `@Retention` and `@Target`** — Unlike all other annotations, `QueryModelStateInfo` does not declare retention or target, relying on defaults (`CLASS` retention, all targets)
- ⚠️ **Empty `gdpr/` package** — The `gdpr` package exists but contains no files; `@PIIData` lives in `annotations/`
- ⚠️ **`@CommandHandler.errors()` has no default** — The `errors` attribute on `@CommandHandler` and `@EventHandler` is required (no default `{}`), meaning users must always specify it even when there are no error events

### Documentation Gaps
- ⚠️ **No Javadoc on most interfaces** — Only the agentic interfaces (`AgenticAggregate`, `MemoryAwareState`, `TaskAwareState`, `AssignedTask`, `RequestingParty`, `AgentProcessAware`, `MemoryDistillation`) have Javadoc; core interfaces (`Aggregate`, `Command`, `DomainEvent`, `AggregateState`) lack documentation
