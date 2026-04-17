# Kubernetes Operator — Feature Specification

> **Status**: Migrated
> **Module**: `services/operator/` (`akces-operator`)
> **Version**: 0.12.1-SNAPSHOT

## 1. Overview

The Akces Operator is a Kubernetes operator that automates the deployment and lifecycle management of Akces Framework services. It watches for Custom Resource (CR) instances and reconciles the cluster state to match the desired configuration — provisioning StatefulSets, Services, ConfigMaps, and Kafka topics as needed.

The operator manages four distinct CRD kinds under the API group `akces.elasticsoftwarefoundation.org/v1`:

| Kind | Short Name | Purpose |
|---|---|---|
| `Aggregate` | `agg` | Deploys aggregate command-processing services |
| `CommandService` | `cs` | Deploys command-routing services |
| `QueryService` | `qs` | Deploys query/read-model services |
| `AgenticAggregate` | `aag` | Deploys singleton AI-powered aggregate services |

## 2. User Scenarios

### US-1: Deploy an AggregateService that processes commands via a Kubernetes CRD

**As** a platform operator,
**I want** to apply an `Aggregate` custom resource to my Kubernetes cluster,
**So that** the operator automatically provisions a StatefulSet, Service, ConfigMap, and the required Kafka topics (Commands, DomainEvents, AggregateState) for each named aggregate.

**Acceptance Criteria:**
- Applying an `Aggregate` CR creates a StatefulSet with the specified replicas, image, resource requirements, and args.
- A headless Service (`<name>-service`) is created with a pod selector matching the aggregate's app label.
- A ConfigMap (`<name>-config`) is created with base `application.properties` and mounted into the pods.
- For each aggregate name listed in `spec.aggregateNames`, three Kafka topics are created:
  - `<aggregateName>-Commands` (delete policy, partition count matching `Akces-Control` topic)
  - `<aggregateName>-DomainEvents` (delete policy, same partition count)
  - `<aggregateName>-AggregateState` (compact policy, same partition count)
- The CR status reflects `readyReplicas` from the underlying StatefulSet.
- The `SPRING_APPLICATION_NAME` environment variable is set from `spec.applicationName`.
- The `enableSchemaOverwrites` flag is passed as an environment variable.

### US-2: Deploy a CommandService for command routing

**As** a platform operator,
**I want** to apply a `CommandService` custom resource,
**So that** the operator provisions a StatefulSet, Service, and ConfigMap for command-routing without managing Kafka topics (topics are owned by the Aggregate reconciler).

**Acceptance Criteria:**
- Applying a `CommandService` CR creates a StatefulSet with the specified replicas, image, resources, and args.
- A headless Service (`<name>-service`) is created.
- A ConfigMap (`<name>-config`) is created and mounted into the pods.
- No Kafka topics are created by the CommandService reconciler.
- The CR status reflects `readyReplicas` from the underlying StatefulSet.
- When the StatefulSet is not yet available, the reconciler returns `noUpdate`.

### US-3: Deploy a QueryService for read-model serving

**As** a platform operator,
**I want** to apply a `QueryService` custom resource,
**So that** the operator provisions a StatefulSet, Service, and ConfigMap for query/read-model serving with support for custom environment variables and application properties.

**Acceptance Criteria:**
- Applying a `QueryService` CR creates a StatefulSet with the specified replicas, image, resources, and args.
- Additional environment variables from `spec.env` are merged into the primary container.
- Custom `spec.applicationProperties` content is injected into the ConfigMap.
- A headless Service (`<name>-service`) is created.
- The CR status reflects `readyReplicas` from the underlying StatefulSet.

### US-4: Deploy an AgenticAggregateService for AI-powered aggregates

**As** a platform operator,
**I want** to apply an `AgenticAggregate` custom resource,
**So that** the operator provisions a singleton StatefulSet (always 1 replica) with sidecar support, Kafka topics with single partitions, and configurable storage/image pull settings.

**Acceptance Criteria:**
- Applying an `AgenticAggregate` CR creates a StatefulSet fixed at 1 replica (singleton).
- Three Kafka topics are created, each with exactly 1 partition:
  - `<name>-Commands` (delete policy)
  - `<name>-DomainEvents` (delete policy)
  - `<name>-AggregateState` (compact policy)
- The replication factor is configurable via `akces.operator.agentic.replication-factor` (default: 3).
- `min.insync.replicas` is set to `min(2, replicationFactor)` so it never exceeds the replication factor.
- Sidecar containers from `spec.sidecars` are appended to the pod spec with full support for env, ports, resources, readiness/liveness probes.
- Additional env vars from `spec.env` are merged into the primary container.
- Additional `spec.applicationProperties` are appended to the ConfigMap.
- `spec.imagePullSecret` is optional; when null, no pull secret is added.
- `spec.storageClassName` is optional; when null, the cluster default StorageClass is used.
- `spec.storageSize` defaults to `"4Gi"` when not set.
- The CR status reports `readyReplicas` (typically 0 or 1).

### US-5: Manage Kafka topics automatically during service reconciliation

**As** a platform operator,
**I want** Kafka topics to be automatically created or updated when Aggregate and AgenticAggregate resources are reconciled,
**So that** I don't have to manually provision Kafka infrastructure before deploying Akces services.

