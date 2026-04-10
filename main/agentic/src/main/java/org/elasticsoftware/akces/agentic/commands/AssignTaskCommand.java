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
import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.RequestingParty;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import java.util.Map;

/**
 * Built-in framework command that assigns a task to an
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate} for AI-assisted processing.
 *
 * <p>When processed by the built-in command handler, this command triggers the creation
 * of an Embabel {@code AgentProcess} and emits an
 * {@link org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent} containing the
 * process identifier.
 *
 * <p>This is the first framework-owned {@link Command} implementation. Unlike
 * application-defined commands, it is automatically registered for every
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}.
 *
 * @param agenticAggregateId the identifier of the target agentic aggregate
 * @param taskDescription    a human-readable description of what the agent should do
 * @param requestingParty    the entity (agent or human) requesting this task
 * @param taskMetadata       optional key-value metadata for additional context
 *                           (e.g. correlationId, priority, deadline); may be {@code null}
 */
@CommandInfo(type = "AssignTask", version = 1,
        description = "Assigns a task to an agentic aggregate for AI-assisted processing")
public record AssignTaskCommand(
        @AggregateIdentifier @NotNull String agenticAggregateId,
        @NotNull String taskDescription,
        @NotNull RequestingParty requestingParty,
        Map<String, String> taskMetadata
) implements Command {

    /**
     * {@inheritDoc}
     */
    @Override
    @Nonnull
    public String getAggregateId() {
        return agenticAggregateId;
    }
}
