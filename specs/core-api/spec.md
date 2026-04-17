# Core API Module — Feature Specification

> **Status**: Migrated
> **Module**: `main/api/` (`akces-api`)
> **Version**: 0.12.1-SNAPSHOT
> **Package**: `org.elasticsoftware.akces`

## Overview

The Core API module defines the public contract of the Akces CQRS/Event Sourcing framework. It provides all interfaces, annotations, base types, and functional handler signatures that application developers use to build aggregates, commands, events, query models, database models, process managers, and agentic aggregates. This module has **zero framework-specific dependencies** — it depends only on Jackson annotations, Jakarta Validation/Inject, SLF4J, and Spring Context (for `@Component` meta-annotation). Every other Akces module depends on this one; nothing in this module depends on any other Akces module.

## User Scenarios

### US-1: Define an Aggregate with Commands and Events

**As a** domain developer,
**I want to** define an aggregate class that handles commands and emits domain events,
**so that** I can model a consistency boundary with event sourcing.

**Acceptance Criteria:**
- Implement `Aggregate<S extends AggregateState>` with a typed state class
- Annotate the class with `@AggregateInfo(value, stateClass, ...)` to register it as a Spring component
- Define state as a Java record implementing `AggregateState` with `getAggregateId()` and optional `getIndexKey()`
- Annotate state with `@AggregateStateInfo(type, version)` for schema versioning
- Define commands as records implementing `Command` with `@CommandInfo(type, version)` and `@AggregateIdentifier`
- Define events as records implementing `DomainEvent` with `@DomainEventInfo(type, version)` and `@AggregateIdentifier`
- Define error events implementing `ErrorEvent extends DomainEvent`
- Write `@CommandHandler(create, produces, errors)` methods returning `Stream<DomainEvent>`
- Write `@EventSourcingHandler(create)` methods that return new state instances
- Write `@EventBridgeHandler` methods that receive events and send commands to other aggregates via `CommandBus`
- Write `@UpcastingHandler` methods to convert older event/command versions to newer ones
- Access `CommandBus` via the `Aggregate.getCommandBus()` default method (backed by `CommandBusHolder` ThreadLocal)

### US-2: Define a Query Model with Event Handlers

**As a** read-side developer,
**I want to** define query models and database models that project domain events into read-optimized state,
**so that** I can serve queries without loading aggregate state.

**Acceptance Criteria:**
- Implement `QueryModel<S extends QueryModelState>` with `getStateClass()` and `getIndexName()`
- Annotate with `@QueryModelInfo(value, version, indexName)` — registered as Spring component
- Define query model state as a record implementing `QueryModelState` with `getIndexKey()`
- Annotate state with `@QueryModelStateInfo(type, version)`
- Write `@QueryModelEventHandler(create)` methods that return new `QueryModelState` instances
- Implement `DatabaseModel` for JDBC-backed projections with offset tracking, transactions
- Annotate with `@DatabaseModelInfo(value, version, schemaName)` — registered as Spring component
- Write `@DatabaseModelEventHandler` methods (void, side-effecting)
- Implement `Reflector<R>` for event-driven integrations with success/failure callbacks
- Annotate with `@ReflectorInfo(value, version, schemaName, maxRetries, retryBackoffBaseMs)`
- Write `@ReflectorEventHandler` methods returning a result `R`

### US-3: Define a Process Manager for Cross-Aggregate Coordination

**As a** workflow developer,
**I want to** define process managers that coordinate multi-step workflows across aggregates,
**so that** I can implement sagas and long-running processes.

**Acceptance Criteria:**
- Implement `ProcessManager<S extends AggregateState, P extends AkcesProcess>` (extends `Aggregate<S>`)
- Define process state implementing `ProcessManagerState<P>` with `getAkcesProcess(processId)` and `hasAkcesProcess(processId)`
- Define process instances implementing `AkcesProcess` with `getProcessId()`
- Use `@EventHandler(create, produces, errors)` methods to react to external events and emit events
- Catch `UnknownAkcesProcessException` when looking up non-existent processes
- Access `CommandBus` to send commands to other aggregates within event handlers

