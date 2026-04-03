# Reflector Feature Plan

## Overview

The **Reflector** is a new component in the `query-support` module that is similar to the `DatabaseModel` but instead of updating a database, it calls an external API in response to domain events. It provides:

1. **External API invocation** triggered by domain events from Kafka
2. **Kafka offset management** — offsets are committed only after a successful API call
3. **Retry with incremental backoff** — maximum 5 retries with incremental (linear) backoff
4. **Success hook** — after a successful API call, the implementor can send a command back to an aggregate to signal success
5. **Failure hook** — when all retries are exhausted, the implementor can send a command back to an aggregate to signal the failure

## Architectural Decisions

### 1. Event Processing Model: Per-Event (Not Per-Batch)

Unlike the `DatabaseModel` which processes events in a batch within a single database transaction and commits offsets for the entire batch, the Reflector processes events **one at a time**. This is because:

- External API calls are inherently non-transactional
- Each API call may fail independently
- Offsets must only be committed after a successful API call (or after failure handling)
- Retry logic applies to individual events, not batches

The `ReflectorPartition` will poll events from Kafka and process each `DomainEventRecord` sequentially within a partition. After each successful event processing (or failure handling), the Kafka offset is committed atomically via a Kafka transaction.

### 2. Offset Management: Kafka Transactional Producer

Since there is no database to store offsets in (unlike `DatabaseModel` which stores offsets in a `partition_offsets` table), the Reflector will use **Kafka transactions** to commit offsets. This follows the same pattern used by `AggregatePartition` in the runtime module, which is more reliable than `consumer.commitSync()`. The flow is:

- Auto-commit is disabled (already the default in the framework)
- Each `ReflectorPartition` creates a **transactional producer** via `CustomKafkaProducerFactory.createProducer(transactionalId)`, which calls `initTransactions()` on the producer
- After a successful API call (or after failure handling), the offset is committed **within a Kafka transaction**:
  1. `producer.beginTransaction()`
  2. If `onFailure` sends commands via the `CommandBus`, the corresponding records are produced via `producer.send()` (within the same transaction)
  3. `producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata())` — this atomically commits the consumer offset
  4. `producer.commitTransaction()`
- If the transaction fails, `producer.abortTransaction()` is called and the consumer is rolled back
- On restart, consumption resumes from the last transactionally committed offset

With this approach, Kafka guarantees atomicity between the offset commit and any failure command sent in the same transaction: they are either both committed or both rolled back. However, the external API invocation itself is not part of the Kafka transaction, so end-to-end behavior remains **at-least-once** with respect to external side effects (e.g., commit/epoch errors or restarts can cause the same event to be reprocessed and the API to be called again). Reflector implementations MUST therefore ensure idempotency of external calls or introduce a deduplication mechanism (for example, an outbox-style design backed by a durable store that is consulted before invoking the external API). The `consumer.commitSync()` approach was rejected as it is not reliable enough — it can succeed while subsequent processing fails, or fail silently in edge cases.

### 3. Retry Strategy: Non-Blocking Poll-Loop Integrated Backoff

- Maximum **5 retries** (configurable via annotation, default = 5)
- **Linear incremental backoff**: retry delays of 1s, 2s, 3s, 4s, 5s (base interval configurable, default = 1 second)
- Formula: `delay = attempt * baseInterval` where attempt is 1-based
- Retries are **non-blocking** and integrated with the Kafka consumer poll loop:
  1. On first failure: the current Kafka transaction is **rolled back** (offset not committed), the retry count (attempt 1) and next-attempt timestamp are stored **in memory**
  2. Control is returned to the poll loop immediately — the consumer keeps polling within `max.poll.interval.ms` to avoid rebalances
  3. On the next poll, the same event is redelivered (since the offset was not committed). The partition checks the in-memory retry state: if the backoff period has not elapsed, the partition is **paused** (`consumer.pause()`) and the event is skipped; once the backoff elapses, the partition is **resumed** (`consumer.resume()`) and the handler is invoked again
  4. If the handler succeeds on any retry: the event is processed as SUCCESS, offset is committed in a transaction
  5. If all retries are exhausted: the event is processed as FAILURE, `onFailure` is called, offset is committed in a transaction
- **In-memory retry state only**: retry counts and timestamps are kept in a `Map<TopicPartition, RetryState>` within the `ReflectorPartition`. On process restart, retry state is lost — the event will be redelivered from the last committed offset and retry count resets to 0. This is acceptable because the external API call should be idempotent anyway.
- This design avoids exceeding `max.poll.interval.ms` and prevents unnecessary consumer rebalances during backoff

### 4. Failure Hook: Command Sending on Exhausted Retries

