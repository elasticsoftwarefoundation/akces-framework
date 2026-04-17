# Implementation Plan: EventCatalog Generator

**Branch**: `eventcatalog-generator` | **Date**: 2025-04-17 | **Spec**: `specs/eventcatalog-generator/spec.md`
**Input**: Brownfield migration — module already implemented in `main/eventcatalog/`

## Summary

The EventCatalog Generator is a compile-time Java annotation processor that produces [EventCatalog](https://eventcatalog.dev)-compatible documentation (MDX frontmatter + JSON Schema) from Akces Framework annotations. It scans `@AggregateInfo`, `@AgenticAggregateInfo`, `@CommandInfo`, `@DomainEventInfo`, `@CommandHandler`, and `@EventHandler` at compile time, resolves aggregate→handler→message relationships, and writes the output into `META-INF/eventcatalog/` inside the compiled JAR. The module depends only on `akces-api` at compile scope (plus `spring-expression` and `auto-service`).

## Technical Context

**Language/Version**: Java 25 (source compatibility set to 21 via `@SupportedSourceVersion`)
**Primary Dependencies**: `akces-api` (annotations), `spring-expression`, `com.google.auto.service:auto-service` (provided scope)
**Test Dependencies**: `compile-testing` (Google), JUnit Jupiter, `akces-shared` (for schema diff utilities), Jackson 3
**Storage**: None — pure compile-time code generation to `StandardLocation.CLASS_OUTPUT`
**Testing**: JUnit Jupiter + Google `compile-testing`; run with `cd main/eventcatalog && mvn test`
**Build**: `mvn clean install -pl main/eventcatalog` (requires `--add-modules java.compiler`)
**Constraints**: Processor must be non-blocking; runs in the compiler's annotation processing environment

## Constitution Check

*GATE: Passed — existing module, no new modules or structural changes required.*

- ✅ Single module, clear scope
- ✅ No runtime dependencies beyond `api`
- ✅ No database or Kafka interaction
- ✅ Tests use Google `compile-testing` (no Testcontainers needed)

## Project Structure

### Documentation (this feature)

```text
specs/eventcatalog-generator/
├── spec.md              # Feature specification (migrated)
├── plan.md              # This file
└── tasks.md             # Task list (all complete)
```

### Source Code (repository root)

```text
main/eventcatalog/
├── pom.xml
└── src/
    ├── main/java/org/elasticsoftware/akces/eventcatalog/
    │   ├── EventCatalogProcessor.java        # Main annotation processor (858 lines)
    │   ├── JsonSchemaGenerator.java           # JSON Schema Draft-07 generation (427 lines)
    │   ├── MessageTemplateGenerator.java      # MDX message template (78 lines)
    │   └── ServiceTemplateGenerator.java      # MDX service template (167 lines)
    └── test/java/org/elasticsoftware/akces/eventcatalog/
        ├── EventCatalogProcessorTest.java     # End-to-end annotation processing tests (1085 lines)
        ├── MessageTemplateGeneratorTests.java # Unit tests for message template (121 lines)
        └── ServiceTemplateGeneratorTests.java # Unit tests for service template (202 lines)
```

**Structure Decision**: Option A — existing module within `main/`. No structural changes needed.

## Key Design Decisions

### Annotation Processing Pipeline

The processor runs in a single pass with the following phases:
1. **Collect** — Cache all annotated elements (`commandCache`, `eventCache`, `aggregateCache`, `agenticAggregateCache`, `commandHandlerCache`, `eventHandlerCache`)
2. **Match** — Associate `@CommandHandler`/`@EventHandler` methods with their enclosing aggregate class
3. **Build relationships** — Extract `produces` and `errors` class arrays from handler annotations to determine sends/receives per aggregate
4. **Generate** — For each aggregate, produce a service MDX + per-command and per-event MDX + JSON Schema files

### JSON Schema Strategy

- Uses `javax.lang.model` type mirrors (not reflection) to introspect record fields at compile time
- Maps Java types → JSON Schema types with special handling for `UUID`, `BigDecimal`, `java.time.*`, enums, nested records, collections
- Nullable vs required driven by `@NotNull` / `@NotBlank` / `@NotEmpty` annotations
- Hand-rolled JSON serializer (no runtime Jackson dependency)

### Agentic Aggregate Support

- `@AgenticAggregateInfo` aggregates are documented as `type: Singleton` services
- Built-in memory commands (`StoreMemory`, `ForgetMemory`) and events (`MemoryStored`, `MemoryRevoked`) are automatically injected into the receives/sends lists

## Complexity Tracking

No violations — single-module, well-bounded scope.
