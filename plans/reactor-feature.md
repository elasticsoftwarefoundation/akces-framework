# Reactor Feature Plan

## Overview

The **Reactor** is a new component in the `query-support` module that is similar to the `DatabaseModel` but instead of updating a database, it calls an external API in response to domain events. It provides:

1. **External API invocation** triggered by domain events from Kafka
2. **Kafka offset management** — offsets are committed only after a successful API call
3. **Retry with incremental backoff** — maximum 5 retries with incremental (linear) backoff
4. **Failure hook** — when all retries are exhausted, the implementor can send a command back to an aggregate to signal the failure

## Architectural Decisions

### 1. Event Processing Model: Per-Event (Not Per-Batch)

Unlike the `DatabaseModel` which processes events in a batch within a single database transaction and commits offsets for the entire batch, the Reactor processes events **one at a time**. This is because:

- External API calls are inherently non-transactional
- Each API call may fail independently
- Offsets must only be committed after a successful API call (or after failure handling)
- Retry logic applies to individual events, not batches

The `ReactorPartition` will poll events from Kafka and process each `DomainEventRecord` sequentially within a partition. The Kafka consumer offset is committed per-event after successful processing or after failure handling.

### 2. Offset Management: Kafka Consumer Offset Commit

Since there is no database to store offsets in (unlike `DatabaseModel` which stores offsets in a `partition_offsets` table), the Reactor will use **Kafka consumer manual offset commit** (`consumer.commitSync()`). This means:

- Auto-commit is disabled (already the default in the framework)
- After a successful API call, the offset is committed to Kafka
- After all retries fail AND the failure handler completes, the offset is also committed (to avoid reprocessing the same failing event forever)
- On restart, consumption resumes from the last committed offset

### 3. Retry Strategy: Linear Incremental Backoff

- Maximum **5 retries** (configurable via annotation, default = 5)
- **Linear incremental backoff**: retry delays of 1s, 2s, 3s, 4s, 5s (base interval configurable, default = 1 second)
- Formula: `delay = attempt * baseInterval` where attempt is 1-based
- Retries happen in-process (blocking the partition thread for that event) using `Thread.sleep()` to keep it simple and predictable
- During retry backoff, the partition thread is effectively paused, which is acceptable since each partition has its own thread

### 4. Failure Hook: Command Sending on Exhausted Retries

When all retries are exhausted, the framework calls a failure handler method on the Reactor implementation. This method:

- Receives the original event and the last exception
- Returns an `Optional<Command>` — if present, the framework sends this command to the appropriate aggregate via the Kafka producer
- If `Optional.empty()` is returned, no command is sent (the event is simply skipped)
- The offset is committed regardless (to avoid infinite retry loops)

To support sending commands, the Reactor needs access to a Kafka producer and the `AkcesRegistry` for command routing (similar to how `ProcessManager` sends commands).

### 5. GDPR / PII Data Support

Like the `DatabaseModel`, the Reactor supports PII data handling via the GDPR context. If any of the handled events contain `@PIIData` fields, the GDPR keys are loaded and the `GDPRContext` is set before invoking the event handler.

## Component Design

### API Module (`main/api`)

#### 1. `@ReactorInfo` Annotation
*File: `main/api/src/main/java/org/elasticsoftware/akces/annotations/ReactorInfo.java`*

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Component
public @interface ReactorInfo {
    @AliasFor(annotation = Component.class)
    String value();
    
    int version() default 1;
    
    String schemaName();
    
    int maxRetries() default 5;
    
