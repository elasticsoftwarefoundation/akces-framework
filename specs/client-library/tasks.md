# Client Library — Tasks

> **Status:** Migrated (all tasks completed)
> **Module:** `main/client` (`akces-client`)

## Tasks

### 1. Core Client Interface

- [x] **T-1.1** Define `AkcesClient` interface with `send()` overloads accepting `(tenantId, correlationId, command)`, `(tenantId, command)`, `(command, correlationId)`, and `(command)`
- [x] **T-1.2** Define `sendAndForget()` overloads with the same parameter variants
- [x] **T-1.3** Define `DEFAULT_TENANT_ID` constant (`"DefaultTenant"`)
- [x] **T-1.4** Return `CompletionStage<List<DomainEvent>>` from `send()` to enable async/reactive usage

### 2. Client Controller Implementation

- [x] **T-2.1** Implement `AkcesClientController` extending `Thread` with single-threaded control loop
- [x] **T-2.2** Implement `INITIALIZING` phase: consume `Akces-Control` partition 0 from beginning; transition to `RUNNING` when caught up to end offsets
- [x] **T-2.3** Implement `RUNNING` phase main loop: poll schemas, poll control records, process command queue, poll command responses
- [x] **T-2.4** Implement `SHUTTING_DOWN` phase: drain queue with `CommandRefusedException`, publish `LivenessState.BROKEN`, count down shutdown latch
- [x] **T-2.5** Implement `close()` method with 10-second `CountDownLatch` timeout
- [x] **T-2.6** Implement `ApplicationContextAware` for availability event publishing

### 3. Command Processing Pipeline

- [x] **T-3.1** Implement `send()`: validate `@CommandInfo` annotation, enqueue `CommandRequest` with `completeAfterValidation=false`
- [x] **T-3.2** Implement `sendAndForget()`: validate `@CommandInfo`, enqueue with `completeAfterValidation=true`, block on `CompletableFuture.get()` to surface validation errors synchronously
- [x] **T-3.3** Implement `processCommands()`: drain queue, register command types, resolve topics, serialize, send within Kafka transaction
- [x] **T-3.4** Implement deferred command processing: when `SchemaException` is caught during `registerCommand()`, return without polling (command stays at queue head)
- [x] **T-3.5** Implement `registerCommand()`: resolve command type from `AggregateServiceRecord`, validate schema via `SchemaRegistry`, cache in `commandSchemasLookup`, process produced domain event types
- [x] **T-3.6** Implement `processDomainEvent()`: resolve local domain event class via `TreeMap.floorEntry()`, validate schema, cache; throw `MissingDomainEventException` if not found

### 4. Command Serialization and Validation

- [x] **T-4.1** Implement `serialize()`: convert command to `JsonNode`, validate against cached JSON Schema, write as bytes
- [x] **T-4.2** Throw `CommandValidationException` on `ValidationException` from JSON Schema validation
- [x] **T-4.3** Throw `CommandSerializationException` on `JacksonException` during JSON serialization

### 5. Command Routing and Partitioning

- [x] **T-5.1** Implement `resolveTopic()`: find single matching `AggregateServiceRecord`; throw `UnroutableCommandException` if none; use `resolveAgenticTarget()` for multi-service commands
- [x] **T-5.2** Implement `resolvePartition(commandType, version, aggregateId)`: return 0 for `AGENTIC` service types; Murmur3-32 hash of aggregate ID mod partition count for `STANDARD`
- [x] **T-5.3** Implement `resolveCommandResponsePartition()`: Murmur3-32 hash of hostname mod command-response partition count

### 6. Response Processing

- [x] **T-6.1** Implement `processCommandResponses()`: match `CommandResponseRecord.commandId()` to `pendingCommandResponseMap`; trace-log unknown IDs
- [x] **T-6.2** Implement `deserialize(DomainEventRecord, encryptionKey)`: set GDPR context when encryption key present, resolve domain event type via `TreeMap.floorEntry()`, deserialize with ObjectMapper
- [x] **T-6.3** Complete `CompletableFuture` with deserialized `List<DomainEvent>` on success; complete exceptionally with `JacksonException` on deserialization failure

### 7. Control Record Processing

- [x] **T-7.1** Implement `processControlRecords()`: handle `AggregateServiceRecord` instances, store/overwrite in `aggregateServices` map, log service discovery
- [x] **T-7.2** Implement `loadSupportedDomainEvents()`: classpath scan via `ClassPathScanningCandidateComponentProvider` with `@DomainEventInfo` filter, populate `domainEventClasses` TreeMap
- [x] **T-7.3** Load built-in error events from `org.elasticsoftware.akces.errors` package

### 8. Exception Hierarchy

- [x] **T-8.1** Implement `AkcesClientCommandException` abstract base with command class, type, version fields
- [x] **T-8.2** Implement `CommandRefusedException` with `AkcesClientControllerState`
- [x] **T-8.3** Implement `CommandSendingFailedException` wrapping Kafka `Throwable`
- [x] **T-8.4** Implement `CommandSerializationException` wrapping Jackson `Throwable`
- [x] **T-8.5** Implement `CommandValidationException` wrapping validation `Throwable`
- [x] **T-8.6** Implement `UnroutableCommandException` for unresolvable command types
- [x] **T-8.7** Implement `UnknownSchemaException` for unregistered schema identifiers
- [x] **T-8.8** Implement `MissingDomainEventException` for missing local domain event classes

