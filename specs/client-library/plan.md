# Client Library — Implementation Plan

> **Status:** Migrated
> **Module:** `main/client` (`akces-client`)

## 1. Technical Context

| Attribute | Value |
|-----------|-------|
| Language | Java 25+ |
| Framework | Spring Boot 4.x |
| Messaging | Apache Kafka 4.x (KRaft mode) |
| Serialization | Jackson 3.x (`tools.jackson`), Protobuf (protocol records) |
| Schema Validation | victools jsonschema-generator (Draft 7) with Jakarta Validation + Jackson modules |
| Build | Maven (parent `akces-framework-main`) |
| Test | JUnit 5, Testcontainers (Kafka), Mockito, Spring Boot Test |
| Coverage | JaCoCo (82.5% instruction, 100% method/class) |

### Internal Dependencies

| Artifact | Provides |
|----------|----------|
| `akces-api` | `Command`, `DomainEvent`, `ErrorEvent`, annotations (`@CommandInfo`, `@DomainEventInfo`, `@AggregateIdentifier`, `@PIIData`) |
| `akces-shared` | `SchemaRegistry`, `KafkaSchemaRegistry`, `KafkaTopicSchemaStorage`, `JsonSchema`, `ProtocolRecordSerde`, `AkcesControlRecordSerde`, `SchemaRecordSerde`, `KafkaSender`, `HostUtils`, `GDPRKeyUtils`, `EncryptingGDPRContext`, `GDPRContextHolder`, `AkcesGDPRModule`, `BigDecimalSerializer`, `EnvironmentPropertiesPrinter` |

## 2. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                     Application Thread(s)                        │
│  akcesClient.send(tenantId, command)                             │
│       │                                                          │
│       ▼                                                          │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  AkcesClient interface                                      │ │
│  │  → validates @CommandInfo annotation                        │ │
│  │  → enqueues CommandRequest on LinkedBlockingQueue            │ │
│  │  → returns CompletableFuture<List<DomainEvent>>             │ │
│  └─────────────────────────────────────────────────────────────┘ │
└────────────────────────┬─────────────────────────────────────────┘
                         │ BlockingQueue