    long retryBackoffBaseMs() default 1000;
}
```

#### 2. `@ReactorEventHandler` Annotation
*File: `main/api/src/main/java/org/elasticsoftware/akces/annotations/ReactorEventHandler.java`*

```java
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReactorEventHandler {
}
```

#### 3. `Reactor` Interface
*File: `main/api/src/main/java/org/elasticsoftware/akces/query/Reactor.java`*

```java
public interface Reactor {
    /**
     * Called when all retries are exhausted for an event handler invocation.
     * The implementor can return a Command to send back to an aggregate to signal failure.
     *
     * @param event the event that failed to be processed
     * @param exception the last exception that was thrown
     * @return an Optional containing a Command to send, or empty if no command should be sent
     */
    default Optional<Command> onFailure(DomainEvent event, Exception exception) {
        return Optional.empty();
    }
}
```

#### 4. `ReactorEventHandlerFunction` Functional Interface
*File: `main/api/src/main/java/org/elasticsoftware/akces/query/ReactorEventHandlerFunction.java`*

```java
@FunctionalInterface
public interface ReactorEventHandlerFunction<E extends DomainEvent> {
    void accept(@Nonnull E event) throws Exception;
    
    default DomainEventType<E> getEventType() {
        throw new UnsupportedOperationException(
            "When implementing ReactorEventHandlerFunction directly, you must override getEventType()");
    }
    
    default Reactor getReactor() {
        throw new UnsupportedOperationException(
            "When implementing ReactorEventHandlerFunction directly, you must override getReactor()");
    }
}
```

Note: unlike `DatabaseModelEventHandlerFunction.accept()` which does not throw, `ReactorEventHandlerFunction.accept()` throws `Exception` because the external API call may throw checked exceptions (e.g. `IOException`, `HttpTimeoutException`). This is a key design difference.

### Query-Support Module (`main/query-support`)

All new files go in package `org.elasticsoftware.akces.query.reactor`.

#### 5. `ReactorRuntime` Interface
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/ReactorRuntime.java`*

Mirrors `DatabaseModelRuntime` but with reactor-specific semantics:

```java
public interface ReactorRuntime {
    String getName();
    
    /**
     * Process a single domain event record. Handles retry logic internally.
     * Returns true if the event was processed successfully (either on first try or after retries),
     * false if all retries were exhausted.
     */
    ReactorResult apply(DomainEventRecord eventRecord, 
                        Function<String, GDPRContext> gdprContextSupplier) throws IOException;
    
    /**
     * Called when all retries are exhausted. Delegates to the Reactor.onFailure() method.
     * Returns an Optional<Command> that should be sent to an aggregate.
     */
    Optional<Command> handleFailure(DomainEvent event, Exception exception);
    
    void validateDomainEventSchemas(SchemaRegistry schemaRegistry);
    
    boolean shouldHandlePIIData();
    
    Collection<DomainEventType<?>> getDomainEventTypes();
    
    int getMaxRetries();
    
    long getRetryBackoffBaseMs();
}
```

#### 6. `ReactorResult` Enum
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/ReactorResult.java`*

```java
public enum ReactorResult {
    SUCCESS,
    FAILURE
}
```

#### 7. `KafkaReactorRuntime` Implementation
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/KafkaReactorRuntime.java`*

Similar to `KafkaDatabaseModelRuntime` but:
- Processes events individually (not in batches)
- `accept()` calls throw exceptions on failure (triggering retry)
- `handleFailure()` delegates to `Reactor.onFailure()`
- Implements the retry with backoff logic
- Uses a Builder pattern like `KafkaDatabaseModelRuntime`

The `apply` method:
1. Resolves the event type and handler
2. Materializes the event from the `DomainEventRecord`
3. Attempts to call the handler
4. On exception: retries up to `maxRetries` times with incremental backoff (`attempt * retryBackoffBaseMs`)
5. If successful: returns `ReactorResult.SUCCESS`
6. If all retries exhausted: calls `handleFailure()` and returns `ReactorResult.FAILURE`

#### 8. `ReactorPartitionState` Enum
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/ReactorPartitionState.java`*

```java
public enum ReactorPartitionState {
    INITIALIZING,
    LOADING_GDPR_KEYS,
    PROCESSING,
    SHUTTING_DOWN
}
```

#### 9. `ReactorPartition` Class
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/ReactorPartition.java`*

