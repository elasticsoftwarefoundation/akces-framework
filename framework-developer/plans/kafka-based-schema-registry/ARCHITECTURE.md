# Technical Architecture Diagrams

## Current Architecture (Before)

```
┌───────────────────────────────────────────────────────────────────┐
│                        Akces Application                          │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐          │
│  │  Aggregate  │  │   Command    │  │  Query Model   │          │
│  │   Service   │  │   Service    │  │    Service     │          │
│  └──────┬──────┘  └──────┬───────┘  └────────┬───────┘          │
│         │                │                    │                  │
│         └────────────────┼────────────────────┘                  │
│                          ▼                                        │
│              ┌─────────────────────┐                             │
│              │ KafkaSchemaRegistry │                             │
│              └──────────┬──────────┘                             │
│                         │                                         │
│                         ▼                                         │
│              ┌──────────────────────┐                            │
│              │ SchemaRegistryClient │                            │
│              │  (Confluent Library) │                            │
│              └──────────┬───────────┘                            │
└───────────────────────────┼───────────────────────────────────────┘
                            │
                            │ HTTP/REST
                            ▼
┌────────────────────────────────────────────────────────┐
│          Confluent Schema Registry Service             │
│                  (External Dependency)                 │
│                                                        │
│  ┌──────────────────────────────────────────────┐    │
│  │  Schema Storage (Kafka Topic: _schemas)      │    │
│  └──────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────┘
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│                    Kafka Cluster                       │
└────────────────────────────────────────────────────────┘
```

**Issues:**
- ❌ External service dependency (Schema Registry)
- ❌ Additional HTTP service to manage
- ❌ Extra network hop for schema operations
- ❌ Separate deployment, monitoring, scaling
- ❌ Another point of failure

---

## New Architecture (After)

```
┌───────────────────────────────────────────────────────────────────┐
│                        Akces Application                          │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐          │
│  │  Aggregate  │  │   Command    │  │  Query Model   │          │
│  │   Service   │  │   Service    │  │    Service     │          │
│  └──────┬──────┘  └──────┬───────┘  └────────┬───────┘          │
│         │                │                    │                  │
│         └────────────────┼────────────────────┘                  │
│                          ▼                                        │
│              ┌─────────────────────┐                             │
│              │ KafkaSchemaRegistry │ (Unchanged API)             │
│              └──────────┬──────────┘                             │
│                         │                                         │
│         ┌───────────────┴────────────────┐                       │
│         ▼                                ▼                       │
│  ┌──────────────────┐          ┌──────────────────────┐         │
│  │ Confluent Libs   │          │ KafkaTopicSchema     │ ◄── NEW │
│  │ (SchemaDiff,     │          │ Storage              │         │
│  │  JsonSchema)     │          └──────────┬───────────┘         │
│  │ (Validation      │                     │                     │
│  │  Only)           │                     │                     │
│  └──────────────────┘                     │                     │
│                                            │                     │
│                                   ┌────────▼────────┐            │
│                                   │  Caffeine Cache │            │
│                                   │  (In-Memory)    │            │
│                                   └────────┬────────┘            │
└────────────────────────────────────────────┼─────────────────────┘
                                             │
                                             │ Kafka Protocol
                                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Kafka Cluster                            │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Topic: akces-schemas (NEW)                              │  │
│  │  ─────────────────────────────────────                   │  │
│  │  Cleanup: compact                                        │  │
│  │  Partitions: 1                                           │  │
│  │  Replication: 3                                          │  │
│  │                                                          │  │
│  │  Records:                                                │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │ Key: commands-CreateWalletCommand-v1             │  │  │
│  │  │ Value: {schema JSON, version, timestamp}         │  │  │
│  │  ├──────────────────────────────────────────────────┤  │  │
│  │  │ Key: commands-CreateWalletCommand-v2             │  │  │
│  │  │ Value: {schema JSON, version, timestamp}         │  │  │
│  │  ├──────────────────────────────────────────────────┤  │  │
│  │  │ Key: domainevents-WalletCreatedEvent-v1          │  │  │
│  │  │ Value: {schema JSON, version, timestamp}         │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Other Akces Topics (Commands, Events, etc.)            │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

**Benefits:**
- ✅ No external service dependency
- ✅ One less service to deploy/manage
- ✅ Direct Kafka access (no HTTP hop)
- ✅ Unified infrastructure (just Kafka)
- ✅ Simpler architecture

---

## Component Interaction Flow

### Schema Registration Flow

```
┌─────────────┐
│ Application │
│  Startup    │
└──────┬──────┘
       │ 1. Initialize
       ▼
