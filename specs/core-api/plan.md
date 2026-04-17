# Core API Module — Implementation Plan

> **Status**: Migrated
> **Module**: `main/api/` (`akces-api`)

## Technical Context

| Aspect | Detail |
|--------|--------|
| Language | Java 25 |
| Build | Maven (inherits from `akces-framework-main` parent POM) |
| Artifact | `org.elasticsoftwarefoundation.akces:akces-api` |
| Packaging | JAR |

### Dependencies (compile-time only)

| Dependency | Purpose |
|------------|---------|
| `com.fasterxml.jackson.core:jackson-annotations` | `@JsonIgnore`, `@JsonTypeInfo`, `@JsonSubTypes`, `@JacksonAnnotation` |
| `jakarta.annotation:jakarta.annotation-api` | `@Nonnull` |
| `jakarta.validation:jakarta.validation-api` | `@NotNull`, `@NotEmpty` on handler signatures |
| `jakarta.inject:jakarta.inject-api` | Standard injection support |
| `org.slf4j:slf4j-api` | Logging facade |
| `org.springframework:spring-context` | `@Component`, `@AliasFor` meta-annotation |

### Test Dependencies

| Dependency | Purpose |
|------------|---------|
| `org.junit.jupiter:junit-jupiter` | Test framework |
| `org.assertj:assertj-core` | Fluent assertions |
| `org.mockito:mockito-core` | Mocking (available but currently unused) |
| `org.testng:testng` | Legacy test framework (present in POM) |

### Key Design Constraint

This module is the **dependency-free contract** that all other Akces modules implement or consume. It must never depend on Kafka, RocksDB, Confluent Schema Registry, Spring Boot, or any other Akces module. The dependency graph is strictly one-directional:

```
api  ←  shared  ←  runtime  ←  client
                ←  query-support
                ←  agentic
                ←  eventcatalog (annotation processor)
```

## Project Structure

```
main/api/
├── pom.xml
└── src/
    ├── main/java/org/elasticsoftware/akces/
    │   ├── AkcesException.java                          # Base runtime exception
    │   ├── aggregate/                                    # Core aggregate types (22 files)
    │   │   ├── Aggregate.java                           # Primary aggregate interface
    │   │   ├── AgenticAggregate.java                    # AI-integrated aggregate interface
    │   │   ├── AggregateState.java                      # State contract (getAggregateId, getIndexKey)
    │   │   ├── AgenticAggregateMemory.java              # Immutable memory entry record
    │   │   ├── MemoryAwareState.java                    # State with memory management
    │   │   ├── MemoryDistillation.java                  # In-progress distillation record
    │   │   ├── TaskAwareState.java                      # State with task tracking
    │   │   ├── AssignedTask.java                        # Assigned task record
    │   │   ├── AgentProcessAware.java                   # Common interface for process-backed items
    │   │   ├── RequestingParty.java                     # Sealed interface (agent | human)
    │   │   ├── AgentRequestingParty.java                # Agent requesting party record
    │   │   ├── HumanRequestingParty.java                # Human requesting party record
    │   │   ├── CommandHandlerFunction.java              # Functional interface for command handling
    │   │   ├── EventSourcingHandlerFunction.java        # Functional interface for event sourcing
    │   │   ├── EventBridgeHandlerFunction.java          # Functional interface for event bridging
    │   │   ├── EventHandlerFunction.java                # Functional interface for event handling
    │   │   ├── UpcastingHandlerFunction.java            # Functional interface for schema upcasting
    │   │   ├── CommandType.java                         # Command type descriptor record
    │   │   ├── DomainEventType.java                     # Domain event type descriptor record
    │   │   ├── AggregateStateType.java                  # State type descriptor record
    │   │   ├── SchemaType.java                          # Sealed schema type interface
    │   │   └── ProtocolRecordType.java                  # Sealed protocol record type interface
    │   ├── annotations/                                  # Framework annotations (19 files)
    │   │   ├── AggregateInfo.java                       # @Component, stateClass, GDPR, indexing
    │   │   ├── AgenticAggregateInfo.java                # @Component, memory limits, agent commands/events/errors
    │   │   ├── AggregateIdentifier.java                 # Marks partition-routing field
    │   │   ├── AggregateStateInfo.java                  # type + version for state
    │   │   ├── CommandHandler.java                      # create, produces, errors
    │   │   ├── CommandInfo.java                         # type + version for commands
    │   │   ├── DomainEventInfo.java                     # type + version for events
    │   │   ├── EventSourcingHandler.java                # create flag
    │   │   ├── EventHandler.java                        # create, produces, errors
    │   │   ├── EventBridgeHandler.java                  # (no attributes)
    │   │   ├── QueryModelInfo.java                      # @Component, version, indexName
    │   │   ├── QueryModelEventHandler.java              # create flag
    │   │   ├── QueryModelStateInfo.java                 # type + version
    │   │   ├── DatabaseModelInfo.java                   # @Component, version, schemaName
    │   │   ├── DatabaseModelEventHandler.java           # (no attributes)
    │   │   ├── ReflectorInfo.java                       # @Component, version, schemaName, retry config
    │   │   ├── ReflectorEventHandler.java               # (no attributes)
    │   │   ├── UpcastingHandler.java                    # (no attributes)
    │   │   └── PIIData.java                             # @JacksonAnnotation for GDPR field marking
    │   ├── commands/                                     # Command types (3 files)
    │   │   ├── Command.java                             # Command marker interface
    │   │   ├── CommandBus.java                          # Command dispatch interface
    │   │   └── CommandBusHolder.java                    # ThreadLocal CommandBus provider
    │   ├── events/                                       # Event types (2 files)
    │   │   ├── DomainEvent.java                         # Domain event marker interface
    │   │   └── ErrorEvent.java                          # Error event marker (extends DomainEvent)
    │   ├── gdpr/                                         # GDPR package (empty — PIIData is in annotations)
    │   ├── processmanager/                               # Process manager types (4 files)
    │   │   ├── ProcessManager.java                      # extends Aggregate<S> with process type
    │   │   ├── ProcessManagerState.java                 # extends AggregateState with process lookup
    │   │   ├── AkcesProcess.java                        # Process instance interface
    │   │   └── UnknownAkcesProcessException.java        # extends AkcesException
    │   └── query/                                        # Query/read-side types (8 files)
    │       ├── QueryModel.java                          # Query model interface
    │       ├── QueryModelState.java                     # Query model state interface
    │       ├── QueryModelStateType.java                 # Query model state type descriptor
    │       ├── QueryModelEventHandlerFunction.java      # Functional interface for QM event handlers
    │       ├── DatabaseModel.java                       # Database model interface (offsets, transactions)
    │       ├── DatabaseModelEventHandlerFunction.java   # Functional interface for DB event handlers
    │       ├── Reflector.java                           # Reflector interface (onSuccess, onFailure)
    │       └── ReflectorEventHandlerFunction.java       # Functional interface for reflector handlers
    └── test/java/org/elasticsoftware/akces/aggregate/   # Tests (5 files)
        ├── AgenticAggregateInfoTest.java                # @AgenticAggregateInfo annotation effects
        ├── AgenticAggregateMemoryTest.java              # Memory record round-trip through MemoryAwareState
        ├── MemoryAwareStateTest.java                    # MemoryAwareState contract + getMemories default
        ├── RequestingPartySerializationTest.java        # Sealed interface, Jackson annotations, equality
        └── TaskAwareStateTest.java                      # TaskAwareState contract + AssignedTask round-trip
```