Similar to `DatabaseModelPartition` but:
- Processes events **one at a time** (not in batches)
- After each successful event processing, commits the Kafka offset synchronously
- After failure handling (all retries exhausted + failure hook called), also commits the offset
- Needs a Kafka producer for sending failure commands
- Uses `consumer.commitSync(Map<TopicPartition, OffsetAndMetadata>)` for per-event offset commit

Key differences from `DatabaseModelPartition`:
- The `processRecords` method iterates over records one by one
- For each record, it calls `runtime.apply()` which handles retries internally
- If result is `FAILURE`, it calls `runtime.handleFailure()` and if a Command is returned, sends it via the Kafka producer
- After processing (or failure handling) of each record, commits the offset

#### 10. `AkcesReactorControllerState` Enum
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/AkcesReactorControllerState.java`*

```java
public enum AkcesReactorControllerState {
    INITIALIZING,
    INITIAL_REBALANCING,
    REBALANCING,
    RUNNING,
    SHUTTING_DOWN,
    ERROR
}
```

#### 11. `AkcesReactorController` Class
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/AkcesReactorController.java`*

Similar to `AkcesDatabaseModelController` but:
- Manages `ReactorPartition` instances instead of `DatabaseModelPartition`
- Needs a Kafka producer factory (for sending failure commands) in addition to consumer factories
- Constructor takes: consumer factories, producer factory, objectMapper, gdprContextRepositoryFactory, reactorRuntime
- Producer is needed to send commands back to aggregates on failure

#### 12. `ReactorRuntimeFactory`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/ReactorRuntimeFactory.java`*

Factory bean similar to `DatabaseModelRuntimeFactory`:
- Implements `FactoryBean<ReactorRuntime>`
- Collects all `ReactorEventHandlerFunction` beans that belong to this reactor
- Builds a `KafkaReactorRuntime` via its Builder

#### 13. `ReactorEventHandlerFunctionAdapter`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/beans/ReactorEventHandlerFunctionAdapter.java`*

Similar to `DatabaseModelEventHandlerFunctionAdapter` but:
- Implements `ReactorEventHandlerFunction<E>` instead of `DatabaseModelEventHandlerFunction<E>`
- The `accept()` method propagates exceptions (does not catch them)
- References `Reactor` instead of `DatabaseModel`

#### 14. `ReactorBeanFactoryPostProcessor`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/beans/ReactorBeanFactoryPostProcessor.java`*

Similar to `DatabaseModelBeanFactoryPostProcessor` but:
- Scans for `@ReactorInfo` annotated beans
- Scans for `@ReactorEventHandler` annotated methods
- Creates `ReactorEventHandlerFunctionAdapter` bean definitions
- Creates `ReactorRuntimeFactory` bean definition
- Creates `AkcesReactorController` bean definition with additional producer factory reference
- The method signature validation allows `void` return type with a single `DomainEvent` parameter (same as DatabaseModel)

#### 15. `ReactorImplementationPresentCondition`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/ReactorImplementationPresentCondition.java`*

Same pattern as `DatabaseModelImplementationPresentCondition` but checks for `@ReactorInfo` annotation.

#### 16. `AkcesReactorAutoConfiguration`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reactor/AkcesReactorAutoConfiguration.java`*

Similar to `AkcesDatabaseModelAutoConfiguration` but:
- Registers reactor-specific beans
- Includes a producer factory bean (for sending failure commands)
- Uses `@Conditional(ReactorImplementationPresentCondition.class)` for conditional activation
- References a new properties file `akces-reactor.properties`

#### 17. `akces-reactor.properties`
*File: `main/query-support/src/main/resources/akces-reactor.properties`*

Same Kafka consumer properties as `akces-databasemodel.properties` (auto-commit disabled, read_committed isolation, etc.).

#### 18. Auto-configuration Registration

Update `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` to include:
```
org.elasticsoftware.akces.query.reactor.AkcesReactorAutoConfiguration
```

