# Agentic Aggregates Feature Plan

## Overview

The **AgenticAggregate** is a new first-class concept in the Akces Framework that combines Event Sourcing with AI Agent capabilities. Unlike a normal Aggregate (which processes commands deterministically, emits events, and maintains state across multiple partitions), an AgenticAggregate is a **singleton instance** that implements an **Agent Loop**, integrating with LLMs, MCP servers, and the Embabel agent framework.

### Key Differences from Normal Aggregates

| Aspect | Normal Aggregate | Agentic Aggregate |
|--------|-----------------|-------------------|
| **Partitioning** | Multi-partition (horizontally scalable) | Single partition (singleton) |
| **Threading** | One thread per partition, many partitions | Single-threaded agent loop |
| **Processing model** | Deterministic command→event, fast | Agent loop with LLM I/O, potentially long-running |
| **State** | Domain state only | Domain state + Memory (learned facts) |
| **External I/O** | Not allowed (must be deterministic) | Allowed — MCP tools, LLM calls, external APIs |
| **Dependencies** | Akces runtime only | Akces runtime + Spring AI + Embabel + MCP servers |
| **Deployment** | StatefulSet with N replicas | StatefulSet with 1 replica + sidecar containers |

### Core Capabilities

1. **Event Sourcing with Agent Loop** — Receives commands, processes them through an Embabel-powered agent loop (which may involve LLM reasoning, tool use, multi-step planning), and emits domain events
2. **Memory System** — Stores and retrieves learned facts about domains, codebases, or operational knowledge as domain events (modeled after Copilot's `store_memory` pattern)
3. **MCP Tool Integration** — Has the GitHub MCP Server (and potentially other MCP servers) available as tools via Spring AI's MCP client, running as Kubernetes sidecar containers
4. **Singleton Guarantee** — Exactly one instance runs at a time, ensuring sequential processing and consistent state

## Architectural Decisions

### 1. Singleton Topology: Single-Partition Kafka Topics

Each AgenticAggregate gets Kafka topics with **exactly 1 partition**:
- `{AgenticAggregateName}-Commands` (1 partition, delete policy)
- `{AgenticAggregateName}-DomainEvents` (1 partition, delete policy)
- `{AgenticAggregateName}-AggregateState` (1 partition, compaction policy)

This is enforced by:
- The `@AgenticAggregateInfo` annotation (no partition count configuration)
- The operator CRD (always creates 1-partition topics)
- The runtime (always assigns partition 0, no rebalancing needed)

**Rationale**: Agent loops are inherently stateful and sequential. LLM context, conversation history, tool call state, and planning state cannot be easily partitioned. A singleton ensures deterministic agent behavior and avoids the complexity of distributed agent coordination.

### 2. Agent Loop Architecture: Embabel + Spring AI

The AgenticAggregate implements its agent loop using the **Embabel** framework (by Rod Johnson, creator of Spring):

- **Embabel** provides Goal-Oriented Action Planning (GOAP) — the agent dynamically plans multi-step workflows to achieve goals
- **Spring AI** provides the LLM integration layer (OpenAI, Anthropic, etc.) and MCP client/server support
- The Embabel agent is configured as a Spring bean within the AgenticAggregate's application context

The command processing flow is:
1. Command arrives from Kafka
2. The AgenticAggregate's command handler invokes the Embabel agent with the command payload as context
3. The Embabel agent plans and executes actions (which may include LLM calls, MCP tool invocations, memory lookups)
4. The agent's result is translated into domain events
5. Events are committed transactionally to Kafka (same pattern as normal aggregates)

**Key difference from normal command handlers**: Normal `@CommandHandler` methods are synchronous and deterministic. Agentic command handlers MAY be long-running (seconds to minutes) due to LLM/tool interactions. The single-threaded, single-partition design accommodates this.

### 3. Memory System: Event-Sourced Facts

The memory system is modeled after GitHub Copilot's memory feature, where an agent can store learned facts for future use. In the Akces context, memories are persisted as **domain events** and reconstructed into state:

#### Memory Domain Model

```java
@DomainEventInfo(type = "MemoryStored", version = 1)
public record MemoryStoredEvent(
    @AggregateIdentifier String agenticAggregateId,
    String memoryId,        // UUID
    String subject,         // 1-2 word topic: "naming conventions", "error handling"
    String fact,            // The fact to remember (max 200 chars)
    String citations,       // Source of the fact
    String reason,          // Why this fact is being stored
    Instant storedAt
) implements DomainEvent { ... }

@DomainEventInfo(type = "MemoryRevoked", version = 1)
public record MemoryRevokedEvent(
    @AggregateIdentifier String agenticAggregateId,
    String memoryId,
    String reason,
    Instant revokedAt
) implements DomainEvent { ... }
```

#### Memory State

Memories are stored in the aggregate state as a collection:

```java
public record AgenticAggregateMemory(
    String memoryId,
    String subject,
    String fact,
    String citations,
    String reason,
    Instant storedAt
) {}
```

The aggregate state includes a `List<AgenticAggregateMemory>` (or indexed `Map<String, AgenticAggregateMemory>`) that is:
- Populated via `@EventSourcingHandler` for `MemoryStoredEvent`
- Pruned via `@EventSourcingHandler` for `MemoryRevokedEvent`
- Injected into the Embabel agent's context at the start of each command processing cycle

**Rationale**: By modeling memories as domain events, they benefit from the same durability, replay, and audit guarantees as all other Akces events. The agent can learn over time, and its learned knowledge is fully reconstructable from the event log.

### 4. MCP Server Integration: Sidecar Pattern

MCP servers run as **sidecar containers** alongside the AgenticAggregate's main container in the same Kubernetes Pod:

#### GitHub MCP Server Sidecar

- **Image**: `ghcr.io/github/github-mcp-server:latest` (pinnable via CRD spec)
- **Communication**: HTTP/SSE transport on `localhost` (pod-local networking)
- **Authentication**: GitHub PAT provided via Kubernetes Secret, mounted as environment variable
- **Toolsets**: Configurable via `GITHUB_TOOLSETS` env var (e.g., `repos,issues,pull_requests,code_search`)

#### Spring AI MCP Client Configuration

The main container connects to sidecar MCP servers using Spring AI's MCP client:

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        servers:
          github:
            url: http://localhost:8080  # Sidecar MCP server
            transport: sse
```

#### Extensible Sidecar Model

The CRD supports additional sidecar containers for future MCP servers:

```yaml
apiVersion: akces.elasticsoftwarefoundation.org/v1
kind: AgenticAggregate
metadata:
  name: my-coding-agent
spec:
  image: ghcr.io/my-org/my-agentic-aggregate:1.0.0
  applicationName: "My Coding Agent"
  sidecars:
    - name: github-mcp-server
      image: ghcr.io/github/github-mcp-server:latest
      env:
        - name: GITHUB_PERSONAL_ACCESS_TOKEN
          valueFrom:
            secretKeyRef:
              name: github-pat
              key: token
        - name: GITHUB_TOOLSETS
          value: "repos,issues,pull_requests"
      ports:
        - containerPort: 8080
    # Future: additional MCP servers
    - name: jira-mcp-server
      image: ghcr.io/my-org/jira-mcp-server:latest
      env:
        - name: JIRA_API_TOKEN
          valueFrom:
            secretKeyRef:
              name: jira-token
              key: token
      ports:
        - containerPort: 8081
```

### 5. Runtime: Simplified Single-Partition Processing

The AgenticAggregate runtime is a simplified variant of the normal `AggregatePartition`:

- **No consumer rebalance listener** — always owns partition 0
- **No Murmur3 hash routing** — all commands go to the single partition
- **Extended `max.poll.interval.ms`** — set to a much higher value (e.g., 5 minutes) to accommodate long-running agent loops
- **No parallel partition executor** — single `AggregatePartition` instance runs directly

The runtime extends the existing `AggregatePartition` concept but with these key modifications:
- The poll loop accommodates long-running command processing
- Kafka consumer is paused while the agent loop is executing (to avoid session timeout)
- Heartbeat thread keeps the consumer session alive during long agent operations

### 6. New Maven Module: `main/agentic`

A new module `main/agentic` is introduced to isolate the AI dependencies from the core runtime:

```
main/agentic/
├── pom.xml
└── src/main/java/org/elasticsoftware/akces/agentic/
    ├── AgenticAggregate.java              # Interface
    ├── AgenticAggregateState.java         # State interface with memory support
    ├── AgenticAggregateMemory.java        # Memory record
    ├── events/
    │   ├── MemoryStoredEvent.java
    │   └── MemoryRevokedEvent.java
    ├── annotations/
    │   └── AgenticAggregateInfo.java
    └── runtime/
        ├── AgenticAggregateRuntime.java
        ├── AgenticAggregatePartition.java
        └── AgenticAggregateServiceApplication.java
```

**Dependencies** (in addition to `akces-api` and `akces-runtime`):
- `org.springframework.ai:spring-ai-starter-mcp-client` — MCP client for tool integration
- `com.embabel:embabel-agent-spring-boot-starter` — Embabel agent framework
- `org.springframework.ai:spring-ai-starter-model-openai` (or other LLM provider starters)

## Component Design

### API Module (`main/api`) — New Types

#### 1. `@AgenticAggregateInfo` Annotation

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Component
public @interface AgenticAggregateInfo {
    @AliasFor(annotation = Component.class)
    String value();

    Class<? extends AggregateState> stateClass();

    boolean generateGDPRKeyOnCreate() default false;

    String description() default "";

    // No indexed/indexName — agentic aggregates are singletons, not indexed
    // No partition configuration — always 1 partition
}
```

#### 2. `AgenticAggregate` Interface

```java
public interface AgenticAggregate<S extends AggregateState> extends Aggregate<S> {
    /**
     * Returns the memories currently stored in the aggregate state.
     * These are injected into the agent context at the start of each processing cycle.
     */
    default List<AgenticAggregateMemory> getMemories(S state) {
        if (state instanceof MemoryAwareState memoryState) {
            return memoryState.getMemories();
        }
        return List.of();
    }
}
```

#### 3. `MemoryAwareState` Interface

```java
/**
 * Interface for aggregate states that support agent memory.
 * Implement this on your state class to enable the memory system.
 */
public interface MemoryAwareState {
    List<AgenticAggregateMemory> getMemories();
}
```

#### 4. `AgenticAggregateMemory` Record

```java
public record AgenticAggregateMemory(
    String memoryId,
    String subject,
    String fact,
    String citations,
    String reason,
    Instant storedAt
) {}
```

### Agentic Module (`main/agentic`) — New Module

#### 5. Built-in Memory Events

The module provides built-in domain events for the memory system. These are automatically registered with the schema registry and handled by the framework:

- `MemoryStoredEvent` — Emitted when the agent stores a new memory
- `MemoryRevokedEvent` — Emitted when the agent revokes/replaces a memory

#### 6. `AgenticAggregatePartition`

Extends the concept of `AggregatePartition` with:

- **Agent context injection**: Before each command processing, the current memories are loaded from state and injected into the Embabel agent's context
- **Extended poll interval**: Configures Kafka consumer with higher `max.poll.interval.ms` (default: 300000ms / 5 minutes)
- **Consumer pause/resume**: Pauses the consumer while the agent loop executes, resumes after completion
- **Heartbeat management**: Keeps consumer session alive during long-running operations via the consumer's background heartbeat thread (which operates independently of polling)

#### 7. `AgenticAggregateServiceApplication`

A Spring Boot application class (similar to `AggregateServiceApplication`) that:

- Auto-configures Spring AI MCP client
- Auto-configures Embabel agent framework
- Discovers and initializes `@AgenticAggregateInfo`-annotated beans
- Creates the single-partition Kafka consumer and producer
- Starts the agent loop

### Operator Module (`services/operator`) — New CRD

#### 8. `AgenticAggregate` CRD

**Group**: `akces.elasticsoftwarefoundation.org`
**Version**: `v1`
**Kind**: `AgenticAggregate`
**Short Name**: `aag`

**Spec:**

```java
public class AgenticAggregateSpec {
    private String image;                          // Main container image
    private String applicationName;                // Application name
    private List<String> args;                     // Container args
    private ResourceRequirements resources;         // Main container resources
    private boolean enableSchemaOverwrites;         // Schema overwrite flag
    private List<SidecarSpec> sidecars;            // Sidecar container specifications
    private List<EnvVar> env;                      // Additional env vars for main container
    private String applicationProperties;           // Custom application.properties content
}

public class SidecarSpec {
    private String name;                           // Sidecar container name
    private String image;                          // Sidecar container image
    private List<EnvVar> env;                      // Environment variables
    private List<ContainerPort> ports;             // Exposed ports
    private ResourceRequirements resources;         // Resource requirements
    private Probe readinessProbe;                  // Optional readiness probe
    private Probe livenessProbe;                   // Optional liveness probe
}
```

**Status:**

```java
public class AgenticAggregateStatus {
    private Integer readyReplicas;                 // Always 0 or 1
}
```

#### 9. `AgenticAggregateReconciler`

Similar to the existing `AggregateReconciler` but:

- Always creates Kafka topics with **1 partition** (hardcoded)
- Creates StatefulSet with **1 replica** (hardcoded)
- Adds sidecar containers from `spec.sidecars` to the Pod template
- Creates ConfigMap with agent-specific configuration (MCP endpoints, LLM config)
- Creates Service for the main container

#### 10. Dependent Resources

- **`StatefulSetDependentResource`** — Creates StatefulSet with main container + sidecar containers. The Pod template includes:
  - Main container (the AgenticAggregate application)
  - One or more sidecar containers (MCP servers)
  - Shared volume for inter-container communication (if needed)
  - Image pull secrets
  - Health probes for all containers

- **`ConfigMapDependentResource`** — Creates ConfigMap with:
  - `application.properties` (Kafka, Akces, Spring AI MCP client config)
  - `logback.xml` (logging configuration)

- **`ServiceDependentResource`** — Creates ClusterIP Service for the main container

#### 11. Kafka Topic Creation

`KafkaTopicUtils` is extended with a new method:

```java
public static void createAgenticAggregateTopics(
    KafkaAdmin kafkaAdmin,
    String agenticAggregateName
) {
    // Always creates topics with exactly 1 partition
    createTopic(kafkaAdmin, agenticAggregateName + "-Commands", 1);
    createTopic(kafkaAdmin, agenticAggregateName + "-DomainEvents", 1);
    createCompactedTopic(kafkaAdmin, agenticAggregateName + "-AggregateState", 1);
}
```

### Kubernetes Resource Templates

#### 12. StatefulSet Template (`statefulset.yaml`)

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: placeholder
  labels:
    app.kubernetes.io/managed-by: akces-operator
spec:
  replicas: 1  # Always 1 — singleton
  podManagementPolicy: Parallel
  selector:
    matchLabels:
      app: placeholder
  template:
    metadata:
      labels:
        app: placeholder
    spec:
      securityContext:
        runAsUser: 1000
        runAsGroup: 1000
        fsGroup: 1000
      imagePullSecrets:
        - name: github-packages-cfg
      containers:
        - name: agentic-aggregate
          image: placeholder
          # ... (same health probes, volume mounts as aggregate statefulset)
          # Extended JVM options for agent workload
          env:
            - name: JAVA_TOOL_OPTIONS
              value: "-XX:+UseZGC"
            - name: VIRTUAL_THREADS_COUNT
              value: "100"
        # Sidecar containers are dynamically added from spec.sidecars
      volumes:
        - name: config-volume
          configMap:
            name: placeholder
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        storageClassName: akces-data-hyperdisk-balanced
        resources:
          requests:
            storage: 4Gi
```

## Implementation Phases

### Phase 1: API Foundation
**Scope**: New interfaces and annotations in `main/api`

1. Create `AgenticAggregateInfo` annotation
2. Create `AgenticAggregate` interface (extends `Aggregate`)
3. Create `MemoryAwareState` interface
4. Create `AgenticAggregateMemory` record
5. Create built-in memory domain events (`MemoryStoredEvent`, `MemoryRevokedEvent`)

### Phase 2: Agentic Module
**Scope**: New `main/agentic` Maven module

1. Create `main/agentic/pom.xml` with Spring AI and Embabel dependencies
2. Implement `AgenticAggregatePartition` (simplified single-partition variant)
3. Implement `AgenticAggregateRuntime` (agent loop integration)
4. Implement `AgenticAggregateServiceApplication` (Spring Boot entry point)
5. Implement memory injection into Embabel agent context
6. Configure Spring AI MCP client auto-configuration

### Phase 3: Operator Support
**Scope**: New CRD and reconciler in `services/operator`

1. Create `AgenticAggregate` CRD Java classes (Resource, Spec, Status)
2. Create `AgenticAggregateReconciler`
3. Create dependent resources (StatefulSet, ConfigMap, Service)
4. Create Kubernetes resource templates (YAML)
5. Extend `KafkaTopicUtils` for single-partition topic creation
6. Register reconciler in `AkcesOperatorConfig`
7. Generate CRD YAML via Fabric8 CRD Generator

### Phase 4: BOM and Integration
**Scope**: Build integration and documentation

1. Add `akces-agentic` to BOM (`bom/pom.xml`)
2. Add module to parent POM (`main/pom.xml`)
3. Update EventCatalog annotation processor for `@AgenticAggregateInfo`
4. Write integration tests
5. Update framework documentation

## New Dependencies

The following new dependencies are required for the `main/agentic` module:

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `org.springframework.ai:spring-ai-bom` | 1.1.x | Spring AI BOM for MCP and LLM support |
| `org.springframework.ai:spring-ai-starter-mcp-client` | (from BOM) | MCP client for connecting to sidecar MCP servers |
| `org.springframework.ai:spring-ai-starter-model-openai` | (from BOM) | Default LLM provider (configurable) |
| `com.embabel:embabel-agent-spring-boot-starter` | 0.1.x | Embabel agent framework for GOAP planning |

These dependencies are **isolated** to the `main/agentic` module and do not affect the core framework modules (`api`, `runtime`, `shared`, `client`, `query-support`).

## Open Questions for Architect Review

1. **LLM Provider Abstraction**: Should we support multiple LLM providers out of the box (OpenAI, Anthropic, local Ollama), or start with one and add others later?

2. **Memory Capacity**: Should there be a configurable maximum number of memories per AgenticAggregate? How should memory pruning work when the limit is reached?

3. **Agent Timeout**: What should the maximum execution time for a single agent loop iteration be? This affects `max.poll.interval.ms` and the overall system's responsiveness.

4. **Command Prioritization**: Should there be a mechanism to prioritize certain commands over others in the single-partition queue? (e.g., system commands like "clear memory" vs. normal domain commands)

5. **Observability**: What metrics should the AgenticAggregate expose? (e.g., LLM token usage, agent loop duration, memory count, tool invocation counts)

6. **Embabel Version Pinning**: Embabel is relatively new (0.1.x). Should we pin to a specific version or track latest? What's the risk tolerance for breaking changes?

7. **Sidecar Health Coupling**: Should the main container's readiness depend on the sidecar containers' readiness? (i.e., should the Pod be marked as ready only when both the main container and all sidecars are healthy?)

8. **GDPR/PII for Memories**: Should memories support `@PIIData` encryption? If an agent stores PII in a memory fact, should it be encrypted at rest?

9. **Multi-Tenancy**: Should AgenticAggregates support multi-tenancy (like normal aggregates), or is the singleton model inherently single-tenant?

10. **Event Bridge**: Should AgenticAggregates support `@EventBridgeHandler` for reacting to events from other aggregates, or should they only process commands?