When all retries are exhausted, the framework calls the `onFailure` method on the Reflector implementation. This method:

- Receives the original event, the last exception, and a `CommandBus`
- The implementor can call `commandBus.send(command)` to send one or more commands back to aggregates
- If the implementor does nothing, no commands are sent (the event is simply skipped)
- The offset is committed regardless (to avoid infinite retry loops)
- Both the offset commit and any failure commands are committed atomically within a single Kafka transaction

The `CommandBus` is the existing framework interface (`org.elasticsoftware.akces.commands.CommandBus`), backed by the `ReflectorPartition`. Commands sent via the `CommandBus` during `onFailure` are queued in the Kafka producer and committed as part of the same transaction as the offset commit.

### 5. Success Hook: Command Sending on Successful Processing

After a successful API call, the framework calls the `onSuccess` method on the Reflector implementation. This method:

- Receives the processed event, the **typed API result** (the value returned by the handler method), and a `CommandBus`
- The implementor can use the API result to build precise Command payloads and call `commandBus.send(command)` to send one or more success commands back to aggregates (e.g., "notification was sent — here is the notification ID")
- If the implementor does nothing, no commands are sent (just the offset is committed)
- Both the offset commit and any success commands are committed atomically within a single Kafka transaction

This is symmetric with `onFailure`: both hooks run inside the Kafka transaction, and both provide access to the `CommandBus`. This allows the Reflector to provide full lifecycle feedback to aggregates — signaling both successful completions and failures of external API calls.

### 6. GDPR / PII Data Support

Like the `DatabaseModel`, the Reflector supports PII data handling via the GDPR context. If any of the handled events contain `@PIIData` fields, the GDPR keys are loaded and the `GDPRContext` is set before invoking the event handler.

## Component Design

### API Module (`main/api`)

#### 1. `@ReflectorInfo` Annotation
*File: `main/api/src/main/java/org/elasticsoftware/akces/annotations/ReflectorInfo.java`*

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Component
public @interface ReflectorInfo {
    @AliasFor(annotation = Component.class)
    String value();
    
    int version() default 1;
    
    String schemaName();
    
    int maxRetries() default 5;
    
    long retryBackoffBaseMs() default 1000;
}
```

#### 2. `@ReflectorEventHandler` Annotation
*File: `main/api/src/main/java/org/elasticsoftware/akces/annotations/ReflectorEventHandler.java`*

```java
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReflectorEventHandler {
}
```

#### 3. `Reflector` Interface
*File: `main/api/src/main/java/org/elasticsoftware/akces/query/Reflector.java`*

`Reflector` is parameterized on `R` — the type returned by the external API call. This lets `onSuccess` receive the actual API response alongside the triggering event, enabling the implementor to build precise Command payloads from the response data.

```java
public interface Reflector<R> {
    /**
     * Called after a successful event handler invocation.
     * The implementor can use the provided CommandBus to send a command back
     * to an aggregate to signal success (e.g., "notification sent").
     *
     * @param event the event that was successfully processed
     * @param result the result returned by the external API call (typed)
     * @param commandBus the CommandBus to use for sending success commands
     */
    default void onSuccess(DomainEvent event, R result, CommandBus commandBus) {
        // default: do nothing
    }

    /**
     * Called when all retries are exhausted for an event handler invocation.
     * The implementor can use the provided CommandBus to send a command back
     * to an aggregate to signal failure.
     *
     * @param event the event that failed to be processed
     * @param exception the last exception that was thrown
     * @param commandBus the CommandBus to use for sending failure commands
     */
    default void onFailure(DomainEvent event, Exception exception, CommandBus commandBus) {
        // default: do nothing
    }
}
```

Note: both `onSuccess` and `onFailure` receive a `CommandBus` parameter which is the existing framework interface (`org.elasticsoftware.akces.commands.CommandBus`). Internally, this is backed by the `ReflectorPartition` which implements `CommandBus` and sends commands via the transactional Kafka producer. This is similar to how `AggregatePartition` implements `CommandBus` for the `EventHandlerFunction.getCommandBus()` pattern used by Process Managers.

When `R` is `Void` (or if the implementor does not need the result in `onSuccess`), the handler method returns `null` and `onSuccess` can ignore the `result` parameter.

#### 4. `ReflectorEventHandlerFunction` Functional Interface
*File: `main/api/src/main/java/org/elasticsoftware/akces/query/ReflectorEventHandlerFunction.java`*

`ReflectorEventHandlerFunction` is parameterized on both `E` (the event type) and `R` (the API result type), matching `Reflector<R>`.

```java
@FunctionalInterface
public interface ReflectorEventHandlerFunction<E extends DomainEvent, R> {
    /**
     * Invokes the external API for the given event and returns the API result.
     * The returned value is passed to {@link Reflector#onSuccess} on success.
     * May return {@code null} if the result is not needed by {@code onSuccess}.
     */
    R accept(@Nonnull E event) throws Exception;
    