## Detailed Flow

### Happy Path (Successful API Call)

```
1. Kafka Consumer polls event records
2. For each DomainEventRecord:
   a. Resolve event type and handler
   b. Set GDPR context if needed
   c. Call handler.accept(event)  → External API call succeeds
   d. Commit Kafka offset for this record
   e. Clear GDPR context
```

### Retry Path (Transient Failure)

```
1. Kafka Consumer polls event records
2. For each DomainEventRecord:
   a. Resolve event type and handler
   b. Set GDPR context if needed
   c. Call handler.accept(event) → throws Exception
   d. Retry 1: wait 1s, call handler.accept(event) → throws Exception
   e. Retry 2: wait 2s, call handler.accept(event) → succeeds
   f. Commit Kafka offset for this record
   g. Clear GDPR context
```

### Failure Path (All Retries Exhausted)

```
1. Kafka Consumer polls event records
2. For each DomainEventRecord:
   a. Resolve event type and handler
   b. Set GDPR context if needed
   c. Call handler.accept(event) → throws Exception
   d. Retry 1-5: all fail with incremental backoff
   e. Call reactor.onFailure(event, lastException)
   f. If Command returned: send command to Kafka via producer
   g. Commit Kafka offset for this record (to avoid infinite retry)
   h. Clear GDPR context
```

## Command Sending on Failure

When the `Reactor.onFailure()` method returns a `Command`, the framework needs to:

1. Serialize the command using the `ObjectMapper`
2. Resolve the target topic using `AkcesRegistry.resolveTopic(commandClass)`
3. Resolve the partition using `AkcesRegistry.resolvePartition(aggregateId)`
4. Send the command record to Kafka using the producer

This requires the `AkcesReactorController` to:
- Implement `AkcesRegistry` (it already needs to for event topic resolution, same as `AkcesDatabaseModelController`)
- Additionally implement `resolveTopic(Class<? extends Command>)` and `resolvePartition(String)` methods (unlike `AkcesDatabaseModelController` which throws `UnsupportedOperationException` for these)
- Have access to a Kafka producer

The command sending logic can be implemented in the `ReactorPartition` which receives the `AkcesRegistry` and uses a producer to send the command.

For the `AkcesReactorController` to resolve command topics, it needs to know the `AggregateServiceRecord` data (which it already reads from the `Akces-Control` topic). The `AggregateServiceRecord` contains information about which commands each aggregate service accepts and their corresponding topics.

## Implementation Order

### Phase 1: API Interfaces and Annotations
1. Create `@ReactorInfo` annotation
2. Create `@ReactorEventHandler` annotation  
3. Create `Reactor` interface
4. Create `ReactorEventHandlerFunction` functional interface

### Phase 2: Runtime Implementation
5. Create `ReactorResult` enum
6. Create `ReactorRuntime` interface
7. Create `KafkaReactorRuntime` with Builder (including retry logic)

### Phase 3: Partition and Controller
8. Create `ReactorPartitionState` enum
9. Create `ReactorPartition` (with per-event offset commit and command sending)
10. Create `AkcesReactorControllerState` enum
11. Create `AkcesReactorController` (with producer support)

### Phase 4: Spring Integration
12. Create `ReactorEventHandlerFunctionAdapter`
13. Create `ReactorBeanFactoryPostProcessor`
14. Create `ReactorImplementationPresentCondition`
15. Create `ReactorRuntimeFactory`
16. Create `AkcesReactorAutoConfiguration`
17. Create `akces-reactor.properties`
18. Update auto-configuration imports

### Phase 5: Testing
19. Create unit tests for `KafkaReactorRuntime` (retry logic, failure handling)
20. Create integration tests with Testcontainers (Kafka + mock external API)

## File Summary

