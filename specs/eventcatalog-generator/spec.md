# Feature Specification: EventCatalog Generator

**Feature Branch**: `eventcatalog-generator`
**Created**: 2025-04-17
**Status**: Migrated
**Input**: Brownfield migration of existing `main/eventcatalog/` module (~1530 source lines, ~1408 test lines)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Compile-Time EventCatalog Documentation (Priority: P1)

A framework user annotates their aggregates, commands, and domain events with `@AggregateInfo`, `@CommandInfo`, `@DomainEventInfo`, `@AgenticAggregateInfo`, `@CommandHandler`, and `@EventHandler`. At compile time the `EventCatalogProcessor` annotation processor scans those annotations, resolves the aggregate→handler→command/event relationships, and writes a complete set of EventCatalog-compatible MDX files and JSON Schema files into `META-INF/eventcatalog/` inside the compiled JAR.

**Why this priority**: This is the core value proposition — automatic API documentation from source annotations with zero runtime overhead.

**Independent Test**: Compile a sample aggregate source set through the processor using the Google `compile-testing` library and verify the expected `index.mdx` and `schema.json` resource files are produced with correct content.

**Acceptance Scenarios**:

1. **Given** a class annotated with `@AggregateInfo` containing `@CommandHandler` and `@EventHandler` methods, **When** the module is compiled with the `EventCatalogProcessor` on the annotation processor path, **Then** a service `index.mdx` is generated at `META-INF/eventcatalog/services/{AggregateName}/index.mdx` listing all received commands/events and all produced events.
2. **Given** command and event records annotated with `@CommandInfo` and `@DomainEventInfo`, **When** the processor runs, **Then** individual `index.mdx` + `schema.json` files are created under the service's `commands/` and `events/` subdirectories.
3. **Given** processor options `eventcatalog.owners`, `eventcatalog.repositoryBaseUrl`, and `eventcatalog.schemaDomain`, **When** the processor runs, **Then** these values are embedded in the generated MDX frontmatter.
4. **Given** an `@AgenticAggregateInfo`-annotated class, **When** the processor runs, **Then** the service MDX includes `type: Singleton` and built-in memory commands/events (`StoreMemory`, `ForgetMemory`, `MemoryStored`, `MemoryRevoked`).

---

### User Story 2 - JSON Schema Generation (Priority: P2)

A framework user needs machine-readable schemas for their commands and events for downstream tooling and documentation portals. The `JsonSchemaGenerator` inspects record components and field types at compile time to produce a Draft-07 JSON Schema (`schema.json`) for each command and event, including proper type mappings, required-field detection from `@NotNull`/`@NotBlank`/`@NotEmpty`, and nested object/enum/collection support.

**Why this priority**: Schemas are a dependency of the EventCatalog documentation (referenced in the MDX template) and also serve as standalone API contracts.

**Independent Test**: Invoke `JsonSchemaGenerator.generate()` directly within the annotation processing environment against sample type elements and validate the resulting JSON structure.

**Acceptance Scenarios**:

1. **Given** a Java record with primitive, boxed, `String`, `BigDecimal`, `UUID`, `java.time.*` fields, **When** `JsonSchemaGenerator.generate()` is called, **Then** each field maps to the correct JSON Schema type/format (e.g., `UUID` → `string` + `format: uuid`).
2. **Given** a field annotated with `@NotNull`, **When** the schema is generated, **Then** it appears in the `required` array and the `type` is a plain string (not a `["type", "null"]` union).
3. **Given** a record containing `List<T>`, `Set<T>`, `Map<K,V>`, or an enum field, **When** the schema is generated, **Then** the correct `array`/`object` type with `items`/`uniqueItems`/`enum` constraints is emitted.
4. **Given** a record with nested custom record fields, **When** the schema is generated, **Then** the nested type is inlined as an `object` with its own `properties`, `required`, and `additionalProperties: false`.

---

### User Story 3 - Service & Message Template Generation (Priority: P3)