    default DomainEventType<E> getEventType() {
        throw new UnsupportedOperationException(
            "When implementing ReflectorEventHandlerFunction directly, you must override getEventType()");
    }
    
    default Reflector<R> getReflector() {
        throw new UnsupportedOperationException(
            "When implementing ReflectorEventHandlerFunction directly, you must override getReflector()");
    }
}
```

Note: unlike `DatabaseModelEventHandlerFunction.accept()` which returns `void` and does not throw, `ReflectorEventHandlerFunction.accept()` returns `R` and throws `Exception` because the external API call may throw checked exceptions (e.g. `IOException`, `HttpTimeoutException`). This is a key design difference.

### Query-Support Module (`main/query-support`)

All new files go in package `org.elasticsoftware.akces.query.reflector`.

#### 5. `ReflectorRuntime` Interface
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/ReflectorRuntime.java`*

Mirrors `DatabaseModelRuntime` but with reflector-specific semantics:

```java
public interface ReflectorRuntime {
    String getName();
    
    /**
     * Process a single domain event record. Handles retry logic internally.
     * Returns {@link ReflectorResult#SUCCESS} if the event was processed successfully
     * (either on first try or after retries), or {@link ReflectorResult#FAILURE} if
     * all retries were exhausted and the event is considered failed.
     */
    ReflectorResult apply(DomainEventRecord eventRecord, 
                        Function<String, GDPRContext> gdprContextSupplier) throws IOException;
    
    /**
     * Called after successful event processing. Delegates to the Reflector.onSuccess() method,
     * passing the typed API result and the CommandBus so the implementor can send success commands.
     *
     * @param event  the event that was successfully processed
     * @param result the value returned by the handler (the external API response); may be null
     * @param commandBus the CommandBus to use for sending success commands
     */
    void handleSuccess(DomainEvent event, Object result, CommandBus commandBus);
    
    /**
     * Called when all retries are exhausted. Delegates to the Reflector.onFailure() method,
     * passing the CommandBus so the implementor can send failure commands.
     */
    void handleFailure(DomainEvent event, Exception exception, CommandBus commandBus);
    
    void validateDomainEventSchemas(SchemaRegistry schemaRegistry);
    
    boolean shouldHandlePIIData();
    
    Collection<DomainEventType<?>> getDomainEventTypes();
    
    int getMaxRetries();
    
    long getRetryBackoffBaseMs();
}
```

#### 6. `ReflectorResult` Enum
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/ReflectorResult.java`*

```java
public enum ReflectorResult {
    /** Event handler completed successfully */
    SUCCESS,
    /** All retries exhausted, event is considered failed */
    FAILURE,
    /** Handler failed but retries remain; backoff is pending, event will be redelivered on next poll */
    RETRY_PENDING
}
```

#### 7. `KafkaReflectorRuntime` Implementation
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/KafkaReflectorRuntime.java`*

Similar to `KafkaDatabaseModelRuntime` but:
- Processes events individually (not in batches)
- `accept()` calls throw exceptions on failure (triggering retry)
- `handleSuccess()` delegates to `Reflector.onSuccess()`, passing the typed API result and a `CommandBus`
- `handleFailure()` delegates to `Reflector.onFailure()`, passing a `CommandBus`
- Implements the retry with backoff logic
- Uses a Builder pattern like `KafkaDatabaseModelRuntime`

The `apply` method:
1. Resolves the event type and handler
2. Checks if this event has in-memory retry state (from a previous failed attempt)
   - If backoff period has not elapsed: returns `ReflectorResult.RETRY_PENDING` (caller should pause partition)
   - If backoff period has elapsed: proceeds to step 3
3. Materializes the event from the `DomainEventRecord`
4. Attempts to call the handler
5. On exception: increments retry count in memory, records next-attempt timestamp; returns `ReflectorResult.RETRY_PENDING`
6. If successful: clears retry state, stores the returned API result (accessible via `getLastResult()`), returns `ReflectorResult.SUCCESS`
7. If all retries exhausted: clears retry state, stores the last exception (accessible via `getLastException()`), returns `ReflectorResult.FAILURE`

The API result returned by `accept()` is held in a per-call field (`lastResult`) so that `ReflectorPartition` can pass it to `runtime.handleSuccess(event, result, commandBus)` immediately after `apply()` returns `SUCCESS`.

