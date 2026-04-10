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
import org.elasticsoftware.akces.aggregate.RequestingParty;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.time.Instant;
import java.util.Map;

/**
 * Domain event emitted when a task has been successfully assigned to an
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate} and an Embabel
 * {@code AgentProcess} has been created.
 *
 * <p>This event is produced by the built-in command handler when processing an
 * {@link org.elasticsoftware.akces.agentic.commands.AssignTaskCommand}. It contains
 * the Embabel {@code AgentProcess} identifier, which links the Akces domain to the
 * Embabel runtime.
 *
 * <p>The built-in event-sourcing handler processes this event by updating the aggregate
 * state's {@link org.elasticsoftware.akces.aggregate.TaskAwareState} with a new
 * {@link org.elasticsoftware.akces.aggregate.AssignedTask}.
 *
 * @param agenticAggregateId the identifier of the agentic aggregate
 * @param agentProcessId     the Embabel {@code AgentProcess.getId()} value
 * @param taskDescription    the task description from the originating command
 * @param requestingParty    the entity that requested the task assignment
 * @param taskMetadata       optional key-value metadata from the originating command;
 *                           may be {@code null}
 * @param assignedAt         the instant at which the task was assigned
 */
@DomainEventInfo(type = "AgentTaskAssigned", version = 1,
        description = "Emitted when a task has been assigned to an agentic aggregate and an AgentProcess has been created")
public record AgentTaskAssignedEvent(
        @AggregateIdentifier String agenticAggregateId,
        @NotNull String agentProcessId,
        @NotNull String taskDescription,
        @NotNull RequestingParty requestingParty,
        Map<String, String> taskMetadata,
        @NotNull Instant assignedAt
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