┌──────────────────────┐
│ KafkaSchemaRegistry  │
└──────┬───────────────┘
       │ 2. registerAndValidate(schemaType)
       ▼
┌──────────────────────┐
│ Generate JSON Schema │
│ (Confluent libs)     │
└──────┬───────────────┘
       │ 3. JsonSchema object
       ▼
┌──────────────────────────┐
│ KafkaTopicSchemaStorage  │
└──────┬───────────────────┘
       │ 4. Check cache
       ▼
┌──────────────────────┐
│  Caffeine Cache      │
└──────┬───────────────┘
       │ 5. Cache miss
       ▼
┌──────────────────────┐
│  Read from Topic     │
│  (Consumer poll)     │
└──────┬───────────────┘
       │ 6. Existing schemas
       ▼
┌──────────────────────┐
│  Validate with       │
│  SchemaDiff          │
│  (Confluent lib)     │
└──────┬───────────────┘
       │ 7. Compatible?
       ▼
┌──────────────────────┐     YES      ┌──────────────────────┐
│  Write to Topic      │ ◄────────────│  Compatibility Check │
│  (Producer send)     │              └──────────────────────┘
└──────┬───────────────┘                       │ NO
       │                                       ▼
       │                              ┌──────────────────────┐
       │                              │  Throw Exception     │
       │                              └──────────────────────┘
       │ 8. Update cache
       ▼
┌──────────────────────┐
│  Update Caffeine     │
│  Cache               │
└──────┬───────────────┘
       │ 9. Return
       ▼
┌──────────────────────┐
│  Application         │
│  Ready               │
└──────────────────────┘
```

### Schema Validation Flow (Existing Schema)

```
┌─────────────┐
│ Application │
│   Runtime   │
└──────┬──────┘
       │ 1. validate(eventType)
       ▼
┌──────────────────────┐
│ KafkaSchemaRegistry  │
└──────┬───────────────┘
       │ 2. Get schema
       ▼
┌──────────────────────────┐
│ KafkaTopicSchemaStorage  │
└──────┬───────────────────┘
       │ 3. Check cache
       ▼
┌──────────────────────┐
│  Caffeine Cache      │
└──────┬───────────────┘
       │ 4. Cache HIT!
       ▼
┌──────────────────────┐
│  Return cached       │
│  schema (<1ms)       │
└──────┬───────────────┘
       │ 5. Schema
       ▼
┌──────────────────────┐
│  Validate structure  │
│  (Confluent libs)    │
└──────┬───────────────┘
       │ 6. Valid/Invalid
       ▼
┌──────────────────────┐
│  Return result       │
└──────────────────────┘
```

---

## Data Model

### Schema Topic Record

```json
{
  "key": "commands-CreateWalletCommand-v1",
  "value": {
    "schemaName": "CreateWalletCommand",
    "version": 1,
    "schema": {
      "$schema": "http://json-schema.org/draft-07/schema#",
      "type": "object",
      "properties": {
        "id": {
          "type": "string"
        },
        "currency": {
          "type": "string"
        }
      },
      "required": ["id", "currency"],
      "additionalProperties": false
    },
    "registeredAt": "2025-11-09T11:34:51.453Z"
  },
  "headers": {
    "source": "aggregate-service",
    "version": "0.10.1"
  }
}
```

### Cache Entry

```java
record CachedSchema(
    String schemaName,
    int version,
    JsonSchema schema,
    Instant cachedAt,
    Instant expiresAt
) {}
```

---

## Deployment Comparison

### Before (with Schema Registry)

```yaml
# Kubernetes Deployment
apiVersion: v1
kind: Service
metadata:
  name: schema-registry
spec:
  selector:
    app: schema-registry
  ports:
  - port: 8081

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: schema-registry
spec:
  replicas: 2  # Need HA
  template:
    spec:
      containers:
      - name: schema-registry
        image: confluentinc/cp-schema-registry:7.8.1
        ports:
        - containerPort: 8081
        env:
        - name: SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS
          value: kafka:9092
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"

---
# Application config
apiVersion: v1
kind: ConfigMap
metadata:
  name: akces-config
data:
  application.properties: |
    akces.schemaregistry.url=http://schema-registry:8081
```

**Resource Requirements:**
- Schema Registry: 512Mi-1Gi memory, 250m-500m CPU
- Additional service to monitor
- Additional network hop

### After (Kafka-based)

```yaml
# NO separate Schema Registry deployment needed!