#### 8. `ReflectorPartitionState` Enum
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/ReflectorPartitionState.java`*

```java
public enum ReflectorPartitionState {
    INITIALIZING,
    LOADING_GDPR_KEYS,
    PROCESSING,
    SHUTTING_DOWN
}
```

#### 9. `ReflectorPartition` Class
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/ReflectorPartition.java`*

Similar to `DatabaseModelPartition` but implements `CommandBus`:
- Implements `Runnable`, `AutoCloseable`, and `CommandBus` (same pattern as `AggregatePartition`)
- **Each `ReflectorPartition` owns both a Kafka `Consumer` and a Transactional `Producer`**:
  - The **Consumer** polls events from the assigned partition of the domain event topic
  - The **Transactional Producer** is created on startup via `producerFactory.createProducer(transactionalId)` where the transactionalId is `{reflectorName}Reflector-partition-{id}-{hostname}`. This single producer instance is used for **both** offset commits (`sendOffsetsToTransaction`) and command sending (`CommandBus.send`)
- Processes events **one at a time** (not in batches)
- After each event processing (success or failure handling), commits via a **Kafka transaction**:
  1. `producer.beginTransaction()`
  2. If `onSuccess` or `onFailure` was called and the implementor used the `CommandBus`, the command `ProducerRecord`s are already queued in the producer within this transaction
  3. `producer.sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata>, consumer.groupMetadata())` 
  4. `producer.commitTransaction()`
- On transaction failure: `producer.abortTransaction()` and rollback the consumer position
- **Custom `CommandBus` implementation** (not delegating to `AkcesClient`) — the `CommandBus.send(Command)` method uses the **same** transactional producer that manages offset commits, ensuring commands and offsets are always in the same transaction:
  1. Resolves command type via `AkcesRegistry.resolveType(commandClass)`
  2. Resolves target topic via `AkcesRegistry.resolveTopic(commandType)`
  3. Resolves partition via `AkcesRegistry.resolvePartition(aggregateId)`
  4. Serializes the command and sends via `KafkaSender.send(producer, producerRecord)` — within the active transaction
- Uses `ReflectorPartitionCommandBus` (extending `CommandBusHolder`) to register itself on the partition thread, similar to `AggregatePartitionCommandBus`

Key differences from `DatabaseModelPartition`:
- The `processRecords` method iterates over records one by one
- For each record, it calls `runtime.apply()` which handles retries internally
- If result is `SUCCESS`, it calls `runtime.handleSuccess(event, runtime.getLastResult(), this)` where `this` is the `CommandBus` — the implementor can use the typed API result from `getLastResult()` together with the event to build precise Command payloads and send them via `commandBus.send(command)` to signal success back to an aggregate
- If result is `FAILURE`, it calls `runtime.handleFailure(event, exception, this)` where `this` is the `CommandBus` — the implementor can call `commandBus.send(command)` which queues the command in the current transaction
- After processing (or failure handling) of each record, commits the offset in a Kafka transaction

#### 10. `AkcesReflectorControllerState` Enum
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/AkcesReflectorControllerState.java`*

```java
public enum AkcesReflectorControllerState {
    INITIALIZING,
    INITIAL_REBALANCING,
    REBALANCING,
    RUNNING,
    SHUTTING_DOWN,
    ERROR
}
```

#### 11. `AkcesReflectorController` Class
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/AkcesReflectorController.java`*

Similar to `AkcesDatabaseModelController` but:
- Manages `ReflectorPartition` instances instead of `DatabaseModelPartition`
- Needs a Kafka **transactional** producer factory (`CustomKafkaProducerFactory`) for both offset commits and failure command sending
- Constructor takes: consumer factories, producer factory, objectMapper, gdprContextRepositoryFactory, reflectorRuntime
- The producer factory is passed to each `ReflectorPartition` which creates its own transactional producer
- Additionally implements `resolveTopic(Class<? extends Command>)`, `resolveTopic(CommandType<?>)`, `resolveType(Class<? extends Command>)`, and `resolvePartition(String)` on the `AkcesRegistry` (unlike `AkcesDatabaseModelController` which throws `UnsupportedOperationException` for these) — needed for command routing in the failure hook

#### 12. `ReflectorRuntimeFactory`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/ReflectorRuntimeFactory.java`*

Factory bean similar to `DatabaseModelRuntimeFactory`:
- Implements `FactoryBean<ReflectorRuntime>`
- Collects all `ReflectorEventHandlerFunction` beans that belong to this reflector
- Builds a `KafkaReflectorRuntime` via its Builder

#### 13. `ReflectorEventHandlerFunctionAdapter`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/beans/ReflectorEventHandlerFunctionAdapter.java`*

