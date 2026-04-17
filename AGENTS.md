# Akces Framework — Agent Boundaries

This file defines ownership boundaries for AI coding agents working in this multi-module Maven monorepo.
Each module has a clear ownership scope. Agents must not violate module dependency rules (see constitution).

## Module Ownership

### `main/api` — Public API Agent
- **Owns**: All interfaces, annotations, and base types in `main/api/src/`
- **May modify**: `main/api/src/main/java/org/elasticsoftware/akces/` subtree
- **Must NOT depend on**: Spring Boot internals, Kafka, RocksDB, Embabel, `runtime`, `shared`, `query-support`, `agentic`
- **Allowed deps**: Jackson annotations, Jakarta (validation, annotation, inject), SLF4J

### `main/shared` — Shared Utilities Agent
- **Owns**: Protocol records, GDPR/PII handling, schema registry client, Kafka utilities in `main/shared/src/`
- **May modify**: `main/shared/src/main/java/org/elasticsoftware/akces/` subtree
- **Must NOT depend on**: `runtime`, `query-support`, `agentic`, `client`
- **Allowed deps**: `api`, Jackson, Kafka clients, victools (JSON Schema), Confluent Schema Registry client

### `main/runtime` — Runtime Engine Agent
- **Owns**: Event sourcing engine, Kafka partition management, RocksDB state, Spring bean wiring in `main/runtime/src/`
- **May modify**: `main/runtime/src/` subtree
- **Must NOT depend on**: `agentic`, `query-support`, `client`
- **Allowed deps**: `api`, `shared`, Spring Boot, Kafka, RocksDB, Testcontainers (test scope)

### `main/client` — Client Library Agent
- **Owns**: `AkcesClient` and command submission utilities in `main/client/src/`
- **May modify**: `main/client/src/` subtree
- **Must NOT depend on**: `runtime` internals, `agentic`, `query-support`
- **Allowed deps**: `api`, `shared`

### `main/query-support` — Query Support Agent
- **Owns**: `QueryModel`, `DatabaseModel`, JDBC/JPA support beans in `main/query-support/src/`
- **May modify**: `main/query-support/src/` subtree
- **Must NOT depend on**: `runtime` internals, `agentic`, `client`
- **Allowed deps**: `api`, `shared`, Spring JDBC, Spring Data JPA

### `main/eventcatalog` — EventCatalog Agent
- **Owns**: Annotation processor for EventCatalog documentation in `main/eventcatalog/src/`
- **May modify**: `main/eventcatalog/src/` subtree
- **Must NOT depend on**: `runtime`, `shared`, `agentic`, `query-support`, `client`
- **Allowed deps**: `api` only (annotation processor — compile-time only)

### `main/agentic` — Agentic Agent
- **Owns**: `AgenticAggregate`, Embabel integration, memory distillation in `main/agentic/src/`
- **May modify**: `main/agentic/src/` subtree
- **Must NOT depend on**: `query-support`, `client`; must not have circular dep back to `runtime` public API beyond what `runtime` exports
- **Allowed deps**: `api`, `shared`, `runtime`, Embabel Agent Framework, Spring AI

### `services/operator` — Kubernetes Operator Agent
- **Owns**: Kubernetes CRD operator, AggregateService/CommandService/QueryService reconcilers in `services/operator/src/`
- **May modify**: `services/operator/src/` subtree, `services/operator/pom.xml`
- **Must NOT depend on**: `agentic`, `query-support`, `client`, `runtime` internals
- **Allowed deps**: `api`, `shared`, Java Operator SDK (JOSDK), Spring Boot

### `test-apps/crypto-trading` — Test App Agent
- **Owns**: Reference implementation in `test-apps/crypto-trading/`
- **May modify**: All files under `test-apps/crypto-trading/`
- **Purpose**: Smoke tests and integration validation — not a production artifact
- **Allowed deps**: Any framework module

### `bom` — BOM Agent
- **Owns**: `bom/pom.xml` — the Bill of Materials for all dependency versions
- **May modify**: `bom/pom.xml` only
- **Note**: Version bumps here affect the entire project; coordinate with all module agents

## Inter-Agent Rules

1. **No circular dependencies** — follow the dependency order: `api → shared → runtime → client / query-support / agentic`
2. **Interface-first changes** — if a new interface is needed in `api`, the API agent must make that change before dependent modules implement it
3. **Schema changes require coordination** — any change to `@CommandInfo`, `@DomainEventInfo`, or `@AggregateStateInfo` version requires the owning agent to also provide `@UpcastingHandler` migrations
4. **Constitution check** — before writing any code, verify the change complies with `.specify/memory/constitution.md`
5. **Full build verification** — after any cross-module change, run `mvn clean install` from the repository root

## Test Commands per Module

| Module | Test Command |
|--------|-------------|
| `main/api` | `cd main/api && mvn test` |
| `main/shared` | `cd main/shared && mvn test` |
| `main/runtime` | `cd main/runtime && mvn test` |
| `main/client` | `cd main/client && mvn test` |
| `main/query-support` | `cd main/query-support && mvn test` |
| `main/eventcatalog` | `cd main/eventcatalog && mvn test` |
| `main/agentic` | `cd main/agentic && mvn test` |
| `services/operator` | `cd services/operator && mvn test` |
| All modules | `mvn clean install` (from repo root) |