### 9. Spring Boot Auto-Configuration

- [x] **T-9.1** Create `AkcesClientAutoConfiguration` with `@Configuration` and `@PropertySource("classpath:akces-client.properties")`
- [x] **T-9.2** Define `akcesClientProducerFactory` bean (transactional `ProtocolRecord` producer with `StringSerializer` keys)
- [x] **T-9.3** Define `akcesClientControlConsumerFactory` bean (`AkcesControlRecord` consumer)
- [x] **T-9.4** Define `akcesClientCommandResponseConsumerFactory` bean (`ProtocolRecord` consumer)
- [x] **T-9.5** Define `akcesClientSchemaConsumerFactory` bean (`SchemaRecord` consumer)
- [x] **T-9.6** Define `akcesClientKafkaAdmin` bean
- [x] **T-9.7** Define `akcesClientJsonCustomizer` bean (BigDecimal serializer + GDPR module)
- [x] **T-9.8** Define `domainEventScanner` bean with `@DomainEventInfo` annotation filter
- [x] **T-9.9** Define `akcesClient` bean with `initMethod="start"`, `destroyMethod="close"`, injecting all factories + `akces.client.domainEventsPackage`
- [x] **T-9.10** Define `environmentPropertiesPrinter` bean with `@ConditionalOnMissingBean`
- [x] **T-9.11** Register auto-configuration in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

### 10. Command Service Application

- [x] **T-10.1** Create `CommandServiceApplication` with `@SpringBootApplication(exclude = KafkaAutoConfiguration.class)`
- [x] **T-10.2** Enable `KafkaProperties` configuration properties
- [x] **T-10.3** Accept command-line arguments as additional component-scan source packages via `application.setSources()`

### 11. Default Configuration

- [x] **T-11.1** Create `akces-client.properties` with consumer settings: `read_committed` isolation, cooperative-sticky assignor, auto-commit disabled, 500 max-poll-records
- [x] **T-11.2** Create `akces-client.properties` with producer settings: `acks=all`, idempotent, max-in-flight 1, infinite retries

### 12. Integration Tests

- [x] **T-12.1** Set up Testcontainers Kafka (KRaft mode) with required topics (Control, CommandResponses, Schemas, aggregate-specific)
- [x] **T-12.2** Publish test command schemas (`CreateAccountCommand`) and domain event schemas (`AccountCreatedEvent`) to `Akces-Schemas` topic
- [x] **T-12.3** Publish `AggregateServiceRecord` for Account aggregate to `Akces-Control` topic
- [x] **T-12.4** Test `testSendCommand`: send command, produce mock `CommandResponseRecord`, verify future completes with deserialized domain events
- [x] **T-12.5** Test `testSendCommandWithCorrelationId`: verify correlation ID propagates through command record
- [x] **T-12.6** Test `testGDPRDecryption`: encrypt PII fields in response, verify transparent decryption
- [x] **T-12.7** Test `testUnroutableCommandWithSendAndForget`: verify `UnroutableCommandException` thrown synchronously
- [x] **T-12.8** Test `testUnroutableCommandWithSend`: verify future completes exceptionally with `UnroutableCommandException`
- [x] **T-12.9** Test `testInvalidCommandWithSendAndForget`: verify `IllegalArgumentException` for missing `@CommandInfo`
- [x] **T-12.10** Test `testInvalidCommandWithSend`: verify `IllegalArgumentException` for missing `@CommandInfo`
- [x] **T-12.11** Test `testValidationErrorWithSendAndForget`: verify `CommandValidationException` for null required field
- [x] **T-12.12** Test `testValidationErrorWithSend`: verify future completes exceptionally with `CommandValidationException`

---

## Gap Analysis

### Identified Gaps (existing TODOs in source code)

| ID | Location | Description | Severity | Status |
|----|----------|-------------|----------|--------|
| **GAP-1** | `AkcesClientController.java:364-365` | TODO: ErrorEvent instances in command responses are returned as regular domain events; no special handling or differentiation | Low | Open |
| **GAP-2** | `AkcesClientController.java:367-368` | TODO: Deserialization failure wraps raw `JacksonException`; should use a framework-specific exception type | Low | Open |
| **GAP-3** | `AkcesClientController.java:547` | TODO: Produced domain events are resolved at the service level, not per-command; all service events are registered for every command type | Low | Open |
| **GAP-4** | `AkcesClientController.java:197-198` | TODO: ClassCastException silently ignored when a class annotated with `@DomainEventInfo` does not implement `DomainEvent` | Low | Open |

### Test Coverage Gaps

| ID | Description | Severity |
|----|-------------|----------|
| **GAP-T1** | No test for `CommandSendingFailedException` (Kafka send failure path) | Medium |
| **GAP-T2** | No test for `MissingDomainEventException` (missing local domain event class) | Medium |
| **GAP-T3** | No test for `UnknownSchemaException` path | Medium |
| **GAP-T4** | No test for unrecoverable `KafkaException` causing SHUTTING_DOWN transition | Medium |
| **GAP-T5** | No test for deferred command processing when schema is not yet available | Low |
| **GAP-T6** | No test for `CommandServiceApplication` with command-line package arguments | Low |
| **GAP-T7** | No test for multi-service (agentic) command routing via `resolveAgenticTarget()` | Medium |
| **GAP-T8** | No test for domain event version downgrade resolution via `TreeMap.floorEntry()` | Low |