Similar to `DatabaseModelEventHandlerFunctionAdapter` but:
- Implements `ReflectorEventHandlerFunction<E, R>` instead of `DatabaseModelEventHandlerFunction<E>`
- The `accept()` method returns the value from the handler method (the API result) and propagates exceptions (does not catch them)
- References `Reflector<R>` instead of `DatabaseModel`

#### 14. `ReflectorBeanFactoryPostProcessor`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/beans/ReflectorBeanFactoryPostProcessor.java`*

Similar to `DatabaseModelBeanFactoryPostProcessor` but:
- Scans for `@ReflectorInfo` annotated beans
- Scans for `@ReflectorEventHandler` annotated methods
- Creates `ReflectorEventHandlerFunctionAdapter` bean definitions
- Creates `ReflectorRuntimeFactory` bean definition
- Creates `AkcesReflectorController` bean definition with additional **transactional** producer factory reference
- The method signature validation requires a single `DomainEvent` parameter, a non-`void` return type (the API result type `R`), and does not enforce any particular `throws` clause (handlers may declare specific checked exceptions, multiple exceptions, or none). The return type is used to infer `R` for the `ReflectorEventHandlerFunctionAdapter`.

#### 15. `ReflectorImplementationPresentCondition`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/ReflectorImplementationPresentCondition.java`*

Same pattern as `DatabaseModelImplementationPresentCondition` but checks for `@ReflectorInfo` annotation.

#### 16. `AkcesReflectorAutoConfiguration`
*File: `main/query-support/src/main/java/org/elasticsoftware/akces/query/reflector/AkcesReflectorAutoConfiguration.java`*

Similar to `AkcesDatabaseModelAutoConfiguration` but:
- Registers reflector-specific beans
- Includes a **transactional** producer factory bean (`CustomKafkaProducerFactory<String, ProtocolRecord>`) — used for both offset commits via Kafka transactions and failure command sending
- Uses `@Conditional(ReflectorImplementationPresentCondition.class)` for conditional activation
- References a new properties file `akces-reflector.properties`

#### 17. `akces-reflector.properties`
*File: `main/query-support/src/main/resources/akces-reflector.properties`*

Same Kafka consumer properties as `akces-databasemodel.properties` (auto-commit disabled, read_committed isolation, etc.). Additionally, the producer properties must include:
- `enable.idempotence=true` (required for transactional producers)
- `acks=all` (required for transactional producers)
- `isolation.level=read_committed` (on the consumer side, already set)

#### 18. Auto-configuration Registration

Update `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` to include:
```
org.elasticsoftware.akces.query.reflector.AkcesReflectorAutoConfiguration
```

## Detailed Flow

### Happy Path (Successful API Call)

```
1. Kafka Consumer polls event records
2. For each DomainEventRecord:
   a. Resolve event type and handler
   b. Set GDPR context if needed
   c. Call `result = handler.accept(event)`  → External API call succeeds, `result` holds the API response
   d. producer.beginTransaction()
   e. Call reflector.onSuccess(event, result, commandBus)
      → implementor may use `result` (the typed API response) to build Command payloads and call commandBus.send(command)
   f. producer.sendOffsetsToTransaction(offset+1, consumer.groupMetadata())
   g. producer.commitTransaction()
      → atomically commits: consumer offset + any success commands
   h. Clear GDPR context
```

### Retry Path (Transient Failure → Eventually Succeeds)

```
Poll N:
1. Kafka Consumer polls event records
2. For DomainEventRecord X:
   a. Resolve event type and handler
   b. Set GDPR context if needed
   c. Call handler.accept(event) → throws Exception
   d. Store retry state in memory: attempt=1, nextAttemptAt=now+1s
   e. Do NOT commit offset (transaction is rolled back / not started)
   f. Pause the partition
   g. Clear GDPR context

Poll N+1 (within 1s):
1. Kafka Consumer polls (partition is paused, no records for this partition)
2. Check if backoff has elapsed → not yet → continue polling

Poll N+2 (after 1s):
1. Resume the partition
2. Kafka Consumer polls → same event X is redelivered
3. For DomainEventRecord X:
   a. Check retry state: attempt=1, backoff elapsed → proceed
   b. Set GDPR context if needed
   c. Call `result = handler.accept(event)` → succeeds, result holds the API response
   d. Clear retry state
   e. producer.beginTransaction()
   f. Call reflector.onSuccess(event, result, commandBus)
   g. producer.sendOffsetsToTransaction(offset+1, consumer.groupMetadata())
   h. producer.commitTransaction()
   i. Clear GDPR context
```

### Failure Path (All Retries Exhausted)

