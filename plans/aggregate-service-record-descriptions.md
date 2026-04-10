# Plan: Add `description` Field to `AggregateServiceRecord` and Contained Types

## Overview

To make the Agentic Layer more descriptive, the `AggregateServiceRecord` and its contained types
(`AggregateServiceCommandType`, `AggregateServiceDomainEventType`) need to expose human-readable
description strings. These descriptions are already declared by developers in the existing `*Info`
annotations (`@AggregateInfo`, `@AgenticAggregateInfo`, `@CommandInfo`, `@DomainEventInfo`); they
simply need to be propagated into the control record that is published to the `Akces-Control` Kafka
topic and consumed by service-discovery clients (including the Agentic Layer).

## Current State

| Type | Location | Has `description`? |
|---|---|---|
| `@AggregateInfo` | `main/api` | ✅ `description()` default `""` |
| `@AgenticAggregateInfo` | `main/api` | ✅ `description()` default `""` |
| `@CommandInfo` | `main/api` | ✅ `description()` default `""` |
| `@DomainEventInfo` | `main/api` | ✅ `description()` default `""` |
| `AggregateServiceRecord` | `main/shared` | ❌ missing |
| `AggregateServiceCommandType` | `main/shared` | ❌ missing |
| `AggregateServiceDomainEventType` | `main/shared` | ❌ missing |

The descriptions are read from annotations at runtime during control-record publication, so they
will be available via `typeClass().getAnnotation(...)`.

## Affected Modules

- **`main/shared`** – records `AggregateServiceRecord`, `AggregateServiceCommandType`,
  `AggregateServiceDomainEventType`
- **`main/runtime`** – `AggregateRuntime` interface, `KafkaAggregateRuntime`,
  `AkcesAggregateController.publishControlRecord()`
- **`main/agentic`** – `AkcesAgenticAggregateController.publishControlRecord()`
- **Tests** – unit/integration tests that construct these records directly

## Architectural Decisions

### 1. Backward Compatibility

The `description` field is nullable (or missing from legacy JSON). Jackson deserializes unknown
fields gracefully and missing fields default to `null`. No migration of existing Kafka records is
needed. Consumers must treat `null` description as "no description available".

### 2. Description Source

| Record field | Source annotation | Source field |
|---|---|---|
| `AggregateServiceRecord.description` | `@AggregateInfo` or `@AgenticAggregateInfo` | `.description()` |
| `AggregateServiceCommandType.description` | `@CommandInfo` on command class | `.description()` |
| `AggregateServiceDomainEventType.description` | `@DomainEventInfo` on event class | `.description()` |

For `AggregateServiceRecord`, the description is read from the aggregate class annotation. This
requires exposing `getDescription()` on the `AggregateRuntime` interface and implementing it in
`KafkaAggregateRuntime`. The implementation reads the `@AggregateInfo` or `@AgenticAggregateInfo`
annotation from the aggregate class stored in the runtime.

For `AggregateServiceCommandType` and `AggregateServiceDomainEventType`, the description is
extracted at publish time directly from the `typeClass()` field of the `CommandType<?>` /
`DomainEventType<?>` objects (which are available in the publisher).

### 3. Empty vs. Null

When the annotation `description()` returns `""` (the default), the stored value in the record
will be `null` (normalized). This keeps the JSON clean for the common case where no description is
provided. A helper method (or inline ternary) will be used at population time:
```java
String desc = annotation.description();
String normalized = (desc == null || desc.isBlank()) ? null : desc;
```

### 4. Built-in / System Types in Agentic Controller

The `AkcesAgenticAggregateController` also constructs `AggregateServiceCommandType` and
`AggregateServiceDomainEventType` for built-in types (`StoreMemoryCommand`,
`ForgetMemoryCommand`, and built-in events). These will also pick up descriptions from their
`@CommandInfo` / `@DomainEventInfo` annotations.

---

## Phases

### Phase 1 – Extend `main/shared` Control Records

**Scope:** `main/shared` module only.

1. Add `@Nullable String description` as the **last** component to `AggregateServiceRecord`.
   - Keep existing constructor order; append `description` at the end to minimise diff.
   - Use `@tools.jackson.annotation.JsonInclude(NON_NULL)` to suppress `null` in JSON output.
   - Update the JavaDoc `@param` block.

