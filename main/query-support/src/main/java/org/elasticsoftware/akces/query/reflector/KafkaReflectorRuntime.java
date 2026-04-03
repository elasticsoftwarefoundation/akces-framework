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
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.ReflectorInfo;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.gdpr.GDPRContextHolder;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.query.Reflector;
import org.elasticsoftware.akces.query.ReflectorEventHandlerFunction;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class KafkaReflectorRuntime implements ReflectorRuntime {

    private static final Logger logger = LoggerFactory.getLogger(KafkaReflectorRuntime.class);

    /**
     * Holds in-memory retry state for a single in-flight event on a given partition.
     *
     * @param attempt        the current retry attempt count (1-based)
     * @param nextAttemptAt  the earliest instant at which the next attempt should be made
     * @param lastException  the exception thrown by the most recent handler invocation
     * @param eventRecord    the event record being retried (kept so the same record is
     *                       re-materialized on every retry rather than storing the full event)
     */
    private record RetryState(
            int attempt,
            Instant nextAttemptAt,
            Exception lastException,
            DomainEventRecord eventRecord
    ) {}

    private final ObjectMapper objectMapper;
    private final Map<Class<?>, DomainEventType<?>> domainEvents;
    @SuppressWarnings("rawtypes")
    private final Map<DomainEventType<?>, ReflectorEventHandlerFunction> eventHandlerFunctions;
    private final String name;
    private final int version;
    private final int maxRetries;
    private final long retryBackoffBaseMs;
    private final boolean shouldHandlePIIData;

    /** Per-partition retry state; at most one pending retry per partition at any time. */
    private final Map<TopicPartition, RetryState> retryStates = new ConcurrentHashMap<>();

    /** Holds the deserialized event from the most recent {@link #apply} call that produced a terminal result. */
    private DomainEvent lastEvent;
    /** Holds the handler result from the most recent successful {@link #apply} call. */
    private Object lastResult;
    /** Holds the last exception from the most recent {@link #apply} call that produced {@link ReflectorResult#FAILURE}. */
    private Exception lastException;

    @SuppressWarnings("rawtypes")
    private KafkaReflectorRuntime(ObjectMapper objectMapper,
                                   Map<Class<?>, DomainEventType<?>> domainEvents,
                                   Map<DomainEventType<?>, ReflectorEventHandlerFunction> eventHandlerFunctions,
                                   String name,
                                   int version,
                                   int maxRetries,
                                   long retryBackoffBaseMs,
                                   boolean shouldHandlePIIData) {
        this.objectMapper = objectMapper;
        this.domainEvents = domainEvents;
        this.eventHandlerFunctions = eventHandlerFunctions;
        this.name = name;
        this.version = version;
        this.maxRetries = maxRetries;
        this.retryBackoffBaseMs = retryBackoffBaseMs;
        this.shouldHandlePIIData = shouldHandlePIIData;
    }

    @Override
    public String getName() {
        return name + "-v" + version;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ReflectorResult apply(DomainEventRecord eventRecord,
                                 TopicPartition topicPartition,
                                 Function<String, GDPRContext> gdprContextSupplier) throws IOException {
        // Clear per-call result fields at the start of each invocation
        lastEvent = null;
        lastResult = null;
        lastException = null;

        // Resolve the domain event type for this record
        DomainEventType<?> domainEventType = getDomainEventType(eventRecord);
        if (domainEventType == null) {
            // No handler registered for this event type — treat as SUCCESS (skip silently)
            logger.debug("No handler registered for event type '{}' version {}; skipping",
                    eventRecord.name(), eventRecord.version());
            return ReflectorResult.SUCCESS;
        }

        ReflectorEventHandlerFunction handler = eventHandlerFunctions.get(domainEventType);
        if (handler == null) {
            // Domain event type known but no handler — skip silently
            logger.debug("No event handler function for domain event type '{}'; skipping",
                    domainEventType.typeName());
            return ReflectorResult.SUCCESS;
        }

        // Capture the current time once for consistent time-based decisions in this invocation
        final Instant now = Instant.now();

        // Check whether there is an existing in-memory retry state for this partition
        RetryState retryState = retryStates.get(topicPartition);
        if (retryState != null) {
            if (now.isBefore(retryState.nextAttemptAt())) {
                // Backoff period has not elapsed yet — tell the caller to keep the partition paused
                logger.debug("Retry backoff not elapsed for partition {} (attempt {}/{}); returning RETRY_PENDING",
                        topicPartition, retryState.attempt(), maxRetries);
                return ReflectorResult.RETRY_PENDING;
            }
            // Backoff has elapsed — fall through to attempt the handler again
            logger.debug("Retry backoff elapsed for partition {} (attempt {}/{}); retrying handler",
                    topicPartition, retryState.attempt(), maxRetries);
        }

        // The current attempt number is 1-based: 1 = first try, 2 = first retry, etc.
        int attempt = (retryState != null) ? retryState.attempt() + 1 : 1;

        // Materialize the event
        DomainEvent event;
        try {
            if (domainEventType.piiData()) {
                GDPRContextHolder.setCurrentGDPRContext(gdprContextSupplier.apply(eventRecord.aggregateId()));
            }
            event = materialize(domainEventType, eventRecord);
        } finally {
            GDPRContextHolder.resetCurrentGDPRContext();
        }

        // Attempt to invoke the handler.
        // totalAttempts = 1 initial attempt + maxRetries retries
        final int totalAttempts = maxRetries + 1;
        try {
            if (domainEventType.piiData()) {
                GDPRContextHolder.setCurrentGDPRContext(gdprContextSupplier.apply(eventRecord.aggregateId()));
            }
            Object result = handler.accept(event);
            // Handler succeeded
            retryStates.remove(topicPartition);
            lastEvent = event;
            lastResult = result;
            logger.debug("Handler succeeded for event '{}' on partition {} (attempt {}/{})",
                    eventRecord.name(), topicPartition, attempt, totalAttempts);
            return ReflectorResult.SUCCESS;
        } catch (Exception e) {
            if (attempt >= totalAttempts) {
                // All attempts exhausted (1 initial + maxRetries retries)
                retryStates.remove(topicPartition);
                lastEvent = event;
                lastException = e;
                logger.warn("All {} retries exhausted for event '{}' on partition {}; returning FAILURE",
                        maxRetries, eventRecord.name(), topicPartition, e);
                return ReflectorResult.FAILURE;
            }
            // Schedule the next retry using linear backoff: delay = attempt * baseIntervalMs
            long backoffMs = (long) attempt * retryBackoffBaseMs;
            Instant nextAttemptAt = now.plusMillis(backoffMs);
            retryStates.put(topicPartition, new RetryState(attempt, nextAttemptAt, e, eventRecord));
            logger.warn("Handler failed for event '{}' on partition {} (attempt {}/{}); next retry in {} ms",
                    eventRecord.name(), topicPartition, attempt, totalAttempts, backoffMs, e);
            return ReflectorResult.RETRY_PENDING;
        } finally {
            GDPRContextHolder.resetCurrentGDPRContext();
        }
    }

    @Override
    public DomainEvent getLastEvent() {
        return lastEvent;
    }

    @Override
    public Object getLastResult() {
        return lastResult;
    }

    @Override
    public Exception getLastException() {
        return lastException;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void handleSuccess(DomainEvent event, Object result, CommandBus commandBus) {
        // Find the handler for this event type in order to obtain the Reflector instance
        DomainEventType<?> domainEventType = domainEvents.get(event.getClass());
        if (domainEventType == null) {
            logger.warn("handleSuccess: no domain event type registered for {}; skipping onSuccess",
                    event.getClass().getName());
            return;
        }
        ReflectorEventHandlerFunction handler = eventHandlerFunctions.get(domainEventType);
        if (handler == null) {
            logger.warn("handleSuccess: no handler registered for domain event type '{}'; skipping onSuccess",
                    domainEventType.typeName());
            return;
        }
        Reflector reflector = handler.getReflector();
        reflector.onSuccess(event, result, commandBus);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void handleFailure(DomainEvent event, Exception exception, CommandBus commandBus) {
        // Find the handler for this event type in order to obtain the Reflector instance
        DomainEventType<?> domainEventType = domainEvents.get(event.getClass());
        if (domainEventType == null) {
            logger.warn("handleFailure: no domain event type registered for {}; skipping onFailure",
                    event.getClass().getName());
            return;
        }
        ReflectorEventHandlerFunction handler = eventHandlerFunctions.get(domainEventType);
        if (handler == null) {
            logger.warn("handleFailure: no handler registered for domain event type '{}'; skipping onFailure",
                    domainEventType.typeName());
            return;
        }
        Reflector reflector = handler.getReflector();
        reflector.onFailure(event, exception, commandBus);
    }

    @Override
    public void validateDomainEventSchemas(SchemaRegistry schemaRegistry) {
        for (DomainEventType<?> domainEventType : domainEvents.values()) {
            schemaRegistry.validate(domainEventType);
        }
    }

    @Override
    public Collection<DomainEventType<?>> getDomainEventTypes() {
        return domainEvents.values();
    }

    @Override
    public boolean shouldHandlePIIData() {
        return shouldHandlePIIData;
    }

    @Override
    public int getMaxRetries() {
        return maxRetries;
    }

    @Override
    public long getRetryBackoffBaseMs() {
        return retryBackoffBaseMs;
    }

    private DomainEvent materialize(DomainEventType<?> domainEventType, DomainEventRecord eventRecord) throws IOException {
        return objectMapper.readValue(eventRecord.payload(), domainEventType.typeClass());
    }

    private DomainEventType<?> getDomainEventType(DomainEventRecord eventRecord) {
        // Find the highest version that is less than or equal to the event record version
        return domainEvents.entrySet().stream()
                .filter(entry -> entry.getValue().external())
                .filter(entry -> entry.getValue().typeName().equals(eventRecord.name()))
                .filter(entry -> entry.getValue().version() <= eventRecord.version())
                .max(Comparator.comparingInt(entry -> entry.getValue().version()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    @Override
    public String toString() {
        return "KafkaReflectorRuntime{" + getName() + "}";
    }

    public static class Builder {
        private final Map<Class<?>, DomainEventType<?>> domainEvents = new HashMap<>();
        @SuppressWarnings("rawtypes")
        private final Map<DomainEventType<?>, ReflectorEventHandlerFunction> eventHandlerFunctions = new HashMap<>();
        private ObjectMapper objectMapper;
        private ReflectorInfo reflectorInfo;

        public Builder setObjectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder setReflectorInfo(ReflectorInfo reflectorInfo) {
            this.reflectorInfo = reflectorInfo;
            return this;
        }

        public Builder addDomainEvent(DomainEventType<?> domainEvent) {
            this.domainEvents.put(domainEvent.typeClass(), domainEvent);
            return this;
        }

        @SuppressWarnings("rawtypes")
        public Builder addEventHandlerFunction(DomainEventType<?> eventType,
                                               ReflectorEventHandlerFunction<?, ?> eventHandlerFunction) {
            this.eventHandlerFunctions.put(eventType, eventHandlerFunction);
            return this;
        }

        public KafkaReflectorRuntime build() {
            final boolean shouldHandlePIIData = domainEvents.values().stream()
                    .map(DomainEventType::typeClass)
                    .anyMatch(GDPRAnnotationUtils::hasPIIDataAnnotation);
            return new KafkaReflectorRuntime(
                    objectMapper,
                    domainEvents,
                    eventHandlerFunctions,
                    reflectorInfo.value(),
                    reflectorInfo.version(),
                    reflectorInfo.maxRetries(),
                    reflectorInfo.retryBackoffBaseMs(),
                    shouldHandlePIIData);
        }
    }
}