**Totals**: 59 source files (~2205 lines), 5 test files (~1093 lines)

## Design Decisions

### D1: Interfaces Over Abstract Classes
All aggregate, command, event, and query contracts are interfaces (not abstract classes). This allows implementors maximum flexibility (e.g., a class can implement both `Aggregate` and other application interfaces). The only abstract class is `AkcesException`.

### D2: `@FunctionalInterface` with Default Throwing Methods
Handler function interfaces (e.g., `CommandHandlerFunction`) use `@FunctionalInterface` with default methods that throw `UnsupportedOperationException`. This allows the runtime to implement the full interface while still supporting lambda creation for the primary functional method.

### D3: ThreadLocal for CommandBus
`CommandBusHolder` uses `ThreadLocal<CommandBus>` to inject the command bus into aggregate handler methods without requiring constructor injection. The runtime sets and clears this ThreadLocal around handler invocations.

### D4: Sealed Hierarchies for Type Safety
`ProtocolRecordType` → `SchemaType` / `AggregateStateType` and `RequestingParty` → `Agent` / `Human` use Java sealed interfaces for exhaustive pattern matching in switch expressions.

### D5: `@Component` Meta-annotation for DI
Aggregate, query model, database model, and reflector info annotations are meta-annotated with Spring `@Component` so that annotated classes are automatically discovered and registered as Spring beans.

### D6: Separate `ErrorEvent` Marker
`ErrorEvent extends DomainEvent` exists as a distinct marker interface to enable the framework to distinguish business error outcomes from successful domain events, supporting the "error events, not exceptions" constitutional principle.

### D7: Agentic Extensions as Optional Interfaces
`MemoryAwareState`, `TaskAwareState`, and `AgentProcessAware` are opt-in interfaces. An agentic aggregate's state only needs to implement them if it uses the corresponding feature. The `AgenticAggregate.getMemories()` default method gracefully handles states that don't implement `MemoryAwareState`.

## Complexity Assessment

| Metric | Value |
|--------|-------|
| Source files | 59 |
| Source lines | ~2,205 |
| Test files | 5 |
| Test lines | ~1,093 |
| Packages | 7 (`root`, `aggregate`, `annotations`, `commands`, `events`, `gdpr`, `processmanager`, `query`) |
| Interfaces | ~25 |
| Annotations | 19 |
| Records | ~10 |
| Classes | 2 (`CommandBusHolder`, `AkcesException`) |
| Sealed hierarchies | 2 |

**Complexity**: Low-to-medium. The module is interface-heavy with almost no logic — it is purely a contract/API definition module. The most complex elements are the generic type signatures on handler function interfaces and the sealed type hierarchies.
