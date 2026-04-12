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

/**
 * Common interface for state-tracked items that are associated with an Embabel
 * {@link com.embabel.agent.core.AgentProcess}.
 *
 * <p>Both {@link AssignedTask} (representing a running agent task) and
 * {@link MemoryDistillation} (representing an in-progress memory distillation process)
 * implement this interface, allowing the tick logic to operate uniformly on any
 * agent-process-backed work item.
 *
 * @see AssignedTask
 * @see MemoryDistillation
 */
public interface AgentProcessAware {

    /**
     * Returns the Embabel {@code AgentProcess} identifier associated with this item.
     *
     * @return the agent process ID; never {@code null}
     */
    String getAgentProcessId();
}
