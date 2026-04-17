# Akces Framework Constitution

## Core Principles

### I. CQRS / Event Sourcing — The Single Source of Truth
All state changes flow through commands → aggregates → domain events. There is no other pathway to mutate state. Events stored in Kafka are the system of record; aggregate state in RocksDB is a derived projection. Query models and database models are read-only projections of the event stream and must never be written to directly by application code.

### II. Error Events — Not Exceptions (NON-NEGOTIABLE)
Command handlers MUST NOT throw exceptions for business-rule violations. Instead, they MUST return error events (e.g., `InsufficientFundsErrorEvent`). The `produces` and `errors` arrays on `@CommandHandler` must exhaustively list all possible outcome types. Exceptions are reserved for unrecoverable infrastructure failures only.

### III. Immutable State via Java Records (NON-NEGOTIABLE)
All aggregate state classes (`AggregateState`), domain events (`DomainEvent`), commands (`Command`), and protocol types (`DomainEventRecord`, etc.) MUST be declared as Java records. Records must never be mutated after construction. `@EventSourcingHandler` and `@QueryModelEventHandler` methods must return new state instances.

### IV. Explicit Versioning — All Types
Every `@CommandInfo`, `@DomainEventInfo`, `@AggregateStateInfo`, `@QueryModelInfo`, and `@DatabaseModelInfo` annotation MUST declare a `version` field (minimum version = 1). When evolving a type in a breaking way, increment the version and provide an `@UpcastingHandler` to convert old records to the new version. Never edit an existing versioned type in place.

### V. Aggregate Identity and Partitioning
Every command and event MUST include a field annotated with `@AggregateIdentifier`. This field drives Kafka partition routing and must be stable for the lifetime of the aggregate. `getAggregateId()` must return this field. Never change the semantics of the aggregate identifier after initial deployment.

### VI. PII / GDPR — Annotate and Never Store Raw
All personally identifiable information fields in aggregate state or query models MUST be annotated with `@PIIData`. The framework encrypts/decrypts these fields transparently. Never log, print, or expose raw PII values. Encryption keys are managed via a dedicated Kafka topic — never store them in code or config files.

### VII. Module Dependency Rules
The module dependency order is strictly top-to-bottom. No upward dependencies are allowed:

```
api  ←  shared  ←  runtime  ←  client
                ←  query-support
                ←  agentic (depends on runtime)
                ←  eventcatalog (annotation processor, depends on api only)
```

`api` has zero framework-specific dependencies (only Jackson, Jakarta, SLF4J). `runtime` may depend on `shared` and `api`. `agentic` may depend on `runtime`, `shared`, and `api`. Nothing in `api`, `shared`, or `runtime` may depend on `agentic`.

### VIII. Integration Tests Use Testcontainers
Integration tests that require Kafka MUST use `testcontainers-kafka` (not embedded brokers or mocks). Use `@SpringBootTest` with `@DirtiesContext` for full-stack aggregate tests. Tests that require a live LLM must be guarded with `@EnabledIfEnvironmentVariable`. Unit tests must not depend on running infrastructure.

### IX. Aggregate Boundaries — Focused and Bounded
Each aggregate must represent exactly one consistency boundary. Aggregates must not call each other directly. Cross-aggregate coordination is done via Process Managers (`ProcessManager<T, P>`) or the `AkcesClient`. Keep aggregates small; prefer many small aggregates over one large one.

### X. No Blocking Calls in Handlers
Command handlers, event sourcing handlers, and query model event handlers must be synchronous, fast, and deterministic. No I/O, no network calls, no blocking operations inside handlers. Handlers must be idempotent where applicable (especially database model event handlers).

### XI. Backward Compatibility — Preserve the Event Log
The Kafka event log is permanent. Never delete or alter event schemas in ways that break consumers of older events. All schema changes must be backward-compatible or mediated by an upcasting handler. The Confluent Schema Registry enforces compatibility — `forceRegister: false` in production.

## Module Structure and Code Boundaries

| Module | Path | Owns | Forbidden |
|--------|------|------|-----------|
| API | `main/api/` | Public interfaces, annotations, base types | Framework deps, Kafka, RocksDB |
| Shared | `main/shared/` | Protocol records, GDPR, schema registry, Kafka utils | Business logic |
| Runtime | `main/runtime/` | Event sourcing engine, Kafka partition management, bean wiring, RocksDB state | Embabel, query-support |
| Client | `main/client/` | Command submission client | Runtime internals |
| Query Support | `main/query-support/` | QueryModel, DatabaseModel beans, JDBC/JPA support | Runtime internals |
| EventCatalog | `main/eventcatalog/` | Annotation processor for EventCatalog docs | Runtime deps |
| Agentic | `main/agentic/` | AgenticAggregate, Embabel integration, memory distillation | Direct query-support deps |
| Operator | `services/operator/` | Kubernetes CRD operator | Framework runtime internals |
| Test Apps | `test-apps/` | Reference implementations, integration smoke tests | Production deployments |

## Development Conventions

- **Language**: Java 25; use modern features (records, sealed classes, pattern matching, virtual threads)
- **Naming**: PascalCase for classes; camelCase for methods/fields; `*Command`, `*Event`, `*State`, `*QueryModel` suffixes for domain types
- **Package root**: `org.elasticsoftware.akces.*`
- **Build**: `mvn clean install` from root; `mvn test` per module (e.g., `cd main/runtime && mvn test`)
- **Branch naming**: `feature/*`, `bugfix/*`, `copilot/*`; avoid `main` direct commits
- **Commits**: Prefer Conventional Commits (`feat:`, `fix:`, `chore:`, `refactor:`, `build:`, `test:`)
- **Do not test directly**: annotations, records, interfaces, exceptions, enums — test them through their consumers

## Quality Gates

All PRs must pass:
1. `mvn clean install` (full build including all tests)
2. GitHub Actions `maven.yml` CI (CodeQL included)
3. No raw PII in logs, state, or events without `@PIIData`
4. No business exceptions thrown from command handlers
5. All new types carry a `version` annotation field
6. Schema Registry compatibility check (non-`forceRegister` mode)

## Governance

This constitution supersedes all other per-feature conventions. Amendments require updating this file, documenting the rationale, and ensuring all active feature work is migrated. For detailed runtime development guidance refer to `FRAMEWORK_OVERVIEW.md` and the Copilot skill at `.github/copilot-setup-steps.yml`.

**Version**: 1.0.0 | **Ratified**: 2026-04-17 | **Last Amended**: 2026-04-17
