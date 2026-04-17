# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]
**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

[Extract from feature spec: primary requirement + technical approach from research]

## Technical Context

<!--
  ACTION REQUIRED: Replace the content in this section with the technical details
  for the project. The structure here is presented in advisory capacity to guide
  the iteration process.
-->

**Language/Version**: Java 25  
**Primary Dependencies**: Spring Boot 4.x, Apache Kafka (kafka-clients/kafka-streams/spring-kafka), RocksDB, victools (JSON Schema), Confluent Schema Registry  
**AI/Agent Layer**: Embabel Agent Framework (agentic module only)  
**Storage**: RocksDB (aggregate state), Kafka (event log), JDBC/JPA (query/database models)  
**Testing**: JUnit Jupiter + Mockito + AssertJ + Testcontainers (Kafka); run with `mvn test`  
**Target Platform**: JVM / Kubernetes (AggregateService, CommandService, QueryService CRDs)  
**Performance Goals**: [domain-specific, e.g., throughput per Kafka partition or NEEDS CLARIFICATION]  
**Constraints**: All handlers must be non-blocking and deterministic; schema changes must be backward-compatible  
**Scale/Scope**: [e.g., number of aggregates, event volume, or NEEDS CLARIFICATION]

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

[Gates determined based on constitution file]

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)
<!--
  ACTION REQUIRED: Replace the placeholder tree below with the concrete layout
  for this feature. Map new types to the correct Akces Framework modules.
  Delete unused options and expand the chosen structure with real paths.
-->

```text
# Option A: New feature within an existing module (MOST COMMON)
main/[module]/src/main/java/org/elasticsoftware/akces/[package]/
├── [FeatureName]Aggregate.java          # or extends existing aggregate
├── commands/
│   └── [FeatureName]Command.java
├── events/
│   ├── [FeatureName]Event.java
│   └── [FeatureName]ErrorEvent.java
└── state/
    └── [FeatureName]State.java          # or updated existing *State record

main/[module]/src/test/java/org/elasticsoftware/[package]/
└── [FeatureName]Test.java               # JUnit Jupiter + Testcontainers

# Option B: New cross-cutting capability spanning multiple modules
main/api/src/main/java/org/elasticsoftware/akces/
└── [new-package]/                        # New interface/annotation (no framework deps)

main/shared/src/main/java/org/elasticsoftware/akces/
└── [new-package]/                        # New shared utility/protocol type

main/runtime/src/main/java/org/elasticsoftware/akces/
└── [new-package]/                        # Runtime implementation

# Option C: New module (requires new pom.xml + parent registration)
main/[new-module]/
├── pom.xml
└── src/main/java/org/elasticsoftware/akces/[new-module]/

# Option D: Agentic feature (uses Embabel Agent Framework)
main/agentic/src/main/java/org/elasticsoftware/akces/agentic/
├── commands/[FeatureName]Command.java
├── events/[FeatureName]Event.java
└── runtime/[FeatureName]Handler.java
```

**Structure Decision**: [Document the selected option and list the real file paths for this feature]

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |
