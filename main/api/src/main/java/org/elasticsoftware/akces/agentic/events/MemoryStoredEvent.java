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

package org.elasticsoftware.akces.agentic.events;

import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.time.Instant;

/**
 * Domain event produced when a new memory entry has been successfully stored for an
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}.
 *
 * <p>This event carries all metadata of the stored memory so that downstream
 * consumers and the aggregate's own event-sourcing handlers can reconstruct state.
 *
 * @param agenticAggregateId the unique identifier of the AgenticAggregate instance
 * @param memoryId           UUID uniquely identifying this memory entry
 * @param subject            a short (1–3 word) topic label for the stored memory
 * @param fact               the fact that was stored (max 200 characters)
 * @param citations          the source citation for the fact
 * @param reason             the reason the memory was stored
 * @param storedAt           the instant at which the memory was recorded
 */
@DomainEventInfo(type = "MemoryStored", version = 1)
public record MemoryStoredEvent(
        @AggregateIdentifier String agenticAggregateId,
        String memoryId,
        String subject,
        String fact,
        String citations,
        String reason,
        Instant storedAt
) implements DomainEvent {

    /**
     * Returns the aggregate identifier for routing.
     *
     * @return the {@code agenticAggregateId}
     */
    @Override
    @Nonnull
    public String getAggregateId() {
        return agenticAggregateId;
    }
}
