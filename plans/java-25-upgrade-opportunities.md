# Java 25 Upgrade Opportunities Plan

This document outlines the potential areas in the Akces Framework where new Java 25 features can be implemented to improve readability, maintainability, and performance.

## 1. Module Import Declarations (JEP 494)
Many classes in the project have extensive import lists from `java.util`, `java.util.stream`, and `java.io`. These can be simplified using module imports.

- **Targets:**
    - `org.elasticsoftware.akces.AkcesAggregateController`
    - `org.elasticsoftware.akces.query.database.AkcesDatabaseModelController`
    - `org.elasticsoftware.akces.query.models.AkcesQueryModelController`
    - `org.elasticsoftware.akces.client.AkcesClientController`
    - `org.elasticsoftware.akces.eventcatalog.EventCatalogProcessor`
- **Benefit:** Reduces boilerplate and improves readability of the header section of source files.

## 2. Flexible Constructor Bodies (JEP 492)
Several exception classes and controllers perform logic or lookups before calling `super()`. This logic can now be moved inside the constructor body *before* the `super()` call.

- **Targets:**
    - `org.elasticsoftware.akces.client.UnknownSchemaException`: Move `commandClass.getAnnotation(CommandInfo.class)` and message construction logic before `super`.
    - `org.elasticsoftware.akces.client.CommandSendingFailedException`
    - `org.elasticsoftware.akces.schemas.SchemaNotBackwardsCompatibleException`
- **Benefit:** Allows for better validation and preparation of arguments before invoking the parent constructor.

## 3. Derived Record State (JEP 468)
The framework makes extensive use of records for `AggregateState` and `DomainEvent`. Updating these currently involves creating new instances by passing all fields. Java 25's `with` expression simplifies this.

- **Targets:**
    - `org.elasticsoftware.cryptotrading.aggregates.wallet.Wallet` (and other aggregates): Simplify `EventSourcingHandler` methods where state is updated.
    - `org.elasticsoftware.akces.client.AkcesClientController`: Simplify updating pending command responses.
- **Benefit:** More concise and less error-prone way to create modified copies of records.

## 4. Stream Gatherers (JEP 485)
Complex stream operations that currently use `collect`, `reduce`, or multiple intermediate operations can be refactored using custom or built-in gatherers.

- **Targets:**
    - `org.elasticsoftware.akces.schemas.KafkaSchemaRegistry`: Streamlining compatibility check logic.
    - `org.elasticsoftware.akces.query.database.KafkaDatabaseModelRuntime`: Handling batches of events more efficiently.
- **Benefit:** More expressive and potentially more efficient stream processing pipelines.

## 5. Scoped Values (JEP 487)
`ThreadLocal` is used in several places to hold context (CommandBus, GDPR context, etc.). These are candidates for `ScopedValue`.

- **Targets:**
    - `org.elasticsoftware.akces.commands.CommandBusHolder`
    - `org.elasticsoftware.akces.gdpr.GDPRContextHolder`
    - `org.elasticsoftware.akces.schemas.KafkaSchemaRegistry` (for `SchemaGenerator`)
- **Benefit:** Safer, more efficient, and better suited for virtual threads compared to `ThreadLocal`.

## 6. Structured Concurrency (JEP 480)
Where the framework performs multiple independent operations (e.g., querying multiple partitions or services in parallel), `StructuredTaskScope` can be used.

- **Targets:**
    - `org.elasticsoftware.akces.client.AkcesClientController`: Parallel command execution or status checks.
    - `org.elasticsoftware.akces.query.models.AkcesQueryModelController`: Hydration processes.
- **Benefit:** Better error handling, cancellation propagation, and observability for concurrent tasks.

## Implementation Strategy
1. **Phase 1 (Low Risk):** Implement Module Import Declarations and Flexible Constructor Bodies.
2. **Phase 2 (Medium Risk):** Refactor Record updates to use Derived Record State.
3. **Phase 3 (High Impact):** Replace `ThreadLocal` with `ScopedValue` and introduce Stream Gatherers.
4. **Phase 4 (Advanced):** Introduce Structured Concurrency in coordination with Virtual Threads.
