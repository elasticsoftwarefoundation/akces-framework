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

package org.elasticsoftware.akces.agentic.embabel;

import com.embabel.agent.core.ActionInvocation;
import org.elasticsoftware.akces.aggregate.AgenticAggregateMemory;
import org.elasticsoftware.akces.aggregate.AssignedTask;

import java.util.List;

/**
 * Strongly-typed input for the {@link MemoryDistillerAgent} action method.
 *
 * <p>This record is placed on the Embabel Blackboard by the Akces runtime
 * and injected into the agent's action method by the Embabel framework
 * via type-based parameter resolution.
 *
 * @param agentTask         the assigned task that triggered the completed process
 * @param history           the execution history (action invocations) from the completed process
 * @param blackboardObjects all objects from the completed process's blackboard
 * @param existingMemories  the current list of memories from the aggregate state
 * @param maxTotalMemories  the total memory capacity of the aggregate
 * @param maxMemoriesAdded  the per-distillation budget for net new memories
 */
public record MemoryDistillationInput(
        AssignedTask agentTask,
        List<ActionInvocation> history,
        List<Object> blackboardObjects,
        List<AgenticAggregateMemory> existingMemories,
        int maxTotalMemories,
        int maxMemoriesAdded
) {
}
