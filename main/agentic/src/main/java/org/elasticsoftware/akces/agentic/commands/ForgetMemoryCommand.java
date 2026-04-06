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
 * Command to forget (revoke) a previously stored memory entry for an
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}.
 *
 * <p>When handled, this command produces a
 * {@link org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent} removing the memory
 * identified by {@code memoryId} from the aggregate's memory store.
 *
 * @param agenticAggregateId the unique identifier of the target AgenticAggregate instance
 * @param memoryId           the UUID of the memory entry to forget
 * @param reason             the reason for forgetting this memory entry
 */
@CommandInfo(type = "ForgetMemory", version = 1)
public record ForgetMemoryCommand(
        @AggregateIdentifier String agenticAggregateId,
        String memoryId,
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
