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

/**
 * An immutable record representing an in-progress memory distillation process
 * for an {@link AgenticAggregate}.
 *
 * <p>Instances are created when a {@code MemoryDistillationStartedEvent} is applied
 * and removed when the corresponding {@code MemoryDistillationFinishedEvent} is applied.
 * The {@link #agentProcessId()} links this record to the Embabel {@code AgentProcess}
 * that is running the {@link com.embabel.agent.core.Agent MemoryDistillerAgent}.
 *
 * <p>This record implements {@link AgentProcessAware} so that the tick logic can
 * uniformly operate on both {@link AssignedTask}s and memory distillation processes.
 *
 * @param agentProcessId the Embabel {@code AgentProcess} identifier for the distillation process
 * @param startedAt      the instant at which the distillation was started
 */
public record MemoryDistillation(
        String agentProcessId,
        Instant startedAt
) implements AgentProcessAware {

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAgentProcessId() {
        return agentProcessId;
    }
}