**Acceptance Criteria:**
- The `AggregateReconciler` discovers the partition count from the existing `Akces-Control` topic at startup (`@PostConstruct`).
- For each aggregate name in the spec, three topics are created with that partition count.
- The `AgenticAggregateReconciler` always creates topics with 1 partition (singletons).
- All topics use LZ4 compression (`compression.type=lz4`).
- All topics have `max.message.bytes=20971520` (20 MB).
- Delete-policy topics have configurable `retention.ms` (default: -1 = infinite).
- Compact-policy topics have `min.cleanable.dirty.ratio=0.1` and `delete.retention.ms=604800000`.
- Topic creation is idempotent — `kafkaAdmin.createOrModifyTopics` handles existing topics.

## 3. Architecture

### 3.1 CRD Model

Each CRD follows the pattern:
```
CustomResource<Spec, Status> implements Namespaced
├── Spec   — desired state (image, replicas, resources, args, etc.)
└── Status — observed state (readyReplicas)
```

All CRDs are registered under:
- **Group**: `akces.elasticsoftwarefoundation.org`
- **Version**: `v1`

CRD YAML manifests are auto-generated by the `crd-generator-maven-plugin` (Fabric8) into `META-INF/fabric8/`.

### 3.2 Reconciler Pattern

Each reconciler uses JOSDK's `@Workflow` annotation to declare three dependent resources:

```
Reconciler<CR>
├── ConfigMapDependentResource  — manages the ConfigMap
├── StatefulSetDependentResource — manages the StatefulSet
└── ServiceDependentResource    — manages the headless Service
```

Reconcilers that manage Kafka topics (`AggregateReconciler`, `AgenticAggregateReconciler`) call `KafkaTopicUtils` during reconciliation before updating the status subresource.

### 3.3 Dependent Resource Templates

Each dependent resource loads a base YAML template from the classpath (`configmap.yaml`, `statefulset.yaml`, `service.yaml`) and customizes it using the Fabric8 builder API. This separates static K8s resource structure from dynamic spec-driven configuration.

### 3.4 Labeling Convention

All managed resources are labelled:
- `app.kubernetes.io/managed-by=akces-operator` — used by informer label selectors
- `app.kubernetes.io/part-of=<resourceName>` — groups resources belonging to the same CR
- `app=<resourceName>` — used for pod selection by the Service

### 3.5 Spring Boot Application

The operator is a Spring Boot application with:
- Actuator health endpoints (liveness + readiness probes enabled)
- Graceful shutdown support
- KafkaAutoConfiguration excluded (manual `KafkaAdmin` bean via `AkcesOperatorConfig`)
- BouncyCastle security provider registered at startup
- CRDs applied on startup (`javaoperatorsdk.crd.apply-on-startup=true`)

## 4. Configuration

| Property | Default | Description |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `akces-kafka-bootstrap.kafka:9092` | Kafka bootstrap servers |
| `akces.operator.agentic.replication-factor` | `3` | Replication factor for AgenticAggregate topics |
| `javaoperatorsdk.crd.apply-on-startup` | `true` | Auto-apply CRDs when operator starts |

## 5. File Inventory (32 source files)

| Package | Files | Description |
|---|---|---|
| `operator/` | `AkcesOperatorApplication`, `AkcesOperatorConfig` | Application entry point and Spring config |
| `operator/utils/` | `KafkaTopicUtils` | Kafka topic creation utilities |
| `operator/aggregate/` | `Aggregate`, `AggregateSpec`, `AggregateStatus`, `AggregateReconciler`, `ConfigMapDependentResource`, `ServiceDependentResource`, `StatefulSetDependentResource` | Aggregate CRD + reconciler (7 files) |
| `operator/command/` | `CommandService`, `CommandServiceSpec`, `CommandServiceStatus`, `CommandServiceReconciler`, `ConfigMapDependentResource`, `ServiceDependentResource`, `StatefulSetDependentResource` | CommandService CRD + reconciler (7 files) |
| `operator/query/` | `QueryService`, `QueryServiceSpec`, `QueryServiceStatus`, `QueryServiceReconciler`, `ConfigMapDependentResource`, `ServiceDependentResource`, `StatefulSetDependentResource` | QueryService CRD + reconciler (7 files) |
| `operator/agentic/` | `AgenticAggregateResource`, `AgenticAggregateSpec`, `AgenticAggregateStatus`, `AgenticAggregateReconciler`, `ConfigMapDependentResource`, `ServiceDependentResource`, `StatefulSetDependentResource`, `SidecarSpec` | AgenticAggregate CRD + reconciler (8 files) |

## 6. Known TODOs in Source

- `aggregate/StatefulSetDependentResource`: Image pull secret is hardcoded to `github-packages-cfg` (TODO: make configurable)
- `aggregate/StatefulSetDependentResource`: Storage class is hardcoded to `akces-data-hyperdisk-balanced` (TODO: get from config)
- `aggregate/StatefulSetDependentResource`: Storage size is hardcoded to `4Gi` (TODO: get from Aggregate spec)
- `command/StatefulSetDependentResource`: Same image pull secret hardcoding
- `query/StatefulSetDependentResource`: Same image pull secret and storage class hardcoding
- The `agentic` package has already resolved these TODOs with configurable `imagePullSecret`, `storageClassName`, and `storageSize` fields