The `ServiceTemplateGenerator` and `MessageTemplateGenerator` produce the MDX frontmatter strings that the processor writes to disk. Each template embeds metadata (id, version, summary, owners, receives/sends, repository URL) into an EventCatalog-compatible MDX format with Astro component imports (`<NodeGraph />`, `<Schema />`, `<Footer />`).

**Why this priority**: These are leaf utilities consumed by the processor; they are simpler and lower-risk.

**Independent Test**: Unit-test each generator with hand-crafted metadata records and assert the output string matches the expected MDX.

**Acceptance Scenarios**:

1. **Given** a `ServiceMetadata` record, **When** `ServiceTemplateGenerator.generate()` is called, **Then** the output is valid MDX frontmatter listing `receives` and `sends` as YAML arrays with `id`/`version` entries.
2. **Given** a `ServiceMetadata` with a non-null `type` field, **When** the template is generated, **Then** a `type:` line appears in the frontmatter.
3. **Given** an `EventMetadata` record, **When** `MessageTemplateGenerator.generate()` is called, **Then** the output MDX includes the JSON Schema reference `<Schema schemaPath="schema.json" />`.

---

### Edge Cases

- What happens when `@CommandInfo.type()` or `@DomainEventInfo.type()` is empty? Processor falls back to the class simple name.
- What happens when an aggregate has no command handlers? The service `receives` list is empty (`[]`).
- What happens when a handler's `produces` or `errors` annotation attribute references a type not annotated with `@DomainEventInfo`? The processor skips it (filter via `Objects::nonNull`).
- How does the processor handle multiple compilation rounds? It guards with a `processed` boolean and returns `true` after the first complete round.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Processor MUST be auto-discovered via `@AutoService(Processor.class)` — no manual `META-INF/services` entry needed.
- **FR-002**: Processor MUST support `@SupportedAnnotationTypes` for `CommandInfo`, `DomainEventInfo`, `AggregateInfo`, `AgenticAggregateInfo`, `CommandHandler`, `EventHandler`.
- **FR-003**: Processor MUST write output files to `StandardLocation.CLASS_OUTPUT` under `META-INF/eventcatalog/`.
- **FR-004**: JSON Schema MUST follow Draft-07 (`$schema: http://json-schema.org/draft-07/schema#`) and set `additionalProperties: false`.
- **FR-005**: All generated MDX MUST include `import Footer from '@catalog/components/footer.astro';`.
- **FR-006**: Processor options (`eventcatalog.owners`, `eventcatalog.repositoryBaseUrl`, `eventcatalog.schemaDomain`) MUST have sensible defaults.
- **FR-007**: Processor MUST handle both standard aggregates (`@AggregateInfo`) and agentic aggregates (`@AgenticAggregateInfo`).

### Key Entities

- **EventCatalogProcessor**: Main `AbstractProcessor` subclass — orchestrates annotation scanning, handler-to-aggregate matching, relationship building, and file generation.
- **JsonSchemaGenerator**: Static utility — maps Java type mirrors to JSON Schema Draft-07 structures.
- **MessageTemplateGenerator**: Static utility — formats `EventMetadata` into MDX message templates.
- **ServiceTemplateGenerator**: Static utility — formats `ServiceMetadata` into MDX service templates.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All 3 test classes pass (`EventCatalogProcessorTest`, `MessageTemplateGeneratorTests`, `ServiceTemplateGeneratorTests`).
- **SC-002**: `mvn clean install -pl main/eventcatalog` completes with zero errors.
- **SC-003**: Generated MDX and JSON Schema files match EventCatalog's expected directory layout (`services/{name}/index.mdx`, `services/{name}/commands/{cmd}/index.mdx`, etc.).
- **SC-004**: Schema output is valid JSON and parses without error.

## Assumptions

- The `api` module (annotation definitions) is stable and does not change as part of this feature.
- EventCatalog MDX format follows the conventions at [eventcatalog.dev](https://eventcatalog.dev).
- `compile-testing` library (Google) is available for annotation processor unit testing.
- The processor only needs to support Java source version 21+ (`@SupportedSourceVersion(RELEASE_21)`).
- Spring Expression Language (`spring-expression`) is a compile dependency for potential template evaluation.
