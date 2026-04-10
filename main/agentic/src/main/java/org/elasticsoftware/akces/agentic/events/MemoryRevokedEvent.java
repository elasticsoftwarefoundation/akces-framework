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
 * Domain event produced when a memory entry has been revoked (forgotten) from an
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}.
 *
 * <p>This event is produced internally by the Embabel layer (via the agent's memory
 * management tools) when a memory is explicitly revoked, or as part of the
 * sliding-window eviction mechanism when
 * {@link org.elasticsoftware.akces.annotations.AgenticAggregateInfo#maxMemories()} is exceeded.
 *
 * @param agenticAggregateId the unique identifier of the AgenticAggregate instance
 * @param memoryId           UUID of the memory entry that was revoked
 * @param reason             the reason the memory was revoked
 * @param revokedAt          the instant at which the memory was revoked
 */
@DomainEventInfo(type = "MemoryRevoked", version = 1)
public record MemoryRevokedEvent(
        @AggregateIdentifier String agenticAggregateId,
        String memoryId,
        String reason,
        Instant revokedAt
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
