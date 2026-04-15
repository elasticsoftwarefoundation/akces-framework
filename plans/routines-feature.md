# Routines Feature Plan

## Overview

The **Routines** feature brings Claude Code-style autonomous task execution to the Akces Framework. A Routine is a saved configuration — a prompt, one or more repository references, and a set of MCP connectors — that can be triggered automatically and executed by an `AgenticAggregate`. Unlike ad-hoc `AssignTask` commands which are fire-and-forget, a Routine is a **persistent, reusable, trigger-driven** configuration that autonomously initiates tasks on a schedule, via an HTTP API call, or in response to GitHub repository events.

### Inspiration

This feature is modeled after [Claude Code Routines](https://code.claude.com/docs/en/routines), which package a prompt + repos + connectors into a reusable unit with three trigger types:

| Trigger Type | Description |
|-------------|-------------|
| **Scheduled** | Run on a recurring cadence (hourly, daily, weekdays, weekly, custom cron) |
| **API** | Trigger on demand via HTTP POST with an optional `text` payload |
| **GitHub** | Run automatically in response to GitHub repository events (pull requests, releases) |

A single Routine can combine multiple triggers. Each trigger execution creates a new `AssignTask` command, which the `AgenticAggregate` processes through its Embabel agent loop.

### Mapping to Akces Concepts

| Claude Code Concept | Akces Mapping |
|---------------------|---------------|
| Routine | `Routine` aggregate (new, regular multi-partition aggregate) |
| Routine run / session | `AssignTaskCommand` sent to an `AgenticAggregate` |
| Scheduled trigger | Kubernetes `CronJob` or in-process scheduler |
| API trigger | REST endpoint on the Command Service |
| GitHub trigger | GitHub Webhooks → Reflector or dedicated webhook service |
| Repositories | Git clone configuration in `AgenticAggregateSpec` sidecars |
| Connectors (MCP) | MCP server sidecars already supported by the operator |
| Prompt | Stored as part of `Routine` aggregate state |

### Key Architectural Decision: Routine as a Regular Aggregate

The `Routine` is modeled as a **regular Akces Aggregate** (not an `AgenticAggregate`) because:

1. **Routine management is deterministic** — creating, updating, pausing, and deleting routines requires no LLM reasoning
2. **Multi-partition scalable** — many routines can exist and be managed in parallel across partitions
3. **Separation of concerns** — the Routine aggregate manages *configuration and triggers*, while the `AgenticAggregate` handles *execution*
4. **Event-driven trigger→execution bridge** — when a trigger fires, the Routine emits a `RoutineTriggeredEvent`, which a Process Manager translates into an `AssignTaskCommand` on the target `AgenticAggregate`

This follows the existing pattern of Process Managers coordinating workflows across aggregates.

---

## Domain Model

### Routine Aggregate

```
Routine (Aggregate)
├── Commands
│   ├── CreateRoutineCommand          (create=true)
│   ├── UpdateRoutineCommand
│   ├── DeleteRoutineCommand
│   ├── PauseRoutineCommand
│   ├── ResumeRoutineCommand
│   ├── AddTriggerCommand
│   ├── RemoveTriggerCommand
│   ├── FireRoutineCommand            (API trigger)
│   └── RegenerateApiTokenCommand
│
├── Events
│   ├── RoutineCreatedEvent           (create=true)
│   ├── RoutineUpdatedEvent
│   ├── RoutineDeletedEvent
│   ├── RoutinePausedEvent
│   ├── RoutineResumedEvent
│   ├── TriggerAddedEvent
│   ├── TriggerRemovedEvent
│   ├── RoutineTriggeredEvent         (← THE key event, consumed by ProcessManager)
│   ├── ApiTokenRegeneratedEvent
│   └── RoutineTriggerFailedEvent     (error event)
│
└── State: RoutineState
    ├── routineId: String
    ├── name: String
    ├── prompt: String
    ├── targetAgenticAggregate: String
    ├── repositories: List<RepositoryRef>
    ├── triggers: List<TriggerConfig>
    ├── paused: boolean
    ├── apiToken: String (hashed)
    ├── createdAt: Instant
    └── updatedAt: Instant
```

### Trigger Configuration (Value Objects)

```java
sealed interface TriggerConfig permits ScheduleTrigger, ApiTrigger, GitHubTrigger {
    String triggerId();
    String type();
}

record ScheduleTrigger(
    String triggerId,
    String cronExpression,    // e.g. "0 0 9 * * MON-FRI" for weekdays at 9am
    String timezone           // e.g. "Europe/Amsterdam"
) implements TriggerConfig { ... }

record ApiTrigger(
    String triggerId,
    String tokenHash          // bcrypt hash of the bearer token
) implements TriggerConfig { ... }

record GitHubTrigger(
    String triggerId,
    String repositoryOwner,
    String repositoryName,
    GitHubEventType eventType,     // PULL_REQUEST or RELEASE
    String eventAction,            // e.g. "opened", "closed", "*" for all
    List<GitHubFilter> filters     // optional PR filters
) implements TriggerConfig { ... }
```

### GitHub Event Filters (Value Objects)

```java
record GitHubFilter(
    GitHubFilterField field,       // AUTHOR, TITLE, BODY, BASE_BRANCH, HEAD_BRANCH, LABELS, IS_DRAFT, IS_MERGED, FROM_FORK
    GitHubFilterOperator operator, // EQUALS, CONTAINS, STARTS_WITH, IS_ONE_OF, IS_NOT_ONE_OF, MATCHES_REGEX
    String value
) { ... }

enum GitHubEventType { PULL_REQUEST, RELEASE }

enum GitHubFilterField {
    AUTHOR, TITLE, BODY, BASE_BRANCH, HEAD_BRANCH, LABELS, IS_DRAFT, IS_MERGED, FROM_FORK
}

enum GitHubFilterOperator {
    EQUALS, CONTAINS, STARTS_WITH, IS_ONE_OF, IS_NOT_ONE_OF, MATCHES_REGEX
}
```

### RoutineExecution Process Manager

A Process Manager that bridges the `RoutineTriggeredEvent` from the Routine aggregate to an `AssignTaskCommand` on the target `AgenticAggregate`:

```
RoutineExecutionProcessManager
├── Listens to: RoutineTriggeredEvent
├── Emits: RoutineExecutionStartedEvent
├── Sends: AssignTaskCommand (to target AgenticAggregate)
│
├── Listens to: AgentTaskFinishedEvent (external, from AgenticAggregate)
├── Emits: RoutineExecutionFinishedEvent
│
└── State: RoutineExecutionState
    ├── routineId: String
    ├── executionId: String
    ├── triggerType: String
    ├── triggerPayload: String (optional, e.g. API text or GitHub webhook body)
    ├── agentProcessId: String
    ├── startedAt: Instant
    └── finishedAt: Instant
```

---

## Phase 1: Core Domain Model (`api` module)

### What

Define the core value objects and sealed interfaces for Routine trigger configurations in the `api` module, so they can be referenced by both the Routine aggregate and any service that needs to work with trigger types.

### Module

`main/api` — package `org.elasticsoftware.akces.routines`

### New Files

| File | Description |
|------|-------------|
| `TriggerConfig.java` | Sealed interface for trigger configurations with `triggerId()` and `type()` methods, Jackson polymorphic serialization |
| `ScheduleTrigger.java` | Record: `triggerId`, `cronExpression`, `timezone` |
| `ApiTrigger.java` | Record: `triggerId`, `tokenHash` |
| `GitHubTrigger.java` | Record: `triggerId`, `repositoryOwner`, `repositoryName`, `eventType`, `eventAction`, `filters` |
| `GitHubEventType.java` | Enum: `PULL_REQUEST`, `RELEASE` |
| `GitHubFilter.java` | Record: `field`, `operator`, `value` |
| `GitHubFilterField.java` | Enum: `AUTHOR`, `TITLE`, `BODY`, `BASE_BRANCH`, `HEAD_BRANCH`, `LABELS`, `IS_DRAFT`, `IS_MERGED`, `FROM_FORK` |
| `GitHubFilterOperator.java` | Enum: `EQUALS`, `CONTAINS`, `STARTS_WITH`, `IS_ONE_OF`, `IS_NOT_ONE_OF`, `MATCHES_REGEX` |
| `RepositoryRef.java` | Record: `owner`, `name`, `defaultBranch`, `allowUnrestrictedBranchPush` |

### Design Decisions

- **Sealed interface with Jackson `@JsonTypeInfo`** for `TriggerConfig` — follows the same pattern as `RequestingParty`
- **Placed in `api` module** — these are core domain types referenced by commands, events, and state across modules
- **No framework dependency on GitHub libraries** — the `GitHubTrigger` is a pure data type; the actual GitHub integration is in a separate module

---

## Phase 2: Routine Aggregate (`main/routines` — new module)

### What

Create a new `main/routines` module containing the Routine aggregate with all commands, events, and state. This is a regular (non-agentic) aggregate.

### Module

`main/routines` — new Maven module under `main/`

### New Package

`org.elasticsoftware.akces.routines`

### New Files — Commands

| File | Description |
|------|-------------|
| `CreateRoutineCommand.java` | Create a new routine with name, prompt, target agentic aggregate, repositories, initial triggers |
| `UpdateRoutineCommand.java` | Update routine name, prompt, or target agentic aggregate |
| `DeleteRoutineCommand.java` | Soft-delete a routine |
| `PauseRoutineCommand.java` | Pause all triggers on a routine |
| `ResumeRoutineCommand.java` | Resume all triggers on a routine |
| `AddTriggerCommand.java` | Add a trigger configuration to a routine |
| `RemoveTriggerCommand.java` | Remove a trigger by `triggerId` |
| `FireRoutineCommand.java` | Manually fire a routine (API trigger), with optional `text` payload |
| `RegenerateApiTokenCommand.java` | Regenerate the API bearer token for a routine |

### New Files — Events

| File | Description |
|------|-------------|
| `RoutineCreatedEvent.java` | Emitted when a routine is created |
| `RoutineUpdatedEvent.java` | Emitted when routine config is updated |
| `RoutineDeletedEvent.java` | Emitted when a routine is deleted |
| `RoutinePausedEvent.java` | Emitted when a routine is paused |
| `RoutineResumedEvent.java` | Emitted when a routine is resumed |
| `TriggerAddedEvent.java` | Emitted when a trigger is added |
| `TriggerRemovedEvent.java` | Emitted when a trigger is removed |
| `RoutineTriggeredEvent.java` | **Key event** — emitted when a trigger fires; consumed by the Process Manager |
| `ApiTokenRegeneratedEvent.java` | Emitted when the API token is regenerated |
| `RoutineTriggerFailedEvent.java` | Error event for validation failures |

### New Files — State & Aggregate

| File | Description |
|------|-------------|
| `RoutineState.java` | Record implementing `AggregateState` with all routine configuration |
| `RoutineAggregate.java` | Aggregate implementation with command handlers and event sourcing handlers |

### Design Decisions

- **Separate module** — routines are a distinct bounded context from the agentic runtime; keeping them in a separate module allows independent deployment and testing
- **Regular aggregate** — deterministic CRUD operations, no LLM reasoning needed
- **`FireRoutineCommand`** — serves as the entry point for both API triggers and scheduled triggers; the scheduler sends `FireRoutineCommand` with `triggerType=SCHEDULE`, the API endpoint sends it with `triggerType=API`
- **`RoutineTriggeredEvent`** — the single output event that the Process Manager consumes; it contains the resolved prompt (with any trigger-specific context like the API `text` payload or GitHub webhook body injected into the prompt)
- **API token management** — tokens are generated as UUIDs, hashed with bcrypt before storage; the raw token is only returned once upon creation/regeneration

---

## Phase 3: Routine Execution Process Manager (`main/routines` module)

### What

Implement the Process Manager that translates `RoutineTriggeredEvent` into `AssignTaskCommand` on the target `AgenticAggregate`, and tracks execution lifecycle.

### Module

`main/routines` — same module as the Routine aggregate

### New Files

| File | Description |
|------|-------------|
| `RoutineExecutionProcessManager.java` | Process Manager that bridges routine triggers to agentic aggregate task assignments |
| `RoutineExecutionState.java` | Process state record tracking execution lifecycle |
| `RoutineExecutionStartedEvent.java` | Emitted when the Process Manager sends the `AssignTaskCommand` |
| `RoutineExecutionFinishedEvent.java` | Emitted when the `AgentTaskFinishedEvent` is received |

### Behavior

1. **On `RoutineTriggeredEvent`**: 
   - Creates a new execution with a unique `executionId`
   - Constructs an `AssignTaskCommand` with:
     - `agenticAggregateId` = the routine's `targetAgenticAggregate`
     - `taskDescription` = the routine's `prompt` + trigger context (API text, GitHub event payload)
     - `requestingParty` = `AgentRequestingParty` with `agentName = "RoutineScheduler"` and `role = "routine"`
     - `taskMetadata` = `{ "routineId": "...", "executionId": "...", "triggerType": "...", "triggerId": "..." }`
   - Sends the command via `CommandBus`
   - Emits `RoutineExecutionStartedEvent`

2. **On `AgentTaskFinishedEvent`** (external event from the target AgenticAggregate):
   - Matches by `executionId` in `taskMetadata`
   - Emits `RoutineExecutionFinishedEvent` with status

---

## Phase 4: Schedule Trigger — Kubernetes CronJob Integration (`services/operator`)

### What

Extend the Akces Operator to create Kubernetes `CronJob` resources for routines with schedule triggers. Each `CronJob` fires a `FireRoutineCommand` to the Routine aggregate's command topic via a lightweight Kafka producer container.

### Module

`services/operator`

### Changes

| File | Change |
|------|--------|
| `RoutineResource.java` (new) | Kubernetes Custom Resource for `Routine` (CRD) |
| `RoutineSpec.java` (new) | Spec with routine ID, schedule triggers, and target aggregate reference |
| `RoutineReconciler.java` (new) | Reconciler that creates/updates/deletes `CronJob` resources based on `ScheduleTrigger` configs |
| `CronJobDependentResource.java` (new) | Dependent resource that generates `CronJob` manifests |

### Design

The CronJob runs a minimal container that:
1. Serializes a `FireRoutineCommand` as a `CommandRecord`
2. Sends it to the `Routine-Commands` Kafka topic with the routine's ID as the key
3. Exits (CronJob `restartPolicy: OnFailure`)

The container image is a lightweight JVM application (or even a shell script with `kafka-console-producer`) that takes the routine ID and trigger metadata as environment variables.

### Alternative: In-Process Scheduler

Instead of Kubernetes CronJobs, the schedule trigger could be implemented as an in-process scheduler within the Command Service or a dedicated "Routine Scheduler Service". This would use Spring's `@Scheduled` or a Quartz scheduler, reading routine configs from a Query Model and firing `FireRoutineCommand`s.

**Trade-offs:**

| Approach | Pros | Cons |
|----------|------|------|
| Kubernetes CronJob | Cloud-native, survives process restarts, native cron support, visible in `kubectl` | Requires operator changes, one CronJob per schedule, K8s overhead |
| In-process scheduler | Simpler implementation, no operator changes, single process | Requires HA (leader election), state synchronization, not visible in K8s |

**Recommendation**: Start with the Kubernetes CronJob approach as it aligns with the operator-centric deployment model and provides built-in resilience. The in-process approach can be added later as a non-Kubernetes alternative.

---

## Phase 5: API Trigger — REST Endpoint (`main/client` or dedicated module)

### What

Add a REST endpoint that accepts HTTP POST requests with a bearer token and fires a `FireRoutineCommand` on the Routine aggregate. This is the API trigger equivalent of Claude Code's `/fire` endpoint.

### Module

`main/client` (extend the existing Command Service) or a new `main/routines-api` module

### New Files

| File | Description |
|------|-------------|
| `RoutineApiController.java` | Spring REST controller with `POST /api/v1/routines/{routineId}/fire` |
| `RoutineApiTokenValidator.java` | Service that validates bearer tokens against the stored token hash |
| `RoutineFireRequest.java` | Request body record: `text` (optional freeform context) |
| `RoutineFireResponse.java` | Response record: `executionId`, `status` |

### Endpoint Design

```
POST /api/v1/routines/{routineId}/fire
Authorization: Bearer <token>
Content-Type: application/json

{
  "text": "Optional trigger-specific context..."
}

→ 200 OK
{
  "executionId": "exec_01ABCDEF...",
  "routineId": "routine_01ABCDEF...",
  "status": "TRIGGERED"
}
```

### Security

- Bearer token validation: the controller hashes the incoming token and compares it against the stored `tokenHash` in the Routine's state (via a Query Model)
- Rate limiting: optional, configurable per routine
- The token is scoped to a single routine — it cannot trigger other routines

---

## Phase 6: GitHub Trigger — Webhook Integration (new `main/routines-github` module)

### What

Implement GitHub webhook reception and event filtering. When a webhook event matches a routine's `GitHubTrigger` configuration, the service fires a `FireRoutineCommand`.

### Module

New module: `main/routines-github`

### New Files

| File | Description |
|------|-------------|
| `GitHubWebhookController.java` | Spring REST controller for `POST /api/v1/webhooks/github` |
| `GitHubWebhookValidator.java` | Validates GitHub webhook signatures (`X-Hub-Signature-256`) |
| `GitHubEventMatcher.java` | Matches incoming GitHub events against stored `GitHubTrigger` configurations |
| `GitHubFilterEvaluator.java` | Evaluates filter conditions (equals, contains, starts_with, regex, etc.) |
| `GitHubPullRequestPayload.java` | Record for deserialized PR webhook payload |
| `GitHubReleasePayload.java` | Record for deserialized release webhook payload |

### Flow

1. GitHub sends a webhook to `POST /api/v1/webhooks/github`
2. `GitHubWebhookValidator` verifies the `X-Hub-Signature-256` using the shared webhook secret
3. The controller parses the `X-GitHub-Event` header and payload
4. `GitHubEventMatcher` queries a Query Model to find all routines with matching `GitHubTrigger` configurations
5. For each matching routine, `GitHubFilterEvaluator` checks all filter conditions
6. For each passing routine, a `FireRoutineCommand` is sent with the GitHub event payload as `triggerPayload`

### GitHub Filter Evaluation

The `GitHubFilterEvaluator` implements all filter operators:

| Operator | Implementation |
|----------|---------------|
| `EQUALS` | `field.equals(value)` |
| `CONTAINS` | `field.contains(value)` |
| `STARTS_WITH` | `field.startsWith(value)` |
| `IS_ONE_OF` | `Set.of(value.split(",")).contains(field)` |
| `IS_NOT_ONE_OF` | `!Set.of(value.split(",")).contains(field)` |
| `MATCHES_REGEX` | `Pattern.compile(value).matcher(field).matches()` (full match) |

### Webhook Registration

GitHub webhooks need to be registered on the repository. This can be done:
1. **Manually** — the user configures the webhook URL and secret in their GitHub repository settings
2. **Automatically via GitHub MCP Server** — the `AgenticAggregate` that processes routines can use the GitHub MCP server sidecar to register webhooks programmatically

**Recommendation**: Start with manual webhook registration and document the setup. Automatic registration can be added later.

---

## Phase 7: Routine Query Model (`main/routines` module)

### What

Create a Query Model that provides read-optimized access to routine configurations. This is needed by:
- The API trigger endpoint (to validate tokens)
- The GitHub webhook service (to find matching routines)
- The schedule trigger (to read cron expressions)
- Any UI or dashboard

### Module

`main/routines` — same module

### New Files

| File | Description |
|------|-------------|
| `RoutineQueryModel.java` | Query Model with event handlers for all Routine events |
| `RoutineQueryModelState.java` | Denormalized state record optimized for lookups |
| `RoutineExecutionQueryModel.java` | Query Model tracking execution history |
| `RoutineExecutionQueryModelState.java` | Execution history state |

### Lookup Patterns

- **By routine ID** — standard aggregate ID lookup
- **By API token hash** — for API trigger validation (indexed)
- **By GitHub repository + event type** — for webhook matching (indexed)
- **By schedule status** — for the scheduler to find active scheduled routines

---

## Phase 8: Operator CRD and Deployment (`services/operator`)

### What

Define the Kubernetes Custom Resource Definition (CRD) for deploying Routine-related services, and extend the operator to manage the complete Routine lifecycle.

### Module

`services/operator`

### New CRD: `RoutineService`

```yaml
apiVersion: akces.elasticsoftware.org/v1
kind: RoutineService
metadata:
  name: routine-service
spec:
  image: ghcr.io/elasticsoftwarefoundation/akces-routine-service:0.12.0
  applicationName: "Routine Service"
  replicas: 2
  resources:
    requests:
      cpu: "500m"
      memory: "512Mi"
  webhookIngress:
    enabled: true
    host: webhooks.example.com
    path: /api/v1/webhooks/github
    tls:
      secretName: webhook-tls
  apiIngress:
    enabled: true
    host: api.example.com
    path: /api/v1/routines
    tls:
      secretName: api-tls
```

### New Files

| File | Description |
|------|-------------|
| `RoutineServiceResource.java` | Kubernetes Custom Resource |
| `RoutineServiceSpec.java` | Spec with image, replicas, ingress config |
| `RoutineServiceStatus.java` | Status tracking |
| `RoutineServiceReconciler.java` | Reconciler that creates Deployment, Service, Ingress, CronJobs |

---

## Phase 9: Example Application — Code Review Routine

### What

Create an example application demonstrating the Routines feature with a GitHub-triggered code review routine, similar to the "bespoke code review" use case from the Claude Code documentation.

### Module

`test-apps/code-review-routine` — new test application

### Components

1. **`CodeReviewAggregate`** — `AgenticAggregate` with an Embabel agent that performs code reviews
2. **`CodeReviewRoutine`** — Pre-configured routine with a GitHub trigger on `pull_request.opened`
3. **`CodeReviewAgent`** — Embabel `@Agent` that uses the GitHub MCP server to:
   - Read PR diff
   - Apply review checklist
   - Post inline comments
   - Add summary comment

### Kubernetes Deployment

```yaml
# The AgenticAggregate
apiVersion: akces.elasticsoftware.org/v1
kind: AgenticAggregateService
metadata:
  name: code-review-agent
spec:
  image: ghcr.io/example/code-review-agent:1.0.0
  applicationName: "Code Review Agent"
  sidecars:
    - name: github-mcp-server
      image: ghcr.io/github/github-mcp-server:latest
      env:
        - name: GITHUB_PERSONAL_ACCESS_TOKEN
          valueFrom:
            secretKeyRef:
              name: github-token
              key: token
      ports:
        - containerPort: 8080

---
# The Routine Service
apiVersion: akces.elasticsoftware.org/v1
kind: RoutineService
metadata:
  name: routine-service
spec:
  image: ghcr.io/elasticsoftwarefoundation/akces-routine-service:0.12.0
  webhookIngress:
    enabled: true
    host: webhooks.mycompany.com
```

---

## Component Interaction Diagram

```
                          ┌─────────────────────┐
                          │   GitHub Webhook     │
                          │   (PR opened)        │
                          └──────────┬───────────┘
                                     │
                          ┌──────────▼───────────┐
                          │ GitHubWebhookCtrl    │
                          │ (validates signature, │
                          │  matches triggers)    │
                          └──────────┬───────────┘
                                     │ FireRoutineCommand
                                     ▼
┌──────────────┐         ┌───────────────────────┐
│  Cron Job    │────────>│  Routine Aggregate     │
│  (schedule)  │         │  (validates, emits     │
└──────────────┘         │   RoutineTriggeredEvent)│
                         └──────────┬────────────┘
┌──────────────┐                    │
│  API POST    │────────────────────┘
│  /fire       │     FireRoutineCommand
└──────────────┘
                         ┌──────────▼────────────┐
                         │ RoutineExecution       │
                         │ ProcessManager         │
                         │ (translates trigger    │
                         │  → AssignTaskCommand)  │
                         └──────────┬────────────┘
                                    │ AssignTaskCommand
                                    ▼
                         ┌───────────────────────┐
                         │ AgenticAggregate       │
                         │ (Embabel agent loop,   │
                         │  LLM + MCP tools)      │
                         └──────────┬────────────┘
                                    │ AgentTaskFinishedEvent
                                    ▼
                         ┌───────────────────────┐
                         │ RoutineExecution       │
                         │ ProcessManager         │
                         │ (records completion)   │
                         └───────────────────────┘
```

---

## Implementation Order and Dependencies

```
Phase 1: Core Domain Model (api)
    │
    ▼
Phase 2: Routine Aggregate (routines)
    │
    ├──► Phase 3: Process Manager (routines)
    │
    ├──► Phase 7: Query Models (routines)
    │       │
    │       ├──► Phase 5: API Trigger (routines-api)
    │       │
    │       └──► Phase 6: GitHub Trigger (routines-github)
    │
    └──► Phase 4: Schedule Trigger (operator)
              │
              └──► Phase 8: Operator CRD (operator)

Phase 9: Example Application (test-apps) — can start after Phases 2-3
```

### Parallelizable Work

- Phases 5, 6, and 4 can be developed in parallel after Phase 7 (Query Model)
- Phase 9 can begin as soon as Phases 2 and 3 are complete, with manual trigger testing

---

## Security Considerations

1. **API Token Security** — tokens are stored as bcrypt hashes; raw tokens are never stored and only returned once on creation
2. **GitHub Webhook Signatures** — all incoming webhooks are validated using `X-Hub-Signature-256` with HMAC-SHA256
3. **Prompt Injection** — the GitHub event payload (PR title, body, etc.) is passed as structured data, not interpolated into the prompt string
4. **Token Rotation** — `RegenerateApiTokenCommand` allows rotating compromised tokens without recreating the routine
5. **Scope Isolation** — each API token is scoped to a single routine; a leaked token cannot trigger other routines

## Open Questions for Architect

1. **Module placement**: Should the Routine aggregate live in a new `main/routines` module, or should it be part of the `main/agentic` module? The plan proposes a separate module for separation of concerns.

2. **Schedule trigger implementation**: Kubernetes CronJob vs. in-process scheduler — the plan recommends CronJob for alignment with the operator model, but this adds complexity. What is the preferred approach?

3. **Webhook registration**: Manual (user configures GitHub webhook) vs. automatic (via GitHub MCP server). Manual is recommended as the starting point.

4. **Routine ownership**: Should routines be scoped to a tenant (multi-tenant) or global? The plan assumes tenant-scoped, following the existing Akces multi-tenant pattern.

5. **Execution history retention**: How long should `RoutineExecutionQueryModel` data be retained? Should there be an automatic cleanup/compaction policy?

6. **Rate limiting**: Should the API trigger support rate limiting? If so, where is it enforced — at the ingress level (Kubernetes), at the API controller level, or within the Routine aggregate (as part of the domain logic)?
