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

import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.schemas.SchemaRegistry;

import java.io.IOException;
import java.util.Collection;
import java.util.function.Function;

public interface ReflectorRuntime {

    String getName();

    /**
     * Process a single domain event record. Handles retry logic internally.
     *
     * <p>Returns {@link ReflectorResult#SUCCESS} if the event was processed successfully
     * (either on first try or after retries), {@link ReflectorResult#FAILURE} if all retries
     * were exhausted and the event is considered failed, or {@link ReflectorResult#RETRY_PENDING}
     * if the handler failed but retries remain and the backoff period has not yet elapsed
     * (the caller should pause the partition and redeliver the event on the next poll).
     *
     * @param eventRecord          the domain event record to process
     * @param topicPartition       the Kafka topic partition from which the event was consumed;
     *                             used to key per-partition retry state
     * @param gdprContextSupplier  supplies the GDPR context for the event's aggregate ID
     * @return the result of processing the event
     * @throws IOException if deserialization of the event payload fails
     */
    ReflectorResult apply(DomainEventRecord eventRecord,
                          TopicPartition topicPartition,
                          Function<String, GDPRContext> gdprContextSupplier) throws IOException;

    /**
     * Returns the domain event that was last successfully deserialized by {@link #apply}.
     * Valid after {@link #apply} returns {@link ReflectorResult#SUCCESS} or
     * {@link ReflectorResult#FAILURE}. The caller uses this to pass the event to
     * {@link #handleSuccess} or {@link #handleFailure}.
     *
     * @return the last deserialized domain event, or {@code null} if not yet set
     */
    DomainEvent getLastEvent();

    /**
     * Returns the API result from the last successful handler invocation.
     * Valid after {@link #apply} returns {@link ReflectorResult#SUCCESS}.
     *
     * @return the result returned by the handler, or {@code null}
     */
    Object getLastResult();

    /**
     * Returns the last exception recorded when retries were exhausted.
     * Valid after {@link #apply} returns {@link ReflectorResult#FAILURE}.
     *
     * @return the last exception thrown by the handler, or {@code null}
     */
    Exception getLastException();

    /**
     * Called after successful event processing. Delegates to {@code Reflector.onSuccess()},
     * passing the typed API result and the {@link CommandBus} so the implementor can send
     * success commands back to an aggregate.
     *
     * @param event      the event that was successfully processed
     * @param result     the value returned by the handler (the external API response); may be {@code null}
     * @param commandBus the {@link CommandBus} to use for sending success commands
     */
    void handleSuccess(DomainEvent event, Object result, CommandBus commandBus);

    /**
     * Called when all retries are exhausted. Delegates to {@code Reflector.onFailure()},
     * passing the last exception and the {@link CommandBus} so the implementor can send
     * failure commands back to an aggregate.
     *
     * @param event      the event that failed to be processed
     * @param exception  the last exception thrown by the handler
     * @param commandBus the {@link CommandBus} to use for sending failure commands
     */
    void handleFailure(DomainEvent event, Exception exception, CommandBus commandBus);

    void validateDomainEventSchemas(SchemaRegistry schemaRegistry);

    boolean shouldHandlePIIData();

    Collection<DomainEventType<?>> getDomainEventTypes();

    int getMaxRetries();

    long getRetryBackoffBaseMs();
}
