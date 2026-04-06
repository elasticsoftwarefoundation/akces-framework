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

package org.elasticsoftware.akces.agentic.commands;

import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

/**
 * Command to store a new memory entry for an {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}.
 *
 * <p>When handled, this command produces a
 * {@link org.elasticsoftware.akces.agentic.events.MemoryStoredEvent} and, if the
 * {@link org.elasticsoftware.akces.annotations.AgenticAggregateInfo#maxMemories()} sliding-window
 * capacity is reached, a subsequent
 * {@link org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent} is automatically emitted
 * to evict the oldest memory entry.
 *
 * @param agenticAggregateId the unique identifier of the target AgenticAggregate instance
 * @param subject            a short (1–2 word) topic label for the memory being stored
 * @param fact               the fact to remember (max 200 characters)
 * @param citations          the source of the fact (e.g., a file path and line number)
 * @param reason             the reason for storing this fact
 */
@CommandInfo(type = "StoreMemory", version = 1)
public record StoreMemoryCommand(
        @AggregateIdentifier String agenticAggregateId,
        String subject,
        String fact,
        String citations,
        String reason
) implements Command {

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
