# Client Library — Feature Specification

> **Status:** Migrated
> **Module:** `main/client` (`akces-client`)
> **Package:** `org.elasticsoftware.akces.client`

## 1. Overview

The Client Library module provides the `AkcesClient` interface and its implementation `AkcesClientController` for submitting commands into the Akces CQRS/Event Sourcing system. It handles command serialization, JSON Schema validation, Kafka-based command routing, transactional command sending, asynchronous response correlation, and GDPR-aware event deserialization. It also ships a Spring Boot auto-configuration and a standalone `CommandServiceApplication` entry point.

## 2. User Scenarios

### US-1: Send a command to an aggregate and receive domain events as response

**As** an application developer using the Akces Framework,
**I want** to send a command through `AkcesClient` and receive the resulting domain events,
**so that** I can react to state changes produced by the aggregate.

#### Acceptance Criteria

1. `AkcesClient.send(tenantId, command)` returns a `CompletionStage<List<DomainEvent>>` that completes with the events emitted by the aggregate's command handler.
2. The command is serialized to JSON, wrapped in a `CommandRecord`, and produced to the correct `{Aggregate}-Commands` Kafka topic within a Kafka transaction.
3. The client listens on its assigned `Akces-CommandResponses` partition for a `CommandResponseRecord` whose `commandId` matches the sent command.
4. Domain events in the response are deserialized to their Java types using the locally-registered `DomainEventType` map (resolved via `@DomainEventInfo` classpath scanning).
5. When the response contains a GDPR encryption key, PII-annotated fields (`@PIIData`) are transparently decrypted before returning the events.
6. A correlation ID is either provided by the caller or auto-generated as a UUID.
7. `sendAndForget(tenantId, command)` sends the command and blocks until validation passes but does not wait for a response; it completes the future with an empty list after successful enqueue.

### US-2: Validate command against JSON Schema before sending

**As** an application developer,
**I want** commands to be validated against their registered JSON Schema before they are sent to Kafka,
**so that** I get immediate feedback on malformed commands without waiting for server-side rejection.

#### Acceptance Criteria

1. When the client registers a command type (first use), it fetches the schema from the `SchemaRegistry` (backed by the `Akces-Schemas` Kafka topic) and caches it in `commandSchemasLookup`.
2. Before serialization, the command is converted to a `JsonNode` and validated against its JSON Schema (Draft 7, victools generator with Jakarta Validation and Jackson modules).
3. If validation fails, a `CommandValidationException` is thrown (for `sendAndForget`) or the `CompletionStage` completes exceptionally (for `send`).
4. If serialization to JSON fails, a `CommandSerializationException` is raised.
5. Command classes missing the `@CommandInfo` annotation cause an immediate `IllegalArgumentException`.

### US-3: Handle command routing failures gracefully

**As** an application developer,
**I want** clear, specific exceptions when a command cannot be routed or sent,
**so that** I can diagnose and recover from configuration or infrastructure errors.

#### Acceptance Criteria

1. If no `AggregateServiceRecord` in the `Akces-Control` topic supports the command type/version, an `UnroutableCommandException` is thrown.
2. If the schema for the command type is not yet available in the `SchemaRegistry`, command processing is deferred (command stays at the head of the queue) until the schema is loaded.
3. If a required domain event class for an aggregate service's produced events is not found on the classpath, a `MissingDomainEventException` is thrown.
4. If the command's schema identifier is unknown to the system, an `UnknownSchemaException` is thrown.
5. If a Kafka send fails, a `CommandSendingFailedException` completes the future exceptionally with the underlying Kafka exception as its cause.
6. If the client is in `SHUTTING_DOWN` state, a `CommandRefusedException` is thrown synchronously.
7. Unrecoverable `KafkaException` during the control loop transitions the client to `SHUTTING_DOWN` and publishes a `LivenessState.BROKEN` availability event.

### US-4: Auto-configure client in a Spring Boot application

**As** a Spring Boot application developer,
**I want** the `AkcesClient` to be auto-configured when I include the `akces-client` dependency,
**so that** I can inject and use it without manual bean wiring.

#### Acceptance Criteria

1. `AkcesClientAutoConfiguration` is registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
2. It creates qualified beans for: Kafka producer factory (`akcesClientProducerFactory`), control consumer factory, command-response consumer factory, schema consumer factory, `KafkaAdmin`, `SchemaRecordSerde`, `AkcesControlRecordSerde`, Jackson customizer (BigDecimal + GDPR module), domain-event classpath scanner, and `EnvironmentPropertiesPrinter` (conditional on missing).
3. The `AkcesClientController` bean is created with `initMethod = "start"` (thread start) and `destroyMethod = "close"` (graceful shutdown with 10-second latch).
4. Default Kafka consumer/producer properties are loaded from `akces-client.properties` (read-committed isolation, idempotent producer, cooperative-sticky assignor).
5. The application property `akces.client.domainEventsPackage` configures the base package for domain event classpath scanning; built-in error events from `org.elasticsoftware.akces.errors` are always loaded.
6. `CommandServiceApplication` excludes `KafkaAutoConfiguration` and accepts command-line arguments as additional component-scan source packages.

