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

package org.elasticsoftware.akces.aggregate;

import java.time.Instant;
import java.util.Map;

/**
 * An immutable record representing a task that has been assigned to an
 * {@link AgenticAggregate} for AI-assisted processing.
 *
 * <p>Instances are created by the built-in event-sourcing handler when an
 * {@code AgentTaskAssignedEvent} is applied, and are stored in aggregate states
 * that implement {@link TaskAwareState}.
 *
 * @param agentProcessId  the Embabel {@code AgentProcess} identifier created for this task
 * @param taskDescription a human-readable description of what the agent should do
 * @param requestingParty the entity (agent or human) that requested this task
 * @param taskMetadata    optional key-value metadata for additional context
 *                        (e.g. correlationId, priority, deadline)
 * @param assignedAt      the instant at which the task was assigned
 */
public record AssignedTask(
        String agentProcessId,
        String taskDescription,
        RequestingParty requestingParty,
        Map<String, String> taskMetadata,
        Instant assignedAt
) {
}
