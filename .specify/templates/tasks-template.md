---

description: "Task list template for feature implementation"
---

# Tasks: [FEATURE NAME]

**Input**: Design documents from `/specs/[###-feature-name]/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: The examples below include test tasks. Tests are OPTIONAL - only include them if explicitly requested in the feature specification.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Module root**: `main/[module]/src/main/java/org/elasticsoftware/akces/[package]/`
- **Test root**: `main/[module]/src/test/java/org/elasticsoftware/[package]/`
- **Commands**: `…/commands/[Name]Command.java`
- **Events**: `…/events/[Name]Event.java`, `…/events/[Name]ErrorEvent.java`
- **State**: `…/[Aggregate]State.java` (Java record implementing `AggregateState`)
- **Aggregate**: `…/[Name]Aggregate.java` (implements `Aggregate<T>`)
- **Query model**: `main/query-support/src/main/java/…/[Name]QueryModel.java`
- **Database model**: `main/query-support/src/main/java/…/[Name]DatabaseModel.java`
- **Test command**: `cd main/[module] && mvn test`
- **Full build**: `mvn clean install` from repository root

<!-- 
  ============================================================================
  IMPORTANT: The tasks below are SAMPLE TASKS for illustration purposes only.
  
  The /speckit.tasks command MUST replace these with actual tasks based on:
  - User stories from spec.md (with their priorities P1, P2, P3...)
  - Feature requirements from plan.md
  - Entities from data-model.md
  - Endpoints from contracts/
  
  Tasks MUST be organized by user story so each story can be:
  - Implemented independently
  - Tested independently
  - Delivered as an MVP increment
  
  DO NOT keep these sample tasks in the generated tasks.md file.
  ============================================================================
-->

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [ ] T001 Identify target module(s) per plan.md Structure Decision
- [ ] T002 [P] Add new `pom.xml` and register in parent if a new Maven module is needed
- [ ] T003 [P] Register new types in `BUILTIN_EVENT_TYPES` / `BUILTIN_COMMAND_TYPES` if they are framework built-ins

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

Examples of foundational tasks (adjust based on your project):

- [ ] T004 Define `@DomainEventInfo` event record(s) in `…/events/`
- [ ] T005 [P] Define `@CommandInfo` command record(s) in `…/commands/`
- [ ] T006 [P] Define or update `@AggregateStateInfo` state record in aggregate module
- [ ] T007 Register new event/command types in schema registry (update `@AggregateInfo` produces/errors lists)
- [ ] T008 [P] Write `@UpcastingHandler` if state or event schema version is incremented
- [ ] T009 Verify `@AggregateIdentifier` is present on all new command and event records

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - [Title] (Priority: P1) 🎯 MVP

**Goal**: [Brief description of what this story delivers]

**Independent Test**: [How to verify this story works on its own]

### Tests for User Story 1 (OPTIONAL - only if tests requested) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T010 [P] [US1] Unit test: `@CommandHandler` in `[Aggregate]Test.java` verifies correct events emitted (`cd main/[module] && mvn test`)
- [ ] T011 [P] [US1] Integration test: full command→event→state flow with Testcontainers Kafka in `[Feature]IntegrationTest.java`

### Implementation for User Story 1

- [ ] T012 [P] [US1] Implement `@CommandHandler` method in `[Name]Aggregate` — emits domain event or error event; no exceptions thrown
- [ ] T013 [P] [US1] Implement `@EventSourcingHandler` method in `[Name]Aggregate` — returns new immutable state record
- [ ] T014 [US1] Update `@QueryModelEventHandler` in affected `QueryModel` (depends on T012)
- [ ] T015 [US1] Update `@DatabaseModelEventHandler` in affected `DatabaseModel` if applicable
- [ ] T016 [US1] Annotate any new PII fields with `@PIIData` in state/event records
- [ ] T017 [US1] Run `cd main/[module] && mvn test` — confirm all tests pass

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently

---

## Phase 4: User Story 2 - [Title] (Priority: P2)

**Goal**: [Brief description of what this story delivers]

**Independent Test**: [How to verify this story works on its own]

### Tests for User Story 2 (OPTIONAL - only if tests requested) ⚠️

- [ ] T018 [P] [US2] Unit test: `@CommandHandler` for user story 2 in `[Aggregate]Test.java`
- [ ] T019 [P] [US2] Integration test for user story 2 flow with Testcontainers Kafka

### Implementation for User Story 2

- [ ] T020 [P] [US2] Implement `@CommandHandler` for user story 2 in `[Name]Aggregate`
- [ ] T021 [US2] Implement `@EventSourcingHandler` for new event(s)
- [ ] T022 [US2] Update query/database models for new events
- [ ] T023 [US2] Run `cd main/[module] && mvn test` — confirm all tests pass

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently

---

## Phase 5: User Story 3 - [Title] (Priority: P3)

**Goal**: [Brief description of what this story delivers]

**Independent Test**: [How to verify this story works on its own]

### Tests for User Story 3 (OPTIONAL - only if tests requested) ⚠️

- [ ] T024 [P] [US3] Unit test: `@CommandHandler` for user story 3
- [ ] T025 [P] [US3] Integration test for user story 3 flow

### Implementation for User Story 3

- [ ] T026 [P] [US3] Implement `@CommandHandler` for user story 3
- [ ] T027 [US3] Implement `@EventSourcingHandler` for new event(s)
- [ ] T028 [US3] Update query/database models; run `cd main/[module] && mvn test`

**Checkpoint**: All user stories should now be independently functional

---

[Add more user story phases as needed, following the same pattern]

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] TXXX [P] Documentation updates (README or FRAMEWORK_OVERVIEW.md if API surface changed)
- [ ] TXXX Code cleanup and refactoring; ensure all handlers remain non-blocking
- [ ] TXXX Run `mvn clean install` from repository root — full build must pass
- [ ] TXXX Verify Schema Registry compatibility (no `forceRegister` in production config)
- [ ] TXXX Confirm no raw PII fields lack `@PIIData` annotation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Final Phase)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - May integrate with US1 but should be independently testable
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - May integrate with US1/US2 but should be independently testable

### Within Each User Story

- Tests (if included) MUST be written and FAIL before implementation
- Models before services
- Services before endpoints
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

- All Setup tasks marked [P] can run in parallel
- All Foundational tasks marked [P] can run in parallel (within Phase 2)
- Once Foundational phase completes, all user stories can start in parallel (if team capacity allows)
- All tests for a user story marked [P] can run in parallel
- Models within a story marked [P] can run in parallel
- Different user stories can be worked on in parallel by different team members

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together (if tests requested):
Task: "Contract test for [endpoint] in tests/contract/test_[name].py"
Task: "Integration test for [user journey] in tests/integration/test_[name].py"

# Launch all models for User Story 1 together:
Task: "Create [Entity1] model in src/models/[entity1].py"
Task: "Create [Entity2] model in src/models/[entity2].py"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Test User Story 1 independently
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 2 → Test independently → Deploy/Demo
4. Add User Story 3 → Test independently → Deploy/Demo
5. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1
   - Developer B: User Story 2
   - Developer C: User Story 3
3. Stories complete and integrate independently

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence
