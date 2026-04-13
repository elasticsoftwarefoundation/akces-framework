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
import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.time.Instant;

/**
 * Domain event emitted when a memory distillation process has finished for an
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}.
 *
 * <p>This event is produced after the distillation agent process completes (regardless
 * of whether it produced any memory events). The built-in event-sourcing handler
 * processes this event by clearing the active
 * {@link org.elasticsoftware.akces.aggregate.MemoryDistillation} from the aggregate
 * state's {@link org.elasticsoftware.akces.aggregate.MemoryAwareState}.
 *
 * @param agenticAggregateId the identifier of the agentic aggregate
 * @param agentProcessId     the Embabel {@code AgentProcess.getId()} value for the distillation process
 * @param finishedAt         the instant at which the distillation finished
 */
@DomainEventInfo(type = "MemoryDistillationFinished", version = 1,
        description = "Emitted when a memory distillation process has finished")
public record MemoryDistillationFinishedEvent(
        @AggregateIdentifier String agenticAggregateId,
        @NotNull String agentProcessId,
        @NotNull Instant finishedAt
) implements DomainEvent {

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    public String getAggregateId() {
        return agenticAggregateId;
    }
}
