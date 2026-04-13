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
 * Domain event emitted when a memory distillation process has failed for an
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}.
 *
 * <p>This event is produced when the {@code AgentProcess} for an active
 * {@link org.elasticsoftware.akces.aggregate.MemoryDistillation} can no longer be found
 * on the {@code AgentPlatform}, typically after a restart or crash. The built-in
 * event-sourcing handler processes this event by removing the corresponding
 * {@link org.elasticsoftware.akces.aggregate.MemoryDistillation} from the aggregate
 * state's {@link org.elasticsoftware.akces.aggregate.MemoryAwareState}.
 *
 * @param agenticAggregateId the identifier of the agentic aggregate
 * @param agentProcessId     the Embabel {@code AgentProcess.getId()} value for the distillation process
 * @param failedAt           the instant at which the failure was detected
 */
@DomainEventInfo(type = "MemoryDistillationFailed", version = 1,
        description = "Emitted when a memory distillation process has failed")
public record MemoryDistillationFailedEvent(
        @AggregateIdentifier String agenticAggregateId,
        @NotNull String agentProcessId,
        @NotNull Instant failedAt
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
