# Akces Event Model Schema vs Original Event-Modeling Spec

This document describes the differences between the Akces augmented event-model schema
([`akces-event-model.schema.json`](src/main/resources/akces-event-model.schema.json)) and the
original [event-modeling specification schema](https://github.com/dilgerma/event-modeling-spec/blob/main/eventmodeling.schema.json)
by Martin Dilger.

The Akces schema is a **strict superset** of the original — it preserves every definition from the
original schema unchanged (Slice, Element, ScreenImage, Table, Specification, SpecificationStep,
Comment, Actor, Dependency) and extends it with Akces Framework-specific properties for aggregate
code generation.

---

## Root-Level Changes

| Aspect | Original Schema | Akces Schema |
|---|---|---|
| **title** | *(none)* | `"Akces Event Model Definition"` |
| **description** | *(none)* | Describes the Akces extension of the event-modeling spec |
| **properties** | `slices` | `packageName`, `aggregateConfig`, `slices` |
| **required** | `["slices"]` | `["packageName", "aggregateConfig", "slices"]` |

### New root-level property: `packageName`

```json
"packageName": {
  "type": "string",
  "description": "The base Java package for generated code"
}
```

Specifies the base Java package name used in the JSON definition. The code generator uses its own
`basePackage` constructor parameter for the generated `package` declarations and directory structure.

### New root-level property: `aggregateConfig`

```json
"aggregateConfig": {
  "type": "object",
  "description": "Per-aggregate configuration keyed by aggregate name",
  "additionalProperties": { "$ref": "#/$defs/AggregateConfig" }
}
```

A map keyed by aggregate name (e.g., `"Account"`, `"Wallet"`) where each value is an
`AggregateConfig` object containing Akces-specific metadata for that aggregate.

---

## New Definition: `AggregateConfig`

Akces-specific configuration for an aggregate, used to generate the state record and aggregate class
annotations.

| Property | Type | Required | Description |
|---|---|---|---|
| `indexed` | `boolean` | No | Whether the aggregate is indexed (maps to `@AggregateInfo(indexed = true)`) |
| `indexName` | `string` | No | The index name (maps to `@AggregateInfo(indexName = "...")`) |
| `generateGDPRKeyOnCreate` | `boolean` | No | Whether to generate a GDPR encryption key on aggregate creation (maps to `@AggregateInfo(generateGDPRKeyOnCreate = true)`) |
| `stateVersion` | `integer` (≥ 1) | No | The version of the aggregate state (maps to `@AggregateStateInfo(version = ...)`) |
| `stateFields` | `StateField[]` | **Yes** | The fields of the aggregate state record |

**Example:**

```json
"aggregateConfig": {
  "Account": {
    "indexed": true,
    "indexName": "Users",
    "generateGDPRKeyOnCreate": true,
    "stateVersion": 1,
    "stateFields": [
      { "name": "userId", "type": "String", "idAttribute": true },
      { "name": "firstName", "type": "String", "piiData": true },
      { "name": "email", "type": "String", "piiData": true }
    ]
  }
}
```

---

## New Definition: `StateField`

Defines a field in the aggregate state record with Akces-specific metadata for PII marking and
identifier designation.

| Property | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | **Yes** | The field name |
| `type` | `string` (enum) | **Yes** | The field type — same enum as `Field.type`: `String`, `Boolean`, `Double`, `Decimal`, `Long`, `Custom`, `Date`, `DateTime`, `UUID`, `Int` |
| `idAttribute` | `boolean` | No | Whether this field is the aggregate identifier (maps to `@AggregateIdentifier`) |
| `piiData` | `boolean` | No | Whether this field contains PII data (maps to `@PIIData` annotation) |
| `optional` | `boolean` | No | Whether the field is optional/nullable (maps to `@Nullable` instead of `@NotNull`) |

---

## Modified Definition: `Field`

The `Field` definition (used in Element fields for commands, events, read models, etc.) has one
addition compared to the original schema:

| Property | Type | Description |
|---|---|---|
| **`piiData`** *(new)* | `boolean` | Whether this field contains PII data requiring GDPR protection |

All other `Field` properties are unchanged from the original schema: `name`, `type`, `example`,
`subfields`, `mapping`, `optional`, `technicalAttribute`, `generated`, `idAttribute`, `schema`,
`cardinality`.

When `piiData: true` is set on a command or event field, the code generator emits the `@PIIData`
annotation on the corresponding record parameter, ensuring GDPR-sensitive fields are consistently
marked across commands, events, and state records.

---

## Unchanged Definitions

The following definitions are **identical** to the original event-modeling spec schema:

- **`Slice`** — Represents a vertical slice with commands, events, read models, screens, processors, tables, specifications, and actors
- **`Element`** — Represents a command, event, read model, screen, or automation element with fields and dependencies
- **`ScreenImage`** — A screen mockup image reference
- **`Table`** — A data table definition with fields
- **`Specification`** — A BDD-style specification with given/when/then steps
- **`SpecificationStep`** — A step in a specification
- **`Comment`** — A comment with a description
- **`Actor`** — An actor with authentication requirements
- **`Dependency`** — An inbound or outbound dependency between elements

---

## Summary of Additions

| Addition | Location | Purpose |
|---|---|---|
| `packageName` | Root property | Java package name declared in the definition |
| `aggregateConfig` | Root property | Per-aggregate Akces configuration (indexing, GDPR, state fields) |
| `AggregateConfig` | New `$defs` entry | Aggregate-level metadata: indexing, GDPR key generation, state definition |
| `StateField` | New `$defs` entry | State record field with PII and identifier markers |
| `piiData` | Added to `Field` | Marks command/event fields as containing PII data |