```
Polls 1 through 6 (initial attempt + 5 retries):
- Each poll: handler fails, retry state incremented, transaction rolled back
- Backoff between attempts: 1s, 2s, 3s, 4s, 5s
- Partition paused/resumed between attempts

Poll 7 (after 5th retry fails):
1. Retry state: attempt=5 (max reached), event marked as exhausted
2. producer.beginTransaction()
3. Call reflector.onFailure(event, lastException, commandBus)
   → implementor may call commandBus.send(command) to signal failure
4. producer.sendOffsetsToTransaction(offset+1, consumer.groupMetadata())
5. producer.commitTransaction()
   → atomically commits: consumer offset + any failure commands
6. Clear retry state for this event
7. Clear GDPR context
```

## Command Sending on Success and Failure

Both `Reflector.onSuccess()` and `Reflector.onFailure()` receive a `CommandBus` parameter and can call `commandBus.send(command)` to send commands to aggregates. `onSuccess` additionally receives `result` — the typed value returned by the handler (the external API response) — enabling the implementor to build precise Command payloads from the API response data. The `CommandBus` is implemented by the `ReflectorPartition` (same pattern as `AggregatePartition`):

1. `CommandBus.send(command)` resolves the command type via `AkcesRegistry.resolveType(commandClass)`
2. Resolves the target topic via `AkcesRegistry.resolveTopic(commandType)`
3. Resolves the partition via `AkcesRegistry.resolvePartition(aggregateId)`
4. Serializes the command to a `CommandRecord`
5. Sends via `KafkaSender.send(producer, producerRecord)` — within the active Kafka transaction

Since both hooks are called between `producer.beginTransaction()` and `producer.commitTransaction()`, any commands sent via the `CommandBus` are part of the same transaction as the offset commit. This guarantees atomicity: either both the offset and the command(s) are committed, or neither is.

This requires the `AkcesReflectorController` to:
- Implement `AkcesRegistry` (it already needs to for event topic resolution, same as `AkcesDatabaseModelController`)
- Additionally implement `resolveTopic(Class<? extends Command>)`, `resolveTopic(CommandType<?>)`, `resolveType(Class<? extends Command>)`, and `resolvePartition(String)` methods (unlike `AkcesDatabaseModelController` which throws `UnsupportedOperationException` for these)
- Read `AggregateServiceRecord` data from `Akces-Control` topic (which contains information about which commands each aggregate service accepts, their topics, and partition counts)

Additionally, a `ReflectorPartitionCommandBus` class (extending `CommandBusHolder`) is needed to register the `ReflectorPartition` as the `CommandBus` on the partition thread, following the same pattern as `AggregatePartitionCommandBus`.

## Implementation Order

### Phase 1: API Interfaces and Annotations
1. Create `@ReflectorInfo` annotation
2. Create `@ReflectorEventHandler` annotation  
3. Create `Reflector` interface
4. Create `ReflectorEventHandlerFunction` functional interface

### Phase 2: Runtime Implementation
5. Create `ReflectorResult` enum
6. Create `ReflectorRuntime` interface
7. Create `KafkaReflectorRuntime` with Builder (including retry logic)

### Phase 3: Partition and Controller
8. Create `ReflectorPartitionState` enum
9. Create `ReflectorPartitionCommandBus` (extending `CommandBusHolder`)
10. Create `ReflectorPartition` (with Kafka transactions, CommandBus impl, and command sending)
11. Create `AkcesReflectorControllerState` enum
12. Create `AkcesReflectorController` (with transactional producer factory support)

### Phase 4: Spring Integration
12. Create `ReflectorEventHandlerFunctionAdapter`
13. Create `ReflectorBeanFactoryPostProcessor`
14. Create `ReflectorImplementationPresentCondition`
15. Create `ReflectorRuntimeFactory`
16. Create `AkcesReflectorAutoConfiguration`
17. Create `akces-reflector.properties`
18. Update auto-configuration imports

### Phase 5: Testing
19. Create unit tests for `KafkaReflectorRuntime` (retry logic, failure handling)
20. Create integration tests with Testcontainers (Kafka + mock external API)

## File Summary

### New files in `main/api`:
| File | Description |
|------|-------------|
| `annotations/ReflectorInfo.java` | Class-level annotation for Reflector implementations |
| `annotations/ReflectorEventHandler.java` | Method-level annotation for event handler methods |
| `query/Reflector.java` | Interface that reflector implementations must implement |
| `query/ReflectorEventHandlerFunction.java` | Functional interface for event handler functions |

