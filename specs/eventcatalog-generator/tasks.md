# Tasks: EventCatalog Generator

**Input**: Design documents from `specs/eventcatalog-generator/`
**Prerequisites**: plan.md ✅, spec.md ✅
**Status**: All tasks complete (brownfield migration)

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

- **Module root**: `main/eventcatalog/src/main/java/org/elasticsoftware/akces/eventcatalog/`
- **Test root**: `main/eventcatalog/src/test/java/org/elasticsoftware/akces/eventcatalog/`
- **Test command**: `cd main/eventcatalog && mvn test`
- **Full build**: `mvn clean install -pl main/eventcatalog`

---

## Phase 1: Setup

- [x] T001 Maven module `akces-eventcatalog` with dependencies on `akces-api`, `spring-expression`, `auto-service` (provided), `compile-testing` (test) — `main/eventcatalog/pom.xml`
- [x] T002 Configure `maven-compiler-plugin` with `--add-modules java.compiler`

---

## Phase 2: User Story 1 — Compile-Time EventCatalog Documentation (P1) 🎯 MVP

**Goal**: Annotation processor scans Akces annotations and generates EventCatalog MDX + JSON Schema at compile time.

- [x] T003 [US1] Implement `EventCatalogProcessor` extending `AbstractProcessor` with `@AutoService`, `@SupportedAnnotationTypes`, `@SupportedSourceVersion`, and `@SupportedOptions` — `EventCatalogProcessor.java`
- [x] T004 [US1] Implement annotation collection phase: `processCommandInfoAnnotations`, `processDomainEventInfoAnnotations`, `processAggregateInfoAnnotations`, `processAgenticAggregateInfoAnnotations`, `processCommandHandlerAnnotations`, `processEventHandlerAnnotations`
- [x] T005 [US1] Implement handler-to-aggregate matching (`matchHandlersToAggregates`) — resolves enclosing class of `@CommandHandler`/`@EventHandler` methods to their `@AggregateInfo`/`@AgenticAggregateInfo`
- [x] T006 [US1] Implement event relationship building (`buildEventRelationships`) — extracts `produces`/`errors` class arrays from handler annotations via `AnnotationMirror`
- [x] T007 [US1] Implement catalog generation (`generateCatalogEntries`) — generates service, command, and event MDX + schema for standard and agentic aggregates
- [x] T008 [US1] Implement file output (`writeToEventCatalog`) — writes to `StandardLocation.CLASS_OUTPUT` under `META-INF/eventcatalog/`
- [x] T009 [US1] End-to-end annotation processing test with single aggregate — `EventCatalogProcessorTest.testProcessorWithSingleAggregate()`
- [x] T010 [US1] Run `cd main/eventcatalog && mvn test` — all tests pass

---

## Phase 3: User Story 2 — JSON Schema Generation (P2)

**Goal**: Generate Draft-07 JSON Schema from Java type mirrors for all commands and events.

- [x] T011 [US2] Implement `JsonSchemaGenerator.generate()` — top-level schema with `$schema`, `id`, `type: object`, `properties`, `required`, `additionalProperties: false`
- [x] T012 [US2] Implement `extractProperties` — iterates record/class fields, skipping static/transient
- [x] T013 [US2] Implement `mapTypeToJsonSchema` — type mapping for primitives, boxed types, `String`, `BigDecimal`, `UUID`, `java.time.*`, `List`, `Set`, `Map`, enums, nested records
- [x] T014 [US2] Implement `hasRequiredAnnotation` — checks `@NotNull`, `@NotBlank`, `@NotEmpty`, `@NonNull`
- [x] T015 [US2] Implement hand-rolled JSON serializer (`mapToJson`, `appendValue`, `escapeJsonString`)
- [x] T016 [US2] Schema validation in `EventCatalogProcessorTest` — verifies generated schemas parse as valid JSON and match expected structure via `SchemaDiff`

---

## Phase 4: User Story 3 — Service & Message Templates (P3)

**Goal**: MDX template generators for EventCatalog service and message documentation.

- [x] T017 [P] [US3] Implement `MessageTemplateGenerator` with `EventMetadata` record and `generate()` method — `MessageTemplateGenerator.java`
- [x] T018 [P] [US3] Implement `ServiceTemplateGenerator` with `ServiceMetadata` record (including `type` field for agentic), `Message` record, and `generate()` method — `ServiceTemplateGenerator.java`
- [x] T019 [P] [US3] Unit tests for `MessageTemplateGenerator` — `MessageTemplateGeneratorTests.java`
- [x] T020 [P] [US3] Unit tests for `ServiceTemplateGenerator` including agentic aggregate `type: Singleton` — `ServiceTemplateGeneratorTests.java`

---

## Phase 5: Polish

- [x] T021 Run full module build `mvn clean install -pl main/eventcatalog` — verify clean build
- [x] T022 Verify generated output matches EventCatalog directory conventions (`services/{name}/index.mdx`, `services/{name}/commands/{cmd}/index.mdx`, etc.)