## 3. Domain Model

### 3.1 Public API

| Type | Name | Description |
|------|------|-------------|
| Interface | `AkcesClient` | Primary client API with `send()` and `sendAndForget()` overloads |
| Class | `AkcesClientController` | Thread-based implementation; manages Kafka producers/consumers, control loop, command queue, and response correlation |
| Enum | `AkcesClientControllerState` | `INITIALIZING → RUNNING → SHUTTING_DOWN` lifecycle states |
| Class | `AkcesClientAutoConfiguration` | Spring `@Configuration` with all bean definitions |
| Class | `CommandServiceApplication` | `@SpringBootApplication` entry point |

### 3.2 Exception Hierarchy

| Exception | Parent | Trigger |
|-----------|--------|---------|
| `AkcesClientCommandException` | `RuntimeException` | Abstract base for all client command errors |
| `CommandRefusedException` | `AkcesClientCommandException` | Client not in `RUNNING` state |
| `CommandSendingFailedException` | `AkcesClientCommandException` | Kafka producer failure |
| `CommandSerializationException` | `AkcesClientCommandException` | Jackson serialization error |
| `CommandValidationException` | `AkcesClientCommandException` | JSON Schema validation failure |
| `UnroutableCommandException` | `AkcesClientCommandException` | No aggregate service handles the command |
| `UnknownSchemaException` | `AkcesClientCommandException` | Schema identifier not registered |
| `MissingDomainEventException` | `AkcesClientCommandException` | Required domain event class not on classpath |

### 3.3 Internal Records

| Record | Purpose |
|--------|---------|
| `CommandRequest` | Queued command with tenant, correlation ID, future, and validation flag |
| `PendingCommandResponse` | Maps a sent `CommandRecord` to its `CompletableFuture` |

## 4. Kafka Topology

| Topic | Role | Access Pattern |
|-------|------|----------------|
| `Akces-Control` | Discover `AggregateServiceRecord`s (command types, event types, topics) | Consumer — partition 0, seek-to-beginning on init |
| `Akces-Schemas` | Load JSON Schemas for commands and domain events | Consumer via `KafkaTopicSchemaStorage` |
| `Akces-CommandResponses` | Receive `CommandResponseRecord` with emitted domain events | Consumer — hash-assigned partition, seek-to-end |
| `{Aggregate}-Commands` | Send `CommandRecord` per command | Producer — transactional, hash-partitioned by aggregate ID |

## 5. Concurrency Model

- `AkcesClientController` extends `Thread`; its `run()` method is the single control loop.
- Commands are submitted to a `LinkedBlockingQueue` from any application thread.
- The control loop drains the queue, serializes/validates, and sends within a Kafka transaction.
- `CompletableFuture` instances bridge the calling thread and the control-loop thread.
- Shutdown uses a `CountDownLatch` with 10-second timeout; pending commands are completed exceptionally with `CommandRefusedException`.

## 6. Dependencies

| Dependency | Purpose |
|------------|---------|
| `akces-api` | Core interfaces (`Command`, `DomainEvent`, annotations) |
| `akces-shared` | Schema registry, GDPR support, Kafka utilities, serialization |
| `spring-boot-autoconfigure` | Auto-configuration |
| `spring-kafka` / `kafka-clients` / `kafka-streams` | Kafka producer/consumer/admin |
| `jackson-dataformat-protobuf` | Protocol record serialization |
| `victools jsonschema-generator` | JSON Schema generation and validation |
| `jakarta.validation-api` | Bean validation annotations for schema generation |
| `guava` | Murmur3 hashing for partition assignment |

## 7. Non-Functional Requirements

- **Exactly-once semantics:** Kafka transactional producer with idempotence enabled.
- **Read-committed isolation:** Consumers only see committed records.
- **Graceful shutdown:** 10-second drain period; liveness state published on unrecoverable errors.
- **Thread safety:** `ConcurrentHashMap` for all lookup caches; `volatile` state field; `BlockingQueue` for command submission.

## 8. Out of Scope

- Aggregate-side command handling and event sourcing (handled by `akces-runtime`).
- Schema registration/creation (handled by aggregate services via `akces-shared`).
- Query model projections (handled by `akces-query-support`).
- Kubernetes operator deployment configuration.