### New files in `main/query-support`:
| File | Description |
|------|-------------|
| `reflector/ReflectorRuntime.java` | Runtime interface |
| `reflector/ReflectorResult.java` | Success/Failure result enum |
| `reflector/KafkaReflectorRuntime.java` | Runtime implementation with retry logic |
| `reflector/ReflectorPartitionState.java` | Partition state enum |
| `reflector/ReflectorPartition.java` | Per-partition event processor |
| `reflector/AkcesReflectorControllerState.java` | Controller state enum |
| `reflector/AkcesReflectorController.java` | Main controller thread |
| `reflector/ReflectorRuntimeFactory.java` | Spring FactoryBean for runtime |
| `reflector/ReflectorImplementationPresentCondition.java` | Spring conditional |
| `reflector/AkcesReflectorAutoConfiguration.java` | Spring auto-configuration |
| `reflector/beans/ReflectorBeanFactoryPostProcessor.java` | Bean registration |
| `reflector/beans/ReflectorEventHandlerFunctionAdapter.java` | Method handle adapter |
| `reflector/ReflectorPartitionCommandBus.java` | ThreadLocal CommandBus registration (like `AggregatePartitionCommandBus`) |

### Modified files:
| File | Change |
|------|--------|
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Add reflector auto-config |

### New resource files:
| File | Description |
|------|-------------|
| `akces-reflector.properties` | Default Kafka consumer properties for reflector |

## Usage Example

```java
// NotificationResult carries the typed response from the external API
public record NotificationResult(String notificationId, String status) {}

@ReflectorInfo(value = "NotificationReflector", version = 1, schemaName = "Accounts")
public class NotificationReflector implements Reflector<NotificationResult> {
    
    private final NotificationApiClient apiClient;
    
    public NotificationReflector(NotificationApiClient apiClient) {
        this.apiClient = apiClient;
    }
    
    @ReflectorEventHandler
    public NotificationResult handle(AccountCreatedEvent event) throws Exception {
        // Calls an external API and returns the typed response; may throw on failure
        return apiClient.sendWelcomeNotification(event.userId(), event.email());
    }
    
    @ReflectorEventHandler
    public NotificationResult handle(OrderConfirmedEvent event) throws Exception {
        return apiClient.sendOrderConfirmation(event.orderId());
    }
    
    @Override
    public void onSuccess(DomainEvent event, NotificationResult result, CommandBus commandBus) {
        // Use the typed API result to build a precise Command payload
        if (event instanceof AccountCreatedEvent ace) {
            commandBus.send(new MarkNotificationSentCommand(
                ace.userId(),
                result.notificationId(),  // from the API response
                result.status()
            ));
        }
    }
    
    @Override
    public void onFailure(DomainEvent event, Exception exception, CommandBus commandBus) {
        // Send a command back to signal the failure via the CommandBus
        if (event instanceof AccountCreatedEvent ace) {
            commandBus.send(new MarkNotificationFailedCommand(
                ace.userId(), 
                "Welcome notification failed: " + exception.getMessage()
            ));
        }
    }
}
```

If the API result is not needed in `onSuccess`, the handler may return `null` (or use `Void` as the type parameter and return `null`). The `onSuccess` default implementation discards `result` unless overridden.

## Resolved Design Decisions (from Architect Review)

1. **Retry configuration scope**: Per-Reflector (on `@ReflectorInfo`). The `maxRetries` and `retryBackoffBaseMs` are set on the reflector class level, not per-handler. This keeps it simple.

2. **Dead Letter Topic**: No. After 5 retry attempts (or the configured `maxRetries`), the `onFailure` method is called. The implementor decides what to do (send a command, log, etc.). No DLT support.

3. **Concurrency**: No async. One thread per partition, sequential processing. Simple and predictable.

4. **Circuit Breaker**: Not in scope for the initial implementation. Could be added as a future enhancement using **Spring Cloud Circuit Breaker** (with Resilience4j as the backing implementation). Spring does not have a built-in circuit breaker — Spring Cloud Circuit Breaker is an abstraction layer, and Resilience4j is the recommended implementation.

5. **Metrics/Observability**: OK — Micrometer metrics will be added for retry counts, success/failure rates, and backoff times. (Can be done as a follow-up task.)

6. **Command sending on success and failure**: Expose the existing `CommandBus` interface (`org.elasticsoftware.akces.commands.CommandBus`) to both `onSuccess` and `onFailure` methods. Internally backed by the `ReflectorPartition` which implements `CommandBus` and sends commands within the Kafka transaction. This follows the same pattern as `AggregatePartition` implements `CommandBus` for process managers.

7. **Typed API result in `onSuccess`**: The `Reflector` interface is parameterized on `R` (the API result type). The `@ReflectorEventHandler` method returns `R` instead of `void`. On success, the framework passes the returned value directly to `onSuccess(event, result, commandBus)`, allowing the implementor to build precise Command payloads from the API response without storing it in fields. If no result is needed, `R` can be `Void` and the handler returns `null`.

