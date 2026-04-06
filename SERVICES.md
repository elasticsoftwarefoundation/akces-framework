# Akces Framework — Kubernetes Services

This document describes the Kubernetes Custom Resource Definitions (CRDs) provided by the Akces
Operator and explains how to deploy each service type. For a general framework overview see
[FRAMEWORK_OVERVIEW.md](FRAMEWORK_OVERVIEW.md).

---

## API Group

All Akces CRDs are registered under:

```
apiVersion: akces.elasticsoftwarefoundation.org/v1
```

---

## CRD Overview

| Kind | Short name | Purpose | Replicas |
|------|-----------|---------|---------|
| `Aggregate` | `agg` | Standard event-sourced aggregate service | Configurable |
| `CommandService` | `cs` | Command validation and routing service | Configurable |
| `QueryService` | `qs` | Query model / read-model service | Configurable |
| `AgenticAggregate` | `aag` | Singleton AI-agent aggregate with memory | Always **1** |

---

## Aggregate (`agg`)

Deploys an event-sourced Aggregate service that processes commands and maintains aggregate state
via RocksDB snapshots.

### Spec fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `image` | string | ✅ | Container image for the Aggregate service |
| `applicationName` | string | ✅ | Value for the `SPRING_APPLICATION_NAME` env var |
| `replicas` | integer | ✅ | Number of Kafka partitions / pod replicas |
| `args` | string[] | ❌ | Additional JVM / application arguments |
| `resources` | ResourceRequirements | ❌ | CPU and memory requests/limits |
| `enableSchemaOverwrites` | boolean | ❌ | Allow overwriting existing Avro schemas (default: `false`) |
| `env` | EnvVar[] | ❌ | Additional environment variables |
| `applicationProperties` | string | ❌ | Extra `application.properties` content merged into ConfigMap |
| `imagePullSecret` | string | ❌ | Name of the image pull secret |
| `storageClassName` | string | ❌ | StorageClass for the PVC (default: cluster default) |
| `storageSize` | string | ❌ | PVC size (default: `"4Gi"`) |

### Example

```yaml
apiVersion: akces.elasticsoftwarefoundation.org/v1
kind: Aggregate
metadata:
  name: wallet-aggregate
  namespace: default
spec:
  replicas: 3
  image: ghcr.io/elasticsoftwarefoundation/akces-aggregate-service:0.12.0
  applicationName: "Wallet Aggregate Service"
  resources:
    requests:
      cpu: "500m"
      memory: "512Mi"
    limits:
      cpu: "2"
      memory: "2Gi"
  storageSize: "8Gi"
```

---

## CommandService (`cs`)

Deploys a command-routing service that validates incoming commands against their JSON Schema and
forwards them to the appropriate Aggregate topic.

### Example

```yaml
apiVersion: akces.elasticsoftwarefoundation.org/v1
kind: CommandService
metadata:
  name: wallet-commands
  namespace: default
spec:
  replicas: 2
  image: ghcr.io/elasticsoftwarefoundation/akces-command-service:0.12.0
  applicationName: "Wallet Command Service"
```

---

## QueryService (`qs`)

Deploys a query-model service that consumes domain events and maintains read-optimised projections
in RocksDB or a relational database.

### Example

```yaml
apiVersion: akces.elasticsoftwarefoundation.org/v1
kind: QueryService
metadata:
  name: wallet-query
  namespace: default
spec:
  replicas: 1
  image: ghcr.io/elasticsoftwarefoundation/akces-query-service:0.12.0
  applicationName: "Wallet Query Service"
```

---

## AgenticAggregate (`aag`)