### US-4: Annotate PII Fields for GDPR Compliance

**As a** developer handling personal data,
**I want to** mark PII fields with `@PIIData`,
**so that** the framework transparently encrypts/decrypts those fields at rest.

**Acceptance Criteria:**
- Annotate record fields, methods, or parameters with `@PIIData`
- `@PIIData` is a Jackson annotation (`@JacksonAnnotation`) — framework serializers detect it
- Aggregates with PII set `generateGDPRKeyOnCreate = true` on `@AggregateInfo`
- The annotation is purely declarative at the API level; encryption is handled by the runtime/shared modules

### US-5: Define an Agentic Aggregate with AI Agent Integration

**As an** AI-integrated developer,
**I want to** define agentic aggregates that delegate command/event processing to AI agents,
**so that** I can build AI-assisted event-sourced systems.

**Acceptance Criteria:**
- Implement `AgenticAggregate<S>` (extends `Aggregate<S>`) with `getCreateDomainEvent()`
- Annotate with `@AgenticAggregateInfo(value, stateClass, description, maxTotalMemories, maxMemoriesAdded, agentHandledCommands, agentHandledEvents, agentProducedErrors)`
- Declare `agentHandledCommands` — commands delegated to AI agent (no `@CommandHandler` needed)
- Declare `agentHandledEvents` — external events delegated to AI agent (no `@EventHandler` needed)
- Declare `agentProducedErrors` — error events the AI agent may emit
- Implement state with `MemoryAwareState` for agent memory management:
  - `getMemories()`, `withMemory(AgenticAggregateMemory)`, `withoutMemory(memoryId)`
  - `getMemoryDistillations()`, `withMemoryDistillation(MemoryDistillation)`, `withoutMemoryDistillation(agentProcessId)`
- Implement state with `TaskAwareState` for agent task tracking:
  - `getAssignedTasks()`, `withAssignedTask(AssignedTask)`, `withoutAssignedTask(agentProcessId)`
- Use `RequestingParty` (sealed: `AgentRequestingParty`, `HumanRequestingParty`) with Jackson polymorphic serialization
- Use `AgentProcessAware` interface for uniform tick-logic over tasks and distillations
- Use `AgenticAggregateMemory` record for immutable memory entries (memoryId, subject, fact, citations, reason, storedAt)
- Use `MemoryDistillation` record for in-progress distillation tracking
- Use `AssignedTask` record for assigned work items with metadata and requesting party

## Requirements

### R1: Zero Framework Dependencies
The API module must not depend on Kafka, RocksDB, Confluent Schema Registry, or any other Akces runtime module. Allowed dependencies: Jackson annotations, Jakarta Validation/Inject/Annotation, SLF4J, Spring Context.

### R2: All Types Must Be Versionable
Every annotation that registers a domain type (`@CommandInfo`, `@DomainEventInfo`, `@AggregateStateInfo`, `@QueryModelInfo`, `@QueryModelStateInfo`, `@DatabaseModelInfo`) must include a `version` field with default `1`.

### R3: Aggregate Identity via `@AggregateIdentifier`
Commands and events must support `@AggregateIdentifier` on fields/methods/annotations. Both `Command.getAggregateId()` and `DomainEvent.getAggregateId()` must be `@JsonIgnore @Nonnull`.

### R4: Immutable State Contracts
`AggregateState.getAggregateId()` and `AggregateState.getIndexKey()` must be `@JsonIgnore @Nonnull`. `QueryModelState.getIndexKey()` must be `@JsonIgnore @Nonnull`. All mutation methods on `MemoryAwareState` and `TaskAwareState` must return new instances.

### R5: Spring Component Registration
`@AggregateInfo`, `@AgenticAggregateInfo`, `@QueryModelInfo`, `@DatabaseModelInfo`, and `@ReflectorInfo` must be meta-annotated with `@Component` and support `@AliasFor(annotation = Component.class)` on `value()`.