### New files in `main/api`:
| File | Description |
|------|-------------|
| `annotations/ReactorInfo.java` | Class-level annotation for Reactor implementations |
| `annotations/ReactorEventHandler.java` | Method-level annotation for event handler methods |
| `query/Reactor.java` | Interface that reactor implementations must implement |
| `query/ReactorEventHandlerFunction.java` | Functional interface for event handler functions |

### New files in `main/query-support`:
| File | Description |
|------|-------------|
| `reactor/ReactorRuntime.java` | Runtime interface |
| `reactor/ReactorResult.java` | Success/Failure result enum |
| `reactor/KafkaReactorRuntime.java` | Runtime implementation with retry logic |
| `reactor/ReactorPartitionState.java` | Partition state enum |
| `reactor/ReactorPartition.java` | Per-partition event processor |
| `reactor/AkcesReactorControllerState.java` | Controller state enum |
| `reactor/AkcesReactorController.java` | Main controller thread |
| `reactor/ReactorRuntimeFactory.java` | Spring FactoryBean for runtime |
| `reactor/ReactorImplementationPresentCondition.java` | Spring conditional |
| `reactor/AkcesReactorAutoConfiguration.java` | Spring auto-configuration |
| `reactor/beans/ReactorBeanFactoryPostProcessor.java` | Bean registration |
| `reactor/beans/ReactorEventHandlerFunctionAdapter.java` | Method handle adapter |

### Modified files:
| File | Change |
|------|--------|
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Add reactor auto-config |

### New resource files:
| File | Description |
|------|-------------|
| `akces-reactor.properties` | Default Kafka consumer properties for reactor |

## Usage Example

```java
@ReactorInfo(value = "NotificationReactor", version = 1, schemaName = "Accounts")
public class NotificationReactor implements Reactor {
    
    private final NotificationApiClient apiClient;
    
    public NotificationReactor(NotificationApiClient apiClient) {
        this.apiClient = apiClient;
    }
    
    @ReactorEventHandler
    public void handle(AccountCreatedEvent event) throws Exception {
        // This calls an external API - may throw on failure
        apiClient.sendWelcomeNotification(event.userId(), event.email());
    }
    
    @ReactorEventHandler
    public void handle(OrderConfirmedEvent event) throws Exception {
        apiClient.sendOrderConfirmation(event.orderId());
    }
    
    @Override
    public Optional<Command> onFailure(DomainEvent event, Exception exception) {
        // Send a command back to signal the failure
        if (event instanceof AccountCreatedEvent ace) {
            return Optional.of(new MarkNotificationFailedCommand(
                ace.userId(), 
                "Welcome notification failed: " + exception.getMessage()
            ));
        }
        return Optional.empty();
    }
}
```

## Open Questions for Architect

1. **Retry configuration scope**: Should `maxRetries` and `retryBackoffBaseMs` be per-Reactor (on `@ReactorInfo`) or per-handler (on `@ReactorEventHandler`)? The current plan puts them on `@ReactorInfo` for simplicity. Per-handler would give more flexibility but adds complexity.

2. **Dead Letter Topic**: Should we also support writing failed events to a dead letter topic (DLT) in addition to/instead of the command-sending failure hook? This is a common Kafka pattern and could be added later.

3. **Concurrency**: The current design uses one thread per partition (same as `DatabaseModelPartition`). Should we consider async/non-blocking API calls with a configurable concurrency level per partition? This would be more complex but could improve throughput for high-latency API calls.

4. **Circuit Breaker**: Should we integrate a circuit breaker pattern (e.g., using Resilience4j) to avoid overwhelming a failing external API? This could be a future enhancement.

5. **Metrics/Observability**: Should we add Micrometer metrics for retry counts, success/failure rates, and backoff times? This would be valuable for monitoring in production.

6. **Command serialization**: The `AkcesReactorController` needs to serialize commands and resolve topics. Should we reuse the `AkcesClient` module for this, or implement command sending directly? The plan currently proposes direct implementation to avoid a circular dependency (query-support should not depend on client), but reusing serialization utilities from `akces-shared` is fine.
