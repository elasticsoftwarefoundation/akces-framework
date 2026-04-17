# Kubernetes Operator — Tasks

> **Status**: Migrated — all tasks reflect existing implemented code
> **Module**: `services/operator/` (`akces-operator`)

## Implementation Tasks

### Core Infrastructure

- [x] **TASK-001**: Create Spring Boot application entry point (`AkcesOperatorApplication`)
  - `@SpringBootApplication` with `KafkaAutoConfiguration` excluded
  - Register BouncyCastle JCE provider at startup
  - Enable `@EnableConfigurationProperties(KafkaProperties.class)`

- [x] **TASK-002**: Create operator configuration class (`AkcesOperatorConfig`)
  - Define `KafkaAdmin` bean with bootstrap servers from `spring.kafka.bootstrap-servers`
  - Define reconciler beans: `AggregateReconciler`, `CommandServiceReconciler`, `QueryServiceReconciler`, `AgenticAggregateReconciler`
  - `AgenticAggregateReconciler` accepts configurable `replicationFactor` (default: 3)

- [x] **TASK-003**: Create Kafka topic utility class (`KafkaTopicUtils`)
  - `createTopics(name, numPartitions)` — creates Commands, DomainEvents, AggregateState topics
  - `createAgenticAggregateTopics(name)` — creates singleton topics (1 partition, replication factor 3)
  - `createAgenticAggregateTopics(name, replicationFactor)` — configurable replication factor
  - `createTopic(name, partitions, retentionMs, replicationFactor)` — delete-policy topic
  - `createCompactedTopic(name, partitions, replicationFactor)` — compact-policy topic
  - All topics: LZ4 compression, 20 MB max message, 7-day segment
  - `min.insync.replicas = min(2, replicationFactor)`

- [x] **TASK-004**: Configure `application.properties`
  - Kafka bootstrap: `akces-kafka-bootstrap.kafka:9092`
  - Actuator health probes enabled (liveness + readiness)
  - Graceful shutdown
  - CRD apply on startup (`javaoperatorsdk.crd.apply-on-startup=true`)

### Aggregate CRD (7 files)

- [x] **TASK-005**: Define `Aggregate` CRD class
  - `@Group("akces.elasticsoftwarefoundation.org")`, `@Version("v1")`, `@Kind("Aggregate")`, `@ShortNames("agg")`
  - Extends `CustomResource<AggregateSpec, AggregateStatus>` implements `Namespaced`

- [x] **TASK-006**: Define `AggregateSpec` with fields: `replicas`, `image`, `aggregateNames`, `args`, `resources`, `enableSchemaOverwrites` (default false), `applicationName`

- [x] **TASK-007**: Define `AggregateStatus` with `readyReplicas` field (default 0)

- [x] **TASK-008**: Implement `AggregateReconciler`
  - `@Workflow` with ConfigMap, StatefulSet, Service dependents
  - `@PostConstruct init()`: discover partition count from `Akces-Control` topic
  - `reconcile()`: call `reconcileTopics()` for each aggregate name, then patch status from StatefulSet
  - `reconcileTopics()`: create 3 topics per aggregate name using discovered partition count

- [x] **TASK-009**: Implement aggregate `ConfigMapDependentResource`
  - Load `configmap.yaml` template, set name to `<aggregateName>-config`, apply labels

- [x] **TASK-010**: Implement aggregate `ServiceDependentResource`
  - Load `service.yaml` template, set name to `<aggregateName>-service`, configure pod selector

- [x] **TASK-011**: Implement aggregate `StatefulSetDependentResource`
  - Load `statefulset.yaml` template
  - Set replicas, image, container name (`akces-aggregate-service`), args, env vars, resources
  - Mount ConfigMap volume, configure PVC
  - **Note**: imagePullSecret, storageClass, storageSize are hardcoded (TODOs in source)

- [x] **TASK-012**: Create YAML templates for aggregate package (`configmap.yaml`, `service.yaml`, `statefulset.yaml`)

### CommandService CRD (7 files)

- [x] **TASK-013**: Define `CommandService` CRD class
  - `@Kind("CommandService")`, `@ShortNames("cs")`
  - Extends `CustomResource<CommandServiceSpec, CommandServiceStatus>` implements `Namespaced`

- [x] **TASK-014**: Define `CommandServiceSpec` with fields: `replicas`, `image`, `args`, `resources`, `applicationName`