2. Add `@Nullable String description` as the **last** component to `AggregateServiceCommandType`.
   - Same JSON treatment.
   - Update JavaDoc.

3. Add `@Nullable String description` as the **last** component to
   `AggregateServiceDomainEventType`.
   - Same JSON treatment.
   - Update JavaDoc.

**Backward compatibility note:** Adding a new nullable field to a Java `record` is a breaking
change in the constructor signature. All construction sites must be updated (see Phase 3).
Deserialization of old JSON (without the field) continues to work because Jackson uses the
`@JsonProperty`-annotated constructor parameters and unknown/missing fields are ignored.

---

### Phase 2 – Add `getDescription()` to `AggregateRuntime` / `KafkaAggregateRuntime`

**Scope:** `main/runtime` module.

1. Add `String getDescription()` to the `AggregateRuntime` interface (returns `null` when no
   description is set).
2. Implement `getDescription()` in `KafkaAggregateRuntime`:
   - The aggregate class is stored in the `aggregateClass` field.
   - Read `@AggregateInfo.description()` (or `@AgenticAggregateInfo.description()` for agentic
     runtimes) and normalize to `null` when blank.

---

### Phase 3 – Populate Descriptions in `AkcesAggregateController` (Standard Aggregates)

**Scope:** `main/runtime` module, `AkcesAggregateController.publishControlRecord()`.

Update the three construction sites:

1. **`AggregateServiceRecord`** – pass `aggregateRuntime.getDescription()`.
2. **`AggregateServiceCommandType`** (produced-commands stream) – extract description from
   `commandType.typeClass().getAnnotation(CommandInfo.class)`.
3. **`AggregateServiceDomainEventType`** (produced-events stream) – extract description from
   `domainEventType.typeClass().getAnnotation(DomainEventInfo.class)`.
4. **`AggregateServiceDomainEventType`** (consumed-events stream) – same as above.

---

### Phase 4 – Populate Descriptions in `AkcesAgenticAggregateController` (Agentic Aggregates)

**Scope:** `main/agentic` module, `AkcesAgenticAggregateController.publishControlRecord()`.

Mirrors Phase 3 for the agentic controller. Built-in command/event types already have `@CommandInfo`
/ `@DomainEventInfo` annotations with their descriptions (or the defaults), so they will be handled
uniformly.

---

### Phase 5 – Update Tests

**Scope:** All modules with tests that construct these records directly.

Files to update (identified by static analysis):

| File | Change |
|---|---|
| `main/runtime/src/test/.../control/AkcesAggregateControllerTests.java` | Add `null` description argument(s) to record constructors |
| `main/runtime/src/test/.../RuntimeTests.java` | Same |
| `main/client/src/test/.../AkcesClientTests.java` | Same |
| `main/query-support/src/test/.../TestUtils.java` | JSON strings are deserialized — no change to JSON needed; new field simply deserializes as `null`. Verify deserialization still works. |

Tests that use JSON deserialization (query-support `TestUtils.java`) require no JSON changes;
legacy JSON without a `description` field will deserialize correctly with `description = null`.

---

## GitHub Issues

The following issues need to be created (in dependency order), with the last one being the tracking
issue:

1. **Issue – Phase 1**: "Add `description` field to `AggregateServiceRecord` and contained types"
   - Scope: `main/shared`
   - No dependencies

2. **Issue – Phase 2**: "Add `getDescription()` to `AggregateRuntime` interface and
   `KafkaAggregateRuntime`"
   - Scope: `main/runtime`
   - Depends on: Issue 1

3. **Issue – Phase 3**: "Populate `description` in `AkcesAggregateController.publishControlRecord()`"
   - Scope: `main/runtime`
   - Depends on: Issues 1, 2

4. **Issue – Phase 4**: "Populate `description` in `AkcesAgenticAggregateController.publishControlRecord()`"
   - Scope: `main/agentic`
   - Depends on: Issues 1, 2

5. **Issue – Phase 5**: "Update tests for `AggregateServiceRecord` description field"
   - Scope: `main/runtime`, `main/client`, `main/agentic`
   - Depends on: Issues 1, 3, 4

6. **Tracking Issue**: "Propagate `*Info` annotation descriptions into `AggregateServiceRecord`"
   - Links all above issues
   - Describes the overall goal for the Agentic Layer
