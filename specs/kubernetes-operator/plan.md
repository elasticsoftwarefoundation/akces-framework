# Kubernetes Operator — Implementation Plan

> **Status**: Migrated (reverse-engineered from existing implementation)
> **Module**: `services/operator/` (`akces-operator`)

## 1. Technical Context

| Dimension | Value |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.x |
| Operator SDK | Java Operator SDK (JOSDK) with Spring Boot starter |
| Kubernetes Client | Fabric8 Kubernetes Client |
| CRD Generation | `crd-generator-maven-plugin` (Fabric8) |
| Kafka Admin | Spring Kafka (`KafkaAdmin`) |
| Cryptography | BouncyCastle (JCE provider) |
| Build | Maven (child of `akces-framework-services` parent) |
| Artifact | `akces-operator` (Spring Boot executable JAR) |
| Test Stack | JUnit 5, Mockito, Testcontainers (Kafka), JOSDK MockOperator, Spring Boot Test |
| Module Dependencies | None (standalone service; does not depend on `api` or `shared` at compile time) |

## 2. Architectural Decisions

### AD-1: JOSDK with Dependent Resources

The operator uses the Java Operator SDK (JOSDK) `@Workflow` / `@Dependent` pattern rather than manual resource watching. Each reconciler declares three dependent resources (ConfigMap, StatefulSet, Service) that JOSDK manages automatically. This reduces boilerplate and ensures resources are created/updated in the correct order.

### AD-2: StatefulSet over Deployment

All Akces services use StatefulSets (not Deployments) because they rely on stable network identities and persistent volumes for RocksDB state directories. This ensures ordered rolling updates and stable pod DNS names.

### AD-3: YAML Templates + Builder API

Base Kubernetes resource definitions are stored as YAML files in the classpath (co-located with the dependent resource classes). At reconciliation time, these templates are deserialized and customized using the Fabric8 builder API. This separates static structure from dynamic configuration and keeps the Java code focused on the variable parts.

### AD-4: Kafka Topic Provisioning in Reconcilers

Kafka topics are created during reconciliation rather than as a separate init step. The `AggregateReconciler` and `AgenticAggregateReconciler` call `KafkaTopicUtils` at the start of each reconciliation loop. Topic creation is idempotent (`createOrModifyTopics`), so repeated reconciliations are safe.

### AD-5: Partition Count from Akces-Control Topic

The `AggregateReconciler` dynamically discovers the cluster's partition count by reading the `Akces-Control` topic's partition count at startup (`@PostConstruct`). This ensures aggregate topics match the cluster's parallelism level without hardcoding.

### AD-6: Singleton Deployment for AgenticAggregates

AgenticAggregates are always deployed as a single replica. Their Kafka topics always have 1 partition. The `AgenticAggregateReconciler` does not accept a `replicas` field — the replica count is fixed at 1 in the StatefulSet template. This is because AgenticAggregates integrate with AI agents and maintain singleton state.

### AD-7: Sidecar Container Support (Agentic only)

The `AgenticAggregateSpec` supports an optional `sidecars` list for injecting auxiliary containers (e.g., MCP server proxies). Each sidecar maps directly to a Kubernetes Container with full support for env, ports, resources, and probes. This is only available for AgenticAggregates.

### AD-8: KafkaAutoConfiguration Excluded

The operator excludes `KafkaAutoConfiguration` and manually creates a `KafkaAdmin` bean in `AkcesOperatorConfig`. This provides explicit control over the Kafka admin client configuration and avoids auto-creating unnecessary producer/consumer beans.

## 3. Module Structure

