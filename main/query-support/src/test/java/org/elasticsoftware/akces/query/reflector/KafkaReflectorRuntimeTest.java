/*
 * Copyright 2022 - 2026 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces.query.reflector;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.annotations.ReflectorInfo;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.gdpr.NoopGDPRContext;
import org.elasticsoftware.akces.gdpr.jackson.AkcesGDPRModule;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.elasticsoftware.akces.query.Reflector;
import org.elasticsoftware.akces.query.ReflectorEventHandlerFunction;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaReflectorRuntimeTest {

    // ---------------------------------------------------------------------------
    // Inner test domain event types
    // ---------------------------------------------------------------------------

    @DomainEventInfo(type = "TestEvent", version = 1)
    record TestEvent(String id, String data) implements DomainEvent {
        @Override
        public String getAggregateId() { return id; }
    }

    @DomainEventInfo(type = "AnotherTestEvent", version = 1)
    record AnotherTestEvent(String id) implements DomainEvent {
        @Override
        public String getAggregateId() { return id; }
    }

    /**
     * Test event carrying a PII-annotated field so that
     * {@link KafkaReflectorRuntime#shouldHandlePIIData()} returns {@code true}.
     */
    @DomainEventInfo(type = "PiiTestEvent", version = 1)
    record PiiTestEvent(String id, @PIIData String sensitiveData) implements DomainEvent {
        @Override
        public String getAggregateId() { return id; }
    }

    // ---------------------------------------------------------------------------
    // Test reflector implementation carrying the @ReflectorInfo annotation
    // ---------------------------------------------------------------------------

    @ReflectorInfo(value = "TestReflector", version = 1, schemaName = "TestSchema",
            maxRetries = 5, retryBackoffBaseMs = 100)
    static class TestReflectorImpl implements Reflector<String> {}

    @ReflectorInfo(value = "RetryReflector", version = 1, schemaName = "RetrySchema",
            maxRetries = 3, retryBackoffBaseMs = 50)
    static class RetryReflectorImpl implements Reflector<String> {}

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static ReflectorInfo reflectorInfo() {
        return TestReflectorImpl.class.getAnnotation(ReflectorInfo.class);
    }

    private static ReflectorInfo retryReflectorInfo() {
        return RetryReflectorImpl.class.getAnnotation(ReflectorInfo.class);
    }

    private static ObjectMapper buildObjectMapper() {
        SimpleModule bigDecimalModule = new SimpleModule();
        bigDecimalModule.addSerializer(BigDecimal.class, new BigDecimalSerializer());
        return JsonMapper.builder()
                .addModule(new AkcesGDPRModule())
                .addModule(bigDecimalModule)
                .build();
    }

    /**
     * Builds a minimal {@link DomainEventRecord} whose payload is the JSON serialisation
     * of the supplied event object.
     */
    private static DomainEventRecord eventRecord(DomainEvent event, ObjectMapper mapper) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(event);
        String typeName = event.getClass().getAnnotation(DomainEventInfo.class).type();
        int version = event.getClass().getAnnotation(DomainEventInfo.class).version();
        return new DomainEventRecord(
                "tenant-1",
                typeName,
                version,
                payload,
                PayloadEncoding.JSON,
                event.getAggregateId(),
                "corr-1",
                1L);
    }

    // ---------------------------------------------------------------------------
    // Mocks
    // ---------------------------------------------------------------------------

    @Mock
    private CommandBus commandBus;

    // ---------------------------------------------------------------------------
    // Shared state
    // ---------------------------------------------------------------------------

    private ObjectMapper objectMapper;
    private TopicPartition topicPartition;

    @BeforeEach
    void setUp() {
        objectMapper = buildObjectMapper();
        topicPartition = new TopicPartition("test-topic", 0);
    }

    // ---------------------------------------------------------------------------
    // 1. Happy path – handler succeeds on first attempt
    // ---------------------------------------------------------------------------

    @Test
    void testHappyPath() throws Exception {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        when(handler.accept(any(TestEvent.class))).thenReturn("ok");

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(reflectorInfo())
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        TestEvent event = new TestEvent("agg-1", "hello");
        DomainEventRecord record = eventRecord(event, objectMapper);

        ReflectorResult result = runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id));

        assertThat(result).isEqualTo(ReflectorResult.SUCCESS);
        assertThat(runtime.getLastResult()).isEqualTo("ok");
        assertThat(runtime.getLastException()).isNull();
        verify(handler, times(1)).accept(any(TestEvent.class));
    }

    // ---------------------------------------------------------------------------
    // 2. Retry then succeed – fail once, succeed on the second attempt
    // ---------------------------------------------------------------------------

    @Test
    void testRetryThenSucceed() throws Exception {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        RuntimeException failure = new RuntimeException("transient");
        when(handler.accept(any(TestEvent.class)))
                .thenThrow(failure)
                .thenReturn("recovered");

        // retryBackoffBaseMs = 50 ms, so after 1 failure backoff = 1*50 = 50 ms
        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(retryReflectorInfo())
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        TestEvent event = new TestEvent("agg-1", "hello");
        DomainEventRecord record = eventRecord(event, objectMapper);

        // First attempt – handler throws → RETRY_PENDING
        ReflectorResult firstResult = runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id));
        assertThat(firstResult).isEqualTo(ReflectorResult.RETRY_PENDING);

        // Wait for the backoff to elapse (50 ms * 2 safety margin)
        Thread.sleep(120);

        // Second attempt – handler succeeds → SUCCESS
        ReflectorResult secondResult = runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id));
        assertThat(secondResult).isEqualTo(ReflectorResult.SUCCESS);
        assertThat(runtime.getLastResult()).isEqualTo("recovered");
        assertThat(runtime.getLastException()).isNull();
    }

    // ---------------------------------------------------------------------------
    // 3. RETRY_PENDING during active backoff window
    // ---------------------------------------------------------------------------

    @Test
    void testRetryPendingDuringBackoff() throws Exception {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        when(handler.accept(any(TestEvent.class))).thenThrow(new RuntimeException("boom"));

        // Use a large backoffBaseMs so the backoff will definitely not elapse during the test
        @ReflectorInfo(value = "SlowReflector", version = 1, schemaName = "SlowSchema",
                maxRetries = 5, retryBackoffBaseMs = 30000)
        class SlowReflectorImpl implements Reflector<String> {}

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(SlowReflectorImpl.class.getAnnotation(ReflectorInfo.class))
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        TestEvent event = new TestEvent("agg-1", "hello");
        DomainEventRecord record = eventRecord(event, objectMapper);

        // First call – fails → backoff started
        ReflectorResult first = runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id));
        assertThat(first).isEqualTo(ReflectorResult.RETRY_PENDING);

        // Immediate second call – backoff has NOT elapsed → still RETRY_PENDING, handler NOT called again
        ReflectorResult second = runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id));
        assertThat(second).isEqualTo(ReflectorResult.RETRY_PENDING);

        // Handler should only have been called once (during the first apply)
        verify(handler, times(1)).accept(any(TestEvent.class));
    }

    // ---------------------------------------------------------------------------
    // 4. All retries exhausted → FAILURE
    // ---------------------------------------------------------------------------

    @Test
    void testAllRetriesExhausted() throws Exception {
        // maxRetries = 3 → totalAttempts = 4; retryBackoffBaseMs = 1 ms (tiny so tests run fast)
        @ReflectorInfo(value = "ExhaustReflector", version = 1, schemaName = "ExhaustSchema",
                maxRetries = 3, retryBackoffBaseMs = 1)
        class ExhaustReflectorImpl implements Reflector<String> {}

        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        RuntimeException terminal = new RuntimeException("always fails");

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        when(handler.accept(any(TestEvent.class))).thenThrow(terminal);

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(ExhaustReflectorImpl.class.getAnnotation(ReflectorInfo.class))
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        TestEvent event = new TestEvent("agg-1", "data");
        DomainEventRecord record = eventRecord(event, objectMapper);

        // We need maxRetries+1 = 4 successful handler invocations (i.e. when backoff elapses)
        // attempt 1 → RETRY_PENDING
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.RETRY_PENDING);

        // Let backoff elapse between each retry
        Thread.sleep(10);
        // attempt 2 → RETRY_PENDING
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.RETRY_PENDING);

        Thread.sleep(10);
        // attempt 3 → RETRY_PENDING
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.RETRY_PENDING);

        Thread.sleep(10);
        // attempt 4 (= totalAttempts) → FAILURE
        ReflectorResult finalResult = runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id));
        assertThat(finalResult).isEqualTo(ReflectorResult.FAILURE);
        assertThat(runtime.getLastException()).isSameAs(terminal);
        assertThat(runtime.getLastResult()).isNull();

        verify(handler, times(4)).accept(any(TestEvent.class));
    }

    // ---------------------------------------------------------------------------
    // 5. Linear backoff timing formula: backoffMs = attempt * retryBackoffBaseMs
    // ---------------------------------------------------------------------------

    @Test
    void testBackoffTiming() throws Exception {
        // Use a moderate baseMs so we can measure timing without making tests slow
        final long baseMs = 50L;

        @ReflectorInfo(value = "BackoffReflector", version = 1, schemaName = "BackoffSchema",
                maxRetries = 5, retryBackoffBaseMs = 50)
        class BackoffReflectorImpl implements Reflector<String> {}

        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        when(handler.accept(any(TestEvent.class)))
                .thenThrow(new RuntimeException("fail-1"))   // attempt 1
                .thenThrow(new RuntimeException("fail-2"))   // attempt 2
                .thenReturn("success");                      // attempt 3

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(BackoffReflectorImpl.class.getAnnotation(ReflectorInfo.class))
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        TestEvent event = new TestEvent("agg-1", "data");
        DomainEventRecord record = eventRecord(event, objectMapper);

        // Attempt 1 fails → backoff = 1 * 50 = 50 ms
        long t0 = System.currentTimeMillis();
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.RETRY_PENDING);

        // Calling before 50 ms → still RETRY_PENDING (handler not called)
        Thread.sleep(10);
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.RETRY_PENDING);
        verify(handler, times(1)).accept(any(TestEvent.class));

        // Wait for full backoff then retry → attempt 2 fails → backoff = 2 * 50 = 100 ms
        Thread.sleep(baseMs + 20);
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.RETRY_PENDING);
        verify(handler, times(2)).accept(any(TestEvent.class));

        // Calling immediately after attempt 2 → still within 100 ms backoff
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.RETRY_PENDING);
        verify(handler, times(2)).accept(any(TestEvent.class));

        // Wait for 2 * 50 = 100 ms backoff then succeed
        Thread.sleep(2 * baseMs + 20);
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.SUCCESS);
        verify(handler, times(3)).accept(any(TestEvent.class));
    }

    // ---------------------------------------------------------------------------
    // 6. Custom maxRetries – exactly maxRetries+1 handler invocations before FAILURE
    // ---------------------------------------------------------------------------

    @Test
    void testCustomMaxRetries() throws Exception {
        @ReflectorInfo(value = "CustomRetryReflector", version = 1, schemaName = "CustomRetrySchema",
                maxRetries = 3, retryBackoffBaseMs = 1)
        class CustomRetryReflectorImpl implements Reflector<String> {}

        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        when(handler.accept(any(TestEvent.class))).thenThrow(new RuntimeException("always"));

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(CustomRetryReflectorImpl.class.getAnnotation(ReflectorInfo.class))
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        assertThat(runtime.getMaxRetries()).isEqualTo(3);

        TestEvent event = new TestEvent("agg-1", "data");
        DomainEventRecord record = eventRecord(event, objectMapper);

        // 3 retries → 4 total attempts; first 3 return RETRY_PENDING, 4th returns FAILURE
        for (int i = 0; i < 3; i++) {
            Thread.sleep(10);
            assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                    .as("attempt %d should be RETRY_PENDING", i + 1)
                    .isEqualTo(ReflectorResult.RETRY_PENDING);
        }
        Thread.sleep(10);
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.FAILURE);

        // Total handler invocations = maxRetries + 1 = 4
        verify(handler, times(4)).accept(any(TestEvent.class));
    }

    // ---------------------------------------------------------------------------
    // 7. Custom retryBackoffBaseMs is applied correctly
    // ---------------------------------------------------------------------------

    @Test
    void testCustomBackoffBaseMs() throws Exception {
        @ReflectorInfo(value = "CustomBackoffReflector", version = 1, schemaName = "CustomBackoffSchema",
                maxRetries = 5, retryBackoffBaseMs = 200)
        class CustomBackoffReflectorImpl implements Reflector<String> {}

        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        when(handler.accept(any(TestEvent.class)))
                .thenThrow(new RuntimeException("fail"))
                .thenReturn("ok");

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(CustomBackoffReflectorImpl.class.getAnnotation(ReflectorInfo.class))
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        assertThat(runtime.getRetryBackoffBaseMs()).isEqualTo(200L);

        TestEvent event = new TestEvent("agg-1", "data");
        DomainEventRecord record = eventRecord(event, objectMapper);

        // First attempt fails → 200 ms backoff
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.RETRY_PENDING);

        // Before 200 ms → still pending
        Thread.sleep(50);
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.RETRY_PENDING);
        verify(handler, times(1)).accept(any(TestEvent.class));

        // After 200 ms → succeeds
        Thread.sleep(200);
        assertThat(runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.SUCCESS);
        assertThat(runtime.getLastResult()).isEqualTo("ok");
    }

    // ---------------------------------------------------------------------------
    // 8. Unknown event type is skipped silently (returns SUCCESS)
    // ---------------------------------------------------------------------------

    @Test
    void testUnknownEventType() throws Exception {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(reflectorInfo())
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        // Build a record for an event type that is NOT registered
        byte[] payload = objectMapper.writeValueAsBytes(new AnotherTestEvent("agg-2"));
        DomainEventRecord unknownRecord = new DomainEventRecord(
                "tenant-1", "UnregisteredEvent", 1, payload,
                PayloadEncoding.JSON, "agg-2", "corr-2", 1L);

        ReflectorResult result = runtime.apply(unknownRecord, topicPartition, id -> new NoopGDPRContext(id));

        assertThat(result).isEqualTo(ReflectorResult.SUCCESS);
        assertThat(runtime.getLastResult()).isNull();
        assertThat(runtime.getLastException()).isNull();
        verify(handler, never()).accept(any());
    }

    // ---------------------------------------------------------------------------
    // 9. Multiple event types – correct handler is invoked for each event
    // ---------------------------------------------------------------------------

    @Test
    void testMultipleEventTypes() throws Exception {
        DomainEventType<TestEvent> testEventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);
        DomainEventType<AnotherTestEvent> anotherEventType = new DomainEventType<>(
                "AnotherTestEvent", 1, AnotherTestEvent.class, false, true, false, false);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> testHandler =
                mock(ReflectorEventHandlerFunction.class);
        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<AnotherTestEvent, String> anotherHandler =
                mock(ReflectorEventHandlerFunction.class);

        when(testHandler.accept(any(TestEvent.class))).thenReturn("test-result");
        when(anotherHandler.accept(any(AnotherTestEvent.class))).thenReturn("another-result");

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(reflectorInfo())
                .addDomainEvent(testEventType)
                .addEventHandlerFunction(testEventType, testHandler)
                .addDomainEvent(anotherEventType)
                .addEventHandlerFunction(anotherEventType, anotherHandler)
                .build();

        DomainEventRecord testRecord = eventRecord(new TestEvent("agg-1", "data"), objectMapper);
        DomainEventRecord anotherRecord = eventRecord(new AnotherTestEvent("agg-2"), objectMapper);
        TopicPartition partition2 = new TopicPartition("test-topic", 1);

        // Process TestEvent
        assertThat(runtime.apply(testRecord, topicPartition, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.SUCCESS);
        assertThat(runtime.getLastResult()).isEqualTo("test-result");
        verify(testHandler, times(1)).accept(any(TestEvent.class));
        verify(anotherHandler, never()).accept(any());

        // Process AnotherTestEvent
        assertThat(runtime.apply(anotherRecord, partition2, id -> new NoopGDPRContext(id)))
                .isEqualTo(ReflectorResult.SUCCESS);
        assertThat(runtime.getLastResult()).isEqualTo("another-result");
        verify(anotherHandler, times(1)).accept(any(AnotherTestEvent.class));
        verify(testHandler, times(1)).accept(any(TestEvent.class)); // still 1
    }

    // ---------------------------------------------------------------------------
    // 10. handleSuccess delegates to Reflector.onSuccess with correct arguments
    // ---------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void testHandleSuccessDelegation() throws Exception {
        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        Reflector<String> reflector = mock(Reflector.class);

        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        when(handler.accept(any(TestEvent.class))).thenReturn("result");
        when(handler.getReflector()).thenReturn(reflector);

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(reflectorInfo())
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        TestEvent event = new TestEvent("agg-1", "hello");
        DomainEventRecord record = eventRecord(event, objectMapper);

        runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id));
        DomainEvent lastEvent = runtime.getLastEvent();
        Object lastResult = runtime.getLastResult();

        runtime.handleSuccess(lastEvent, lastResult, commandBus);

        verify(reflector, times(1)).onSuccess(eq(lastEvent), eq("result"), eq(commandBus));
        verify(reflector, never()).onFailure(any(), any(), any());
    }

    // ---------------------------------------------------------------------------
    // 11. handleFailure delegates to Reflector.onFailure with the correct exception
    // ---------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void testHandleFailureDelegation() throws Exception {
        @ReflectorInfo(value = "FailureReflector", version = 1, schemaName = "FailureSchema",
                maxRetries = 0, retryBackoffBaseMs = 1)
        class FailureReflectorImpl implements Reflector<String> {}

        DomainEventType<TestEvent> eventType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        Reflector<String> reflector = mock(Reflector.class);
        RuntimeException cause = new RuntimeException("delegated");

        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        when(handler.accept(any(TestEvent.class))).thenThrow(cause);
        when(handler.getReflector()).thenReturn(reflector);

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(FailureReflectorImpl.class.getAnnotation(ReflectorInfo.class))
                .addDomainEvent(eventType)
                .addEventHandlerFunction(eventType, handler)
                .build();

        TestEvent event = new TestEvent("agg-1", "hello");
        DomainEventRecord record = eventRecord(event, objectMapper);

        // maxRetries=0 → 1 total attempt → immediate FAILURE
        ReflectorResult result = runtime.apply(record, topicPartition, id -> new NoopGDPRContext(id));
        assertThat(result).isEqualTo(ReflectorResult.FAILURE);

        DomainEvent lastEvent = runtime.getLastEvent();
        Exception lastException = runtime.getLastException();

        runtime.handleFailure(lastEvent, lastException, commandBus);

        verify(reflector, times(1)).onFailure(eq(lastEvent), eq(cause), eq(commandBus));
        verify(reflector, never()).onSuccess(any(), any(), any());
    }

    // ---------------------------------------------------------------------------
    // 12. GDPR context supplier is invoked when event has PII data
    // ---------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void testGDPRContext() throws Exception {
        DomainEventType<PiiTestEvent> piiEventType = new DomainEventType<>(
                "PiiTestEvent", 1, PiiTestEvent.class, false, true, false, true);

        ReflectorEventHandlerFunction<PiiTestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);
        when(handler.accept(any(PiiTestEvent.class))).thenReturn("pii-result");

        KafkaReflectorRuntime runtime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(reflectorInfo())
                .addDomainEvent(piiEventType)
                .addEventHandlerFunction(piiEventType, handler)
                .build();

        PiiTestEvent event = new PiiTestEvent("agg-pii", "secret data");
        DomainEventRecord record = eventRecord(event, objectMapper);

        Function<String, GDPRContext> gdprSupplier = mock(Function.class);
        when(gdprSupplier.apply(anyString())).thenReturn(new NoopGDPRContext("agg-pii"));

        ReflectorResult result = runtime.apply(record, topicPartition, gdprSupplier);

        assertThat(result).isEqualTo(ReflectorResult.SUCCESS);
        // The supplier should have been called at least once (once for materialization, once for handler)
        verify(gdprSupplier, atLeastOnce()).apply("agg-pii");
    }

    // ---------------------------------------------------------------------------
    // 13. shouldHandlePIIData returns the correct flag
    // ---------------------------------------------------------------------------

    @Test
    void testShouldHandlePIIData() throws Exception {
        // Without PII event
        DomainEventType<TestEvent> nonPiiType = new DomainEventType<>(
                "TestEvent", 1, TestEvent.class, false, true, false, false);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<TestEvent, String> handler =
                mock(ReflectorEventHandlerFunction.class);

        KafkaReflectorRuntime nonPiiRuntime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(reflectorInfo())
                .addDomainEvent(nonPiiType)
                .addEventHandlerFunction(nonPiiType, handler)
                .build();

        assertThat(nonPiiRuntime.shouldHandlePIIData()).isFalse();

        // With PII event (PiiTestEvent has @PIIData on sensitiveData field)
        DomainEventType<PiiTestEvent> piiType = new DomainEventType<>(
                "PiiTestEvent", 1, PiiTestEvent.class, false, true, false, true);

        @SuppressWarnings("unchecked")
        ReflectorEventHandlerFunction<PiiTestEvent, String> piiHandler =
                mock(ReflectorEventHandlerFunction.class);

        KafkaReflectorRuntime piiRuntime = new KafkaReflectorRuntime.Builder()
                .setObjectMapper(objectMapper)
                .setReflectorInfo(reflectorInfo())
                .addDomainEvent(piiType)
                .addEventHandlerFunction(piiType, piiHandler)
                .build();

        assertThat(piiRuntime.shouldHandlePIIData()).isTrue();
    }
}