┌────────────────────────▼─────────────────────────────────────────┐
│              AkcesClientController Thread                         │
│                                                                   │
│  INITIALIZING:                                                    │
│    • Read Akces-Control partition 0 to discover services          │
│    • Read Akces-Schemas via SchemaStorage                         │
│    • Transition to RUNNING when caught up                         │
│                                                                   │
│  RUNNING (main loop):                                             │
│    1. schemaStorage.process()     ← update schemas                │
│    2. poll Akces-Control          ← discover new services         │
│    3. drain commandQueue          ← process pending commands      │
│       a. registerCommand()        ← resolve route + schema        │
│       b. serialize()              ← JSON Schema validate + write  │
│       c. Kafka txn send           ← to {Aggregate}-Commands      │
│    4. poll Akces-CommandResponses  ← correlate responses          │
│       a. deserialize events       ← GDPR decrypt if key present  │
│       b. complete CompletableFuture                               │
│                                                                   │
│  SHUTTING_DOWN:                                                   │
│    • Drain queue, complete futures exceptionally                  │
│    • Publish LivenessState.BROKEN                                 │
│    • Count down shutdown latch                                    │
└───────────────────────────────────────────────────────────────────┘
```

## 3. Component Inventory

### 3.1 Source Files (13)

| # | File | Responsibility | Lines |
|---|------|---------------|-------|
| 1 | `AkcesClient.java` | Public interface with `send()` / `sendAndForget()` overloads, default tenant constant | ~78 |
| 2 | `AkcesClientController.java` | Thread-based implementation: control loop, command queue, Kafka I/O, schema registration, partition resolution, GDPR deserialization | ~640 |
| 3 | `AkcesClientControllerState.java` | Lifecycle enum: `INITIALIZING`, `RUNNING`, `SHUTTING_DOWN` | ~25 |
| 4 | `AkcesClientAutoConfiguration.java` | Spring `@Configuration`: Kafka factories, serde beans, Jackson customizer, domain event scanner, client bean | ~148 |
| 5 | `CommandServiceApplication.java` | `@SpringBootApplication` entry point with optional package scanning | ~40 |
| 6 | `AkcesClientCommandException.java` | Abstract base exception with command class, type, version | ~61 |
| 7 | `CommandRefusedException.java` | Client not in RUNNING state | ~36 |
| 8 | `CommandSendingFailedException.java` | Kafka producer send failure | ~29 |
| 9 | `CommandSerializationException.java` | Jackson serialization error | ~30 |
| 10 | `CommandValidationException.java` | JSON Schema validation failure | ~30 |
| 11 | `UnroutableCommandException.java` | No aggregate service handles the command | ~28 |
| 12 | `UnknownSchemaException.java` | Unknown schema identifier | ~36 |
| 13 | `MissingDomainEventException.java` | Required domain event class not on classpath | ~43 |

### 3.2 Test Files (6)

| # | File | Responsibility |
|---|------|---------------|
| 1 | `AkcesClientTests.java` | 9 integration tests: send, send-with-correlationId, sendAndForget, unroutable, invalid, validation error, GDPR decryption; Testcontainers Kafka setup with control records, schema publishing |
| 2 | `AkcesClientTestConfiguration.java` | Minimal Spring test config |
| 3 | `commands/CreateAccountCommand.java` | Test command fixture with `@CommandInfo`, `@AggregateIdentifier`, Jakarta validations |
| 4 | `commands/UnroutableCommand.java` | Command with no matching aggregate service |
| 5 | `commands/InvalidCommand.java` | Command without `@CommandInfo` annotation |
| 6 | `events/AccountCreatedEvent.java` | Test domain event with `@PIIData` fields |

### 3.3 Resources

| File | Purpose |
|------|---------|
| `akces-client.properties` | Default Kafka consumer/producer settings (read-committed, idempotent, cooperative-sticky) |
| `akces-schemas.properties` | Schema-related defaults |
| `META-INF/spring/...AutoConfiguration.imports` | Spring Boot auto-configuration registration |

## 4. Key Design Decisions

### D-1: Single-threaded control loop

The `AkcesClientController` extends `Thread` and runs a single event loop. This avoids thread-safety issues in Kafka consumer/producer usage (which are not thread-safe) while allowing multi-threaded command submission via the blocking queue.

### D-2: Transactional command batching

All commands drained from the queue in one iteration are sent within a single Kafka transaction. This provides atomicity for batch sends while maintaining exactly-once semantics.

### D-3: Hash-based partition assignment

Commands are routed to Kafka partitions using Murmur3-32 hashing of the aggregate ID. This ensures all commands for the same aggregate land on the same partition (ordering guarantee). Agentic service types always use partition 0.

### D-4: Deferred command processing

If the schema registry hasn't loaded a command's schema yet, the command stays at the head of the queue (`peek` without `poll`) and processing is retried on the next loop iteration. This handles startup races gracefully.

### D-5: Response partition assignment

Each client instance is assigned a `Akces-CommandResponses` partition based on the Murmur3 hash of its hostname. The `commandResponsePartition` field in `CommandRecord` tells the aggregate service where to send the response.

### D-6: Domain event version resolution

Domain events are resolved using `TreeMap.floorEntry()`, allowing a client to deserialize events from newer server versions as long as a compatible local version exists (backward compatibility via schema evolution).

## 5. Integration Points

| System | Protocol | Direction |
|--------|----------|-----------|
| Kafka (`Akces-Control`) | Custom `AkcesControlRecord` serde | Consume |
| Kafka (`Akces-Schemas`) | Custom `SchemaRecord` serde | Consume |
| Kafka (`Akces-CommandResponses`) | `ProtocolRecord` serde (Protobuf) | Consume |
| Kafka (`{Aggregate}-Commands`) | `ProtocolRecord` serde (Protobuf) | Produce (transactional) |
| Spring Boot | Auto-configuration, `ApplicationContextAware`, `AvailabilityChangeEvent` | Framework |

## 6. Risk & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Schema registry not ready at startup | Commands stuck in queue | Deferred processing (D-4); INITIALIZING state blocks command sending |
| Aggregate service discovery lag | Unroutable commands | Control topic consumed from beginning; queue retry on `SchemaException` |
| Kafka broker unavailable | Unrecoverable exception | Transition to SHUTTING_DOWN; publish LivenessState.BROKEN for health checks |
| Command response partition collision | Multiple clients on same partition | Acceptable — unknown command IDs are silently traced at TRACE level |
| PII data without encryption key | Potential data exposure | GDPR context only set when `encryptionKey != null`; transparent decrypt via `AkcesGDPRModule` |

## 7. Test Strategy

| Layer | Tool | Scope |
|-------|------|-------|
| Integration | Testcontainers Kafka + Spring Boot Test | Full command lifecycle: send → produce → consume response → complete future |
| Validation | In-test schema generation | JSON Schema validation errors for missing required fields |
| Error paths | Direct assertions | Unroutable commands, invalid commands (no annotation), refused commands |
| GDPR | In-test encryption | Encrypt PII fields, verify transparent decryption in response |
| Coverage | JaCoCo | 82.5% instruction, 100% method/class |
