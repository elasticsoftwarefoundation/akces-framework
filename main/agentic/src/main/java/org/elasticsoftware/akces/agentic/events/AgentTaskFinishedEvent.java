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

import com.embabel.agent.core.AgentProcessStatusCode;
import jakarta.annotation.Nonnull;
import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.time.Instant;

/**
 * Domain event emitted when an {@link com.embabel.agent.core.AgentProcess} has finished
 * executing, regardless of the reason (completed, failed, terminated, killed, or stuck).
 *
 * <p>This event is produced by
 * {@link org.elasticsoftware.akces.agentic.runtime.KafkaAgenticAggregateRuntime#resumeNextAgentTask}
 * after each tick when the agent process reports {@code getFinished() == true}.
 *
 * <p>The built-in event-sourcing handler processes this event by removing the corresponding
 * {@link org.elasticsoftware.akces.aggregate.AssignedTask} from the aggregate state's
 * {@link org.elasticsoftware.akces.aggregate.TaskAwareState}.
 *
 * @param agenticAggregateId the identifier of the agentic aggregate
 * @param agentProcessId     the Embabel {@code AgentProcess.getId()} value
 * @param status             the {@link AgentProcessStatusCode} indicating why the process finished
 * @param finishedAt         the instant at which the process was detected as finished
 */
@DomainEventInfo(type = "AgentTaskFinished", version = 1,
        description = "Emitted when an AgentProcess has finished executing")
public record AgentTaskFinishedEvent(
        @AggregateIdentifier String agenticAggregateId,
        @NotNull String agentProcessId,
        @NotNull AgentProcessStatusCode status,
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