- [x] **TASK-015**: Define `CommandServiceStatus` with `readyReplicas` field (default 0)

- [x] **TASK-016**: Implement `CommandServiceReconciler`
  - `@Workflow` with ConfigMap, StatefulSet, Service dependents
  - `reconcile()`: patch status from StatefulSet (no Kafka topic management)

- [x] **TASK-017**: Implement command `ConfigMapDependentResource`
  - Load `configmap.yaml` template, set name to `<name>-config`, apply labels

- [x] **TASK-018**: Implement command `ServiceDependentResource`
  - Load `service.yaml` template, set name to `<name>-service`, configure pod selector

- [x] **TASK-019**: Implement command `StatefulSetDependentResource`
  - Load `statefulset.yaml` template
  - Set replicas, image, container name (`akces-command-service`), args, env, resources
  - No VolumeClaimTemplate (differs from aggregate — command services have no PVC)
  - **Note**: imagePullSecret is hardcoded (TODO in source)

- [x] **TASK-020**: Create YAML templates for command package

### QueryService CRD (7 files)

- [x] **TASK-021**: Define `QueryService` CRD class
  - `@Kind("QueryService")`, `@ShortNames("qs")`
  - Extends `CustomResource<QueryServiceSpec, QueryServiceStatus>` implements `Namespaced`

- [x] **TASK-022**: Define `QueryServiceSpec` with fields: `replicas`, `image`, `args`, `resources`, `applicationName`, `env`, `applicationProperties`

- [x] **TASK-023**: Define `QueryServiceStatus` with `readyReplicas` field (default 0)

- [x] **TASK-024**: Implement `QueryServiceReconciler`
  - `@Workflow` with ConfigMap, StatefulSet, Service dependents
  - `reconcile()`: patch status from StatefulSet (no Kafka topic management)

- [x] **TASK-025**: Implement query `ConfigMapDependentResource`
  - Load `configmap.yaml` template, merge `applicationProperties` from spec into ConfigMap data

- [x] **TASK-026**: Implement query `ServiceDependentResource`
  - Load `service.yaml` template, set name to `<name>-service`, configure pod selector

- [x] **TASK-027**: Implement query `StatefulSetDependentResource`
  - Load `statefulset.yaml` template
  - Set replicas, image, container name (`akces-query-service`), args, resources
  - Merge additional env vars from `spec.env` into primary container
  - **Note**: imagePullSecret, storageClass, storageSize are hardcoded (TODOs in source)

- [x] **TASK-028**: Create YAML templates for query package

### AgenticAggregate CRD (8 files)

- [x] **TASK-029**: Define `AgenticAggregateResource` CRD class
  - `@Kind("AgenticAggregate")`, `@ShortNames("aag")`
  - Extends `CustomResource<AgenticAggregateSpec, AgenticAggregateStatus>` implements `Namespaced`

- [x] **TASK-030**: Define `AgenticAggregateSpec` with fields: `image`, `applicationName`, `args`, `resources`, `enableSchemaOverwrites`, `sidecars`, `env`, `applicationProperties`, `imagePullSecret`, `storageClassName`, `storageSize` (default "4Gi")

- [x] **TASK-031**: Define `AgenticAggregateStatus` with `readyReplicas` field (default 0)

- [x] **TASK-032**: Define `SidecarSpec` with fields: `name`, `image`, `env`, `ports`, `resources`, `readinessProbe`, `livenessProbe`

- [x] **TASK-033**: Implement `AgenticAggregateReconciler`
  - `@Workflow` with ConfigMap, StatefulSet, Service dependents
  - Constructor accepts `KafkaAdmin` and `replicationFactor`
  - `reconcile()`: create 3 Kafka topics (1 partition each), then patch status from StatefulSet
  - Topics use configurable replication factor with safe `min.insync.replicas`

- [x] **TASK-034**: Implement agentic `ConfigMapDependentResource`
  - Load `configmap.yaml` template, append `applicationProperties` from spec to existing properties

- [x] **TASK-035**: Implement agentic `ServiceDependentResource`
  - Load `service.yaml` template, set name to `<name>-service`, configure pod selector

- [x] **TASK-036**: Implement agentic `StatefulSetDependentResource`
  - Load `statefulset.yaml` template, fixed at 1 replica (singleton)
  - Configure primary container: image, name (`akces-agentic-aggregate-service`), args, env, resources
  - Conditionally add image pull secret from `spec.imagePullSecret`
  - Set storage class from `spec.storageClassName` (null = cluster default)
  - Set storage size from `spec.storageSize` (default "4Gi")
  - Merge additional env vars from `spec.env`
  - Append sidecar containers from `spec.sidecars` (with full Container spec mapping)