```
services/operator/
├── pom.xml
├── src/main/java/org/elasticsoftware/akces/operator/
│   ├── AkcesOperatorApplication.java          # Spring Boot entry point (BouncyCastle setup)
│   ├── AkcesOperatorConfig.java               # Bean definitions for reconcilers + KafkaAdmin
│   ├── utils/
│   │   └── KafkaTopicUtils.java               # Static topic-creation helpers
│   ├── aggregate/                             # Aggregate CRD (7 files)
│   │   ├── Aggregate.java                     # CRD class (@Group, @Version, @Kind)
│   │   ├── AggregateSpec.java                 # Spec: replicas, image, aggregateNames, resources, args, enableSchemaOverwrites, applicationName
│   │   ├── AggregateStatus.java               # Status: readyReplicas
│   │   ├── AggregateReconciler.java           # Reconciler: topics + status
│   │   ├── ConfigMapDependentResource.java    # ConfigMap from template
│   │   ├── ServiceDependentResource.java      # Headless Service from template
│   │   └── StatefulSetDependentResource.java  # StatefulSet from template
│   ├── command/                               # CommandService CRD (7 files)
│   │   ├── CommandService.java
│   │   ├── CommandServiceSpec.java            # Spec: replicas, image, resources, args, applicationName
│   │   ├── CommandServiceStatus.java
│   │   ├── CommandServiceReconciler.java      # Reconciler: status only (no topics)
│   │   ├── ConfigMapDependentResource.java
│   │   ├── ServiceDependentResource.java
│   │   └── StatefulSetDependentResource.java
│   ├── query/                                 # QueryService CRD (7 files)
│   │   ├── QueryService.java
│   │   ├── QueryServiceSpec.java              # Spec: replicas, image, resources, args, applicationName, env, applicationProperties
│   │   ├── QueryServiceStatus.java
│   │   ├── QueryServiceReconciler.java        # Reconciler: status only (no topics)
│   │   ├── ConfigMapDependentResource.java    # ConfigMap with applicationProperties merge
│   │   ├── ServiceDependentResource.java
│   │   └── StatefulSetDependentResource.java  # Merges env vars from spec
│   └── agentic/                               # AgenticAggregate CRD (8 files)
│       ├── AgenticAggregateResource.java
│       ├── AgenticAggregateSpec.java          # Spec: image, applicationName, resources, args, enableSchemaOverwrites,
│       │                                      #   sidecars, env, applicationProperties, imagePullSecret, storageClassName, storageSize
│       ├── AgenticAggregateStatus.java
│       ├── AgenticAggregateReconciler.java    # Reconciler: topics (1 partition) + status
│       ├── SidecarSpec.java                   # Sidecar container specification
│       ├── ConfigMapDependentResource.java    # ConfigMap with applicationProperties append
│       ├── ServiceDependentResource.java
│       └── StatefulSetDependentResource.java  # Sidecar injection, conditional imagePullSecret, configurable storage
├── src/main/resources/
│   ├── application.properties                 # Operator config (Kafka URL, actuator, graceful shutdown)
│   └── org/elasticsoftware/akces/operator/
│       ├── aggregate/                         # YAML templates (configmap.yaml, service.yaml, statefulset.yaml)
│       ├── command/                           # YAML templates
│       ├── query/                             # YAML templates
│       └── agentic/                           # YAML templates
└── src/test/java/org/elasticsoftware/akces/operator/
    ├── AkcesOperatorApplicationTests.java     # Integration test (Testcontainers Kafka, all 4 reconcilers)
    └── AgenticAggregateTopicCreationTest.java # Unit test (KafkaTopicUtils for agentic topics)
```

## 4. Key Design Patterns

### 4.1 CRD Class Pattern
```java
@Group("akces.elasticsoftwarefoundation.org")
@Version("v1")
@Kind("<KindName>")
@ShortNames("<shortname>")
public class <CRD> extends CustomResource<Spec, Status> implements Namespaced {}
```

### 4.2 Reconciler Pattern
```java
@Workflow(dependents = {
    @Dependent(type = ConfigMapDependentResource.class),
    @Dependent(type = StatefulSetDependentResource.class),
    @Dependent(type = ServiceDependentResource.class)
})
@ControllerConfiguration
public class <Name>Reconciler implements Reconciler<CRD> {
    @Override
    public UpdateControl<CRD> reconcile(CRD resource, Context<CRD> context) {
        // Optionally reconcile Kafka topics
        // Read StatefulSet status → patch CR status
        return context.getSecondaryResource(StatefulSet.class)
            .map(ss -> UpdateControl.patchStatus(buildStatusUpdate(resource, ss)))
            .orElseGet(UpdateControl::noUpdate);
    }
}
```

### 4.3 Dependent Resource Pattern
```java
@KubernetesDependent(informer = @Informer(
    labelSelector = "app.kubernetes.io/managed-by=akces-operator"))
public class <Resource>DependentResource extends CRUDKubernetesDependentResource<K8sResource, CRD> {
    @Override
    protected K8sResource desired(CRD primary, Context<CRD> context) {
        // Load YAML template → customize with Fabric8 builder → return desired state
    }
}
```

## 5. Kafka Topic Configuration

### Standard Aggregate Topics (per aggregate name)
| Topic Suffix | Cleanup Policy | Partitions | Replication | Retention |
|---|---|---|---|---|
| `-Commands` | delete | from `Akces-Control` | 1 | -1 (infinite) |
| `-DomainEvents` | delete | from `Akces-Control` | 1 | -1 (infinite) |
| `-AggregateState` | compact | from `Akces-Control` | 1 | -1 (infinite) |

### AgenticAggregate Topics (per resource)
| Topic Suffix | Cleanup Policy | Partitions | Replication | Retention |
|---|---|---|---|---|
| `-Commands` | delete | 1 (always) | configurable (default 3) | -1 |
| `-DomainEvents` | delete | 1 (always) | configurable (default 3) | -1 |
| `-AggregateState` | compact | 1 (always) | configurable (default 3) | -1 |

### Common Topic Settings
- `compression.type=lz4`
- `max.message.bytes=20971520` (20 MB)
- `segment.ms=604800000` (7 days)
- Compact topics: `min.cleanable.dirty.ratio=0.1`, `delete.retention.ms=604800000`

## 6. Evolution Roadmap

The `agentic` package represents the most evolved CRD design with configurable `imagePullSecret`, `storageClassName`, `storageSize`, and sidecar support. The `aggregate`, `command`, and `query` packages still have hardcoded values for these fields (marked with TODO comments in source). A future iteration should align the older CRDs with the agentic model.