### R6: Functional Handler Interfaces
All handler function interfaces must be `@FunctionalInterface`. Default method implementations must throw `UnsupportedOperationException` with descriptive messages, forcing runtime implementations to override them.

### R7: Command Bus Thread-Local Pattern
`CommandBusHolder` must use a `ThreadLocal<CommandBus>` to provide the command bus to aggregate instances during handler execution. `Aggregate.getCommandBus()` delegates to `CommandBusHolder.getCommandBus(Class)`.

### R8: Sealed Type Hierarchies
- `ProtocolRecordType<T>` is sealed, permitting `SchemaType` and `AggregateStateType`
- `SchemaType<T>` is sealed, permitting `DomainEventType` and `CommandType`
- `RequestingParty` is sealed, permitting `AgentRequestingParty` and `HumanRequestingParty`

### R9: Jackson Polymorphic Serialization for RequestingParty
`RequestingParty` must use `@JsonTypeInfo(use = Id.NAME, property = "type")` with `@JsonSubTypes` mapping `"agent"` → `AgentRequestingParty` and `"human"` → `HumanRequestingParty`.

### R10: Upcasting Support
`UpcastingHandlerFunction<T, R, TR, RR>` must support converting between any `ProtocolRecordType` variants. `@UpcastingHandler` marks methods on aggregates for schema evolution.

## CQRS Design

### Commands (marker interfaces)
| Type | Interface | Annotation |
|------|-----------|------------|
| Command | `Command` | `@CommandInfo(type, version, description)` |

### Events (marker interfaces)
| Type | Interface | Annotation |
|------|-----------|------------|
| Domain Event | `DomainEvent` | `@DomainEventInfo(type, version, description)` |
| Error Event | `ErrorEvent extends DomainEvent` | (uses `@DomainEventInfo`) |

### State Types
| Type | Interface/Record | Annotation |
|------|-----------------|------------|
| Aggregate State | `AggregateState` | `@AggregateStateInfo(type, version)` |
| Query Model State | `QueryModelState` | `@QueryModelStateInfo(type, version)` |

### Type Descriptors (records)
| Record | Type Parameter | Purpose |
|--------|---------------|---------|
| `CommandType<C>` | `C extends Command` | Runtime command type metadata (typeName, version, typeClass, create, external, piiData) |
| `DomainEventType<T>` | `T extends DomainEvent` | Runtime event type metadata (typeName, version, typeClass, create, external, error, piiData) |
| `AggregateStateType<C>` | `C extends AggregateState` | Runtime state type metadata (typeName, version, typeClass, generateGDPRKeyOnCreate, indexed, indexName, piiData) |
| `QueryModelStateType<C>` | `C extends QueryModelState` | Runtime query state metadata (typeName, version, typeClass, indexName) |

### Handler Annotations
| Annotation | Target | Key Attributes |
|------------|--------|----------------|
| `@CommandHandler` | METHOD | `create`, `produces`, `errors` |
| `@EventSourcingHandler` | METHOD | `create` |
| `@EventHandler` | METHOD | `create`, `produces`, `errors` |
| `@EventBridgeHandler` | METHOD | (none) |
| `@QueryModelEventHandler` | METHOD | `create` |
| `@DatabaseModelEventHandler` | METHOD | (none) |
| `@ReflectorEventHandler` | METHOD | (none) |
| `@UpcastingHandler` | METHOD | (none) |

### Sealed Type Hierarchies
```
ProtocolRecordType<T>  (sealed)
├── SchemaType<T>  (sealed)
│   ├── CommandType<C>
│   └── DomainEventType<T>
└── AggregateStateType<C>

RequestingParty  (sealed)
├── AgentRequestingParty(agentId, agentName, role)
└── HumanRequestingParty(userId, role)
```

## Non-Functional Requirements

- **Compilation target**: Java 25
- **No runtime behavior**: This module defines contracts only; all runtime logic is in other modules
- **Backward compatibility**: Changes to interfaces/annotations must not break existing consumers
- **Annotation retention**: All annotations use `RetentionPolicy.RUNTIME` (except `@QueryModelStateInfo` which lacks explicit retention)