8. **Offset management**: Use **Kafka transactions** (transactional producer with `beginTransaction()`, `sendOffsetsToTransaction()`, `commitTransaction()`) instead of `consumer.commitSync()`. This is more reliable and guarantees exactly-once semantics. Follows the same pattern used by `AggregatePartition` in the runtime module.

9. **Retry mechanism**: Non-blocking, poll-loop integrated. On failure, the Kafka transaction is rolled back (offset not committed), retry state is stored in memory, and control returns to the poll loop. The event is redelivered on the next poll. The partition is paused/resumed to implement backoff. Retry state is in-memory only; on process restart, retry count resets to 0 (acceptable since external calls must be idempotent).

## Producer Sharing Analysis: AkcesClient vs ReflectorPartition

The architect requested an exploration of whether the same Kafka `Producer` instance can be shared between `AkcesClient` and `ReflectorPartition`.

### Current AkcesClient Producer Architecture

The `AkcesClientController` (in `main/client`) runs on its own thread and manages its own lifecycle:

1. **Producer creation**: `producerFactory.createProducer(HostUtils.getHostName() + "-AkcesClientController")` — creates a transactional producer with a unique transaction ID
2. **Command flow**: Commands are submitted via a `BlockingQueue<CommandRequest>` (`commandQueue`). The controller's thread polls this queue and sends commands in batches within Kafka transactions:
   ```
   producer.beginTransaction()
   for each command: KafkaSender.send(producer, record)
   producer.commitTransaction()
   ```
3. **Thread ownership**: The producer is exclusively used by the `AkcesClientController` thread — Kafka transactional producers are **not thread-safe**

### Why Sharing the Same Producer Instance Is Not Possible

Kafka transactional producers have strict threading constraints:

1. **Transaction state is per-producer**: A `Producer` can have at most one active transaction at a time. If `ReflectorPartition` and `AkcesClient` shared the same producer, they would need to coordinate transaction boundaries — one cannot call `beginTransaction()` while the other has an active transaction.

2. **Thread safety**: The Kafka `Producer` API is not designed for concurrent use within transactions. While `producer.send()` is thread-safe in isolation, transaction operations (`beginTransaction()`, `commitTransaction()`, `abortTransaction()`, `sendOffsetsToTransaction()`) are not safe to interleave from different threads.

3. **Transaction scope**: The `ReflectorPartition` needs the offset commit (`sendOffsetsToTransaction`) and any success/failure commands to be in the **same** transaction. If commands were routed through `AkcesClient`'s queue, they would end up in a different transaction on a different thread — breaking atomicity.

### Recommended Approach: Shared ProducerFactory, Separate Producer Instances

The `ReflectorPartition` should use the **same `ProducerFactory`** (and thus the same Kafka cluster configuration) as `AkcesClient`, but create its **own `Producer` instance** with a unique transaction ID. This is the same pattern used by `AggregatePartition`:

| Component | Producer Instance | Transaction ID | Thread |
|-----------|------------------|----------------|--------|
| `AkcesClientController` | Dedicated producer | `{hostname}-AkcesClientController` | AkcesClient thread |
| `AggregatePartition` | Per-partition producer | `{aggregate}Aggregate-partition-{id}-{hostname}` | Partition thread |
| `ReflectorPartition` | Per-partition producer | `{reflector}Reflector-partition-{id}-{hostname}` | Partition thread |

All three use `CustomKafkaProducerFactory` to create producers, sharing the same Kafka cluster configuration. The command-sending logic in `ReflectorPartition.send(Command)` mirrors `AggregatePartition.send(Command)` — it resolves the command type, topic, and partition via `AkcesRegistry` and sends directly via its own producer within its own transaction.

### Potential AkcesClient Concern

The architect noted this "may be an issue with the current AkcesClient implementation." The potential issue is that **AkcesClient's command-sending logic is tightly coupled to its internal thread and producer**:

- The `AkcesClient` exposes a public API (`send(tenantId, command)`) that puts commands on an internal queue
- The internal `AkcesClientController` thread picks up commands and sends them via its own producer
- There is no way to externally inject commands into an already-active transaction on a different producer

For the Reflector's `CommandBus`, we need commands to be sent within the **ReflectorPartition's transaction**, not the AkcesClient's transaction. This means the `ReflectorPartition` must implement `CommandBus` directly (using its own producer), rather than delegating to `AkcesClient`. The command serialization and routing logic (`resolveType`, `resolveTopic`, `resolvePartition`) can be shared via `AkcesRegistry`, but the actual `producer.send()` must happen on the ReflectorPartition's own producer within its active transaction.

This is consistent with how `AggregatePartition` implements `CommandBus` — it sends commands directly via its own transactional producer, not through `AkcesClient`.