Deploys a singleton AI-agent aggregate that has a built-in sliding-window memory system and can
communicate with external AI services (e.g. via the Model Context Protocol). See
[FRAMEWORK_OVERVIEW.md — AgenticAggregates](FRAMEWORK_OVERVIEW.md#agenticaggregates) for the
programming model.

> **Note:** The replica count is always **1**. There is no `replicas` field in the spec — the
> operator always deploys exactly one pod backed by a single-partition StatefulSet.

### Spec fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `image` | string | ✅ | Container image for the AgenticAggregate service |
| `applicationName` | string | ✅ | Value for the `SPRING_APPLICATION_NAME` env var |
| `args` | string[] | ❌ | Additional JVM / application arguments |
| `resources` | ResourceRequirements | ❌ | CPU and memory requests/limits for the main container |
| `enableSchemaOverwrites` | boolean | ❌ | Allow overwriting existing Avro schemas (default: `false`) |
| `sidecars` | SidecarSpec[] | ❌ | List of sidecar containers to inject (e.g. MCP server proxies) |
| `env` | EnvVar[] | ❌ | Additional environment variables for the main container |
| `applicationProperties` | string | ❌ | Extra `application.properties` content merged into ConfigMap |
| `imagePullSecret` | string | ❌ | Name of the image pull secret |
| `storageClassName` | string | ❌ | StorageClass for the PVC (default: cluster default) |
| `storageSize` | string | ❌ | PVC size (default: `"4Gi"`) |

### SidecarSpec fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | ✅ | Container name (must be unique within the pod) |
| `image` | string | ✅ | Container image for the sidecar |
| `env` | EnvVar[] | ❌ | Environment variables for the sidecar |
| `ports` | ContainerPort[] | ❌ | Ports to expose from the sidecar |
| `resources` | ResourceRequirements | ❌ | CPU and memory requests/limits for the sidecar |
| `readinessProbe` | Probe | ❌ | Kubernetes readiness probe |
| `livenessProbe` | Probe | ❌ | Kubernetes liveness probe |

### Example — basic deployment

```yaml
apiVersion: akces.elasticsoftwarefoundation.org/v1
kind: AgenticAggregate
metadata:
  name: my-assistant
  namespace: default
spec:
  image: ghcr.io/elasticsoftwarefoundation/akces-agentic-service:0.12.0
  applicationName: "My Assistant AgenticAggregate"
  resources:
    requests:
      cpu: "500m"
      memory: "512Mi"
    limits:
      cpu: "2"
      memory: "2Gi"
  applicationProperties: |
    spring.ai.openai.api-key=${OPENAI_API_KEY}
    spring.ai.openai.chat.options.model=gpt-4o
  env:
    - name: OPENAI_API_KEY
      valueFrom:
        secretKeyRef:
          name: openai-credentials
          key: api-key
```

### Example — with GitHub MCP Server sidecar

The following example deploys an AgenticAggregate that uses the
[GitHub MCP Server](https://github.com/github/github-mcp-server) as a sidecar to give the AI
agent access to GitHub APIs via the Model Context Protocol (MCP).

```yaml
apiVersion: akces.elasticsoftwarefoundation.org/v1
kind: AgenticAggregate
metadata:
  name: github-assistant
  namespace: default
spec:
  image: ghcr.io/elasticsoftwarefoundation/akces-agentic-service:0.12.0
  applicationName: "GitHub Assistant AgenticAggregate"
  resources:
    requests:
      cpu: "500m"
      memory: "512Mi"
    limits:
      cpu: "2"
      memory: "2Gi"
  sidecars:
    - name: github-mcp-server
      image: ghcr.io/github/github-mcp-server:latest
      env:
        - name: GITHUB_PERSONAL_ACCESS_TOKEN
          valueFrom:
            secretKeyRef:
              name: github-credentials
              key: token
      ports:
        - name: mcp
          containerPort: 8080
          protocol: TCP
      resources:
        requests:
          cpu: "100m"
          memory: "128Mi"
        limits:
          cpu: "500m"
          memory: "256Mi"
      readinessProbe:
        httpGet:
          path: /health
          port: 8080
        initialDelaySeconds: 5
        periodSeconds: 10
  applicationProperties: |
    spring.ai.openai.api-key=${OPENAI_API_KEY}
    spring.ai.openai.chat.options.model=gpt-4o
    # Connect to the GitHub MCP Server sidecar running at localhost
    spring.ai.mcp.client.sse.connections.github-mcp.url=http://localhost:8080
  env:
    - name: OPENAI_API_KEY
      valueFrom:
        secretKeyRef:
          name: openai-credentials
          key: api-key
```

---

## Operator Deployment

The Akces Operator itself is a standard Spring Boot application that watches the above CRDs and
reconciles the desired state (StatefulSet, Service, ConfigMap) in the cluster.

```bash
# Install the operator using Helm (example — adapt to your release)
helm install akces-operator oci://ghcr.io/elasticsoftwarefoundation/akces-operator \
  --namespace akces-system --create-namespace \
  --set image.tag=0.12.0
```

After the operator is running, apply your Custom Resources with `kubectl apply -f <your-cr>.yaml`.