- [x] **TASK-037**: Create YAML templates for agentic package

### Maven & Build

- [x] **TASK-038**: Configure `pom.xml`
  - Parent: `akces-framework-services`
  - Dependencies: JOSDK Spring Boot starter, Spring Boot (web, actuator, logging, kafka), BouncyCastle, Testcontainers, Mockito
  - Plugins: `spring-boot-maven-plugin`, `crd-generator-maven-plugin` (generates CRD YAMLs to `META-INF/fabric8/`)

### Testing

- [x] **TASK-039**: Implement integration test (`AkcesOperatorApplicationTests`)
  - Testcontainers Kafka (Confluent Platform 7.8.7, KRaft mode)
  - `@EnableMockOperator` for JOSDK test support
  - Pre-create `Akces-Control`, `Akces-CommandResponses`, `Akces-GDPRKeys` topics
  - Tests: context loads, health readiness/liveness endpoints, aggregate reconciliation (9 topics verified), command service reconciliation, query service reconciliation, agentic aggregate reconciliation (3 singleton topics verified)

- [x] **TASK-040**: Implement unit test (`AgenticAggregateTopicCreationTest`)
  - 9 test cases for `KafkaTopicUtils.createAgenticAggregateTopics()`
  - Verifies: 3 topics created, correct names, 1 partition each, delete/compact cleanup policies, default replication factor 3, custom replication factor, `min.insync.replicas` ≤ replication factor, LZ4 compression

## Gaps & Technical Debt

### Test Coverage Gap

The module has **2 test files (~396 lines)** covering **32 source files (~2,983 lines)**. This represents very limited test coverage:

| Area | Tested | Coverage Notes |
|---|---|---|
| `KafkaTopicUtils` (agentic) | ✅ Unit tests | 9 tests for agentic topic creation |
| `KafkaTopicUtils` (standard) | ⚠️ Indirect only | Covered indirectly via integration test |
| `AggregateReconciler` | ✅ Integration test | Topic creation + reconciliation verified |
| `CommandServiceReconciler` | ✅ Integration test | No-update path verified |
| `QueryServiceReconciler` | ✅ Integration test | No-update path verified |
| `AgenticAggregateReconciler` | ✅ Integration test | Topic creation + reconciliation verified |
| All `ConfigMapDependentResource` | ❌ Not tested | No tests for ConfigMap generation logic |
| All `ServiceDependentResource` | ❌ Not tested | No tests for Service generation logic |
| All `StatefulSetDependentResource` | ❌ Not tested | No tests for StatefulSet generation logic (the most complex code in the module) |
| `SidecarSpec` injection | ❌ Not tested | No tests for sidecar container injection |
| `AkcesOperatorConfig` | ⚠️ Indirect only | Covered via Spring context loading |
| Env var merging (query, agentic) | ❌ Not tested | No tests for env var merge behavior |
| Application properties merging | ❌ Not tested | No tests for ConfigMap properties append |
| Status update with ready replicas | ⚠️ Partial | Reconcilers tested with empty StatefulSet only (noUpdate path) |

**Recommended future test tasks (not yet implemented):**
- Unit tests for each `StatefulSetDependentResource.desired()` method (verify labels, replicas, env, image, volume mounts)
- Unit tests for each `ConfigMapDependentResource.desired()` method (verify name, labels, properties merging)
- Unit tests for each `ServiceDependentResource.desired()` method (verify name, labels, selector)
- Unit test for `SidecarSpec` injection in agentic `StatefulSetDependentResource`
- Unit test for status update path when StatefulSet has ready replicas (non-empty `Optional`)
- Unit tests for `KafkaTopicUtils.createTopics()` (standard aggregate topics)
- Unit tests for env var merging in query and agentic StatefulSet dependent resources

### Hardcoded Configuration (aggregate, command, query packages)

The `aggregate`, `command`, and `query` packages have hardcoded values that should be made configurable (already resolved in `agentic`):
- `imagePullSecret` hardcoded to `"github-packages-cfg"`
- `storageClassName` hardcoded to `"akces-data-hyperdisk-balanced"`
- `storageSize` hardcoded to `"4Gi"`