# Application config
apiVersion: v1
kind: ConfigMap
metadata:
  name: akces-config
data:
  application.properties: |
    akces.schemas.storage=kafka
    akces.schemas.topic=akces-schemas
    spring.kafka.bootstrap-servers=kafka:9092
```

**Resource Requirements:**
- ✅ No additional services
- ✅ No extra memory/CPU
- ✅ Fewer things to monitor
- ✅ Simpler deployment

---

## Performance Characteristics

### Operation Latency Comparison

| Operation | Schema Registry (HTTP) | Kafka Topic | Improvement |
|-----------|------------------------|-------------|-------------|
| Initial Load | 50-100ms (HTTP GET) | 100-200ms (topic read) | -50% slower |
| Cached Read | <1ms (memory) | <1ms (memory) | **Same** |
| Write | 50-150ms (HTTP POST) | 50-200ms (Kafka write) | **Similar** |
| Validation | 10-50ms | 10-50ms | **Same** |
| Cold Start | 500ms-1s | 1-2s | -50% slower |
| Steady State | <1ms | <1ms | **Same** |

**Analysis:**
- ✅ Cached operations same performance (most common case)
- ⚠️ Cold start slightly slower (one-time, acceptable)
- ✅ Write performance similar
- ✅ Validation performance identical

### Memory Usage

| Component | Schema Registry | Kafka Topic |
|-----------|----------------|-------------|
| External Service | 512Mi-1Gi | 0 |
| Application Cache | Minimal | 1-10MB |
| **Total** | **512Mi-1Gi** | **1-10MB** |

**Savings**: 500Mi-1Gi per deployment!

---

## Migration Scenarios

### Scenario 1: New Installation
```
┌─────────────────┐
│ Deploy Akces    │
│ with config:    │
│ storage=kafka   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Auto-create     │
│ akces-schemas   │
│ topic           │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Ready to use!   │
└─────────────────┘
```

### Scenario 2: Existing with Registry
```
┌─────────────────────┐
│ Existing deployment │
│ with registry       │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Deploy new version  │
│ storage=registry    │ ◄── Keep existing mode
│ (no changes)        │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Validate everything │
│ works               │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Switch to           │
│ storage=kafka       │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Monitor & validate  │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Remove registry     │
│ deployment (done!)  │
└─────────────────────┘
```

---

## Error Handling

### Connection Failures

```
┌──────────────────┐
│ Kafka unavailable│
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Retry with       │
│ exponential      │
│ backoff          │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐      Timeout
│ Circuit breaker  │ ───────────► ┌──────────────────┐
│ (after 3 fails)  │              │ Fail application │
└────────┬─────────┘              │ startup          │
         │                        └──────────────────┘
         │ Success
         ▼
┌──────────────────┐
│ Continue normal  │
│ operation        │
└──────────────────┘
```

### Schema Conflicts

```
┌──────────────────┐
│ Incompatible     │
│ schema detected  │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Log detailed     │
│ differences      │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐      forceRegister=true
│ Check config     │ ───────────────────────► ┌──────────────────┐
└────────┬─────────┘                          │ Delete + re-write│
         │                                    │ (WARN in logs)   │
         │ forceRegister=false                └──────────────────┘
         ▼
┌──────────────────┐
│ Throw exception  │
│ (fail fast)      │
└──────────────────┘
```

---

## Security Considerations

### Access Control

```
┌─────────────────────────────────────────┐
│         Kafka ACLs (Recommended)        │
├─────────────────────────────────────────┤
│                                         │
│ Topic: akces-schemas                    │
│ ─────────────────────────                │
│ • READ: aggregate-service               │
│ • READ: command-service                 │
│ • READ: query-service                   │
│ • WRITE: aggregate-service (only)       │
│ • CREATE: aggregate-service (only)      │
│                                         │
└─────────────────────────────────────────┘
```

**Benefits:**
- Only aggregate service can register schemas
- All services can read for validation
- Prevent unauthorized schema modifications
- Audit trail in Kafka logs

### Data Protection

```
┌─────────────────────────────────────────┐
│         Optional Encryption             │
├─────────────────────────────────────────┤
│                                         │
│ • Topic encryption at rest              │
│ • TLS for Kafka connections             │
│ • mTLS for service authentication       │
│                                         │
└─────────────────────────────────────────┘
```

---

This architecture provides a cleaner, simpler, and more cost-effective solution while maintaining all existing functionality and validation capabilities.
