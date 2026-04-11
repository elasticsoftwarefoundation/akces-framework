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

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.ActionInvocation;

import java.util.List;

/**
 * Embabel agent responsible for distilling relevant memories from a successfully
 * completed {@link com.embabel.agent.core.AgentProcess}.
 *
 * <p>When an agent process finishes with status {@code COMPLETED}, this agent is
 * invoked with the process's execution history and all objects from its blackboard.
 * It uses an LLM to analyze the process results and determine which memories should
 * be stored and which existing memories should be revoked.
 *
 * <p>The agent produces a {@link MemoryDistillationResult} containing:
 * <ul>
 *   <li>A list of new memories to store</li>
 *   <li>A list of existing memories to revoke</li>
 * </ul>
 *
 * <p>The net number of new memories (stored minus revoked) is constrained by the
 * {@code maxNewMemories} binding on the blackboard, which is derived from the
 * aggregate's {@code maxMemories} configuration minus the current memory count.
 */
@Agent(name = MemoryDistillerAgent.AGENT_NAME,
        description = "Distills relevant memories from a completed agent process",
        planner = PlannerType.UTILITY)
public class MemoryDistillerAgent {

    /**
     * The well-known agent name used to locate this agent on the
     * {@link com.embabel.agent.core.AgentPlatform}.
     */
    public static final String AGENT_NAME = "MemoryDistillerAgent";

    /**
     * Distills memories from a completed agent process by analyzing the process
     * history and blackboard contents using an LLM.
     *
     * <p>The method reads the following bindings from the blackboard:
     * <ul>
     *   <li>{@code "history"} — the {@link List} of {@link ActionInvocation}s from the
     *       completed process</li>
     *   <li>{@code "blackboardObjects"} — all objects from the completed process's
     *       blackboard</li>
     *   <li>{@code "existingMemories"} — the current list of memories from the aggregate
     *       state</li>
     *   <li>{@code "maxNewMemories"} — the maximum number of net new memories allowed
     *       (maxMemories - currentMemoryCount)</li>
     * </ul>
     *
     * @param context the action context providing access to AI capabilities and blackboard
     * @return a {@link MemoryDistillationResult} with memories to store and revoke
     */
    @Action(description = "Distill relevant memories from a completed agent process")
    @AchievesGoal(description = "Distill and manage memories from completed agent processes")
    public MemoryDistillationResult distillMemories(ActionContext context) {
        var blackboard = context.getProcessContext().getAgentProcess().getBlackboard();
        List<?> history = blackboard.objectsOfType(ActionInvocation.class);
        List<?> blackboardObjects = (List<?>) blackboard.get("blackboardObjects");
        List<?> existingMemories = (List<?>) blackboard.get("existingMemories");
        int maxNewMemories = (int) blackboard.get("maxNewMemories");

        String prompt = buildPrompt(history, blackboardObjects, existingMemories, maxNewMemories);

        return context.ai()
                .withDefaultLlm()
                .createObject(prompt, MemoryDistillationResult.class);
    }

    private String buildPrompt(List<?> history,
                                List<?> blackboardObjects,
                                List<?> existingMemories,
                                int maxNewMemories) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a memory distillation agent. Analyze the following completed agent process ");
        sb.append("and determine which facts should be stored as new memories and which existing ");
        sb.append("memories should be revoked (because they are outdated, incorrect, or superseded).\n\n");

        sb.append("CONSTRAINTS:\n");
        sb.append("- The net number of new memories (stored count minus revoked count) must be <= ")
                .append(maxNewMemories).append("\n");
        sb.append("- Each stored memory must have: subject (1-2 words), fact (max 200 chars), ");
        sb.append("citations (source reference), and reason (why it should be stored)\n");
        sb.append("- Each revoked memory must have: memoryId (from existing memories) and reason\n");
        sb.append("- Only store memories that are actionable, likely to remain relevant, and ");
        sb.append("cannot always be inferred from limited context\n");
        sb.append("- Only revoke memories that are clearly outdated, incorrect, or superseded ");
        sb.append("by new information\n\n");

        sb.append("PROCESS HISTORY (actions executed):\n");
        if (history != null && !history.isEmpty()) {
            for (Object invocation : history) {
                sb.append("- ").append(invocation).append("\n");
            }
        } else {
            sb.append("(no actions recorded)\n");
        }

        sb.append("\nBLACKBOARD OBJECTS (process context and results):\n");
        if (blackboardObjects != null && !blackboardObjects.isEmpty()) {
            for (Object obj : blackboardObjects) {
                sb.append("- [").append(obj.getClass().getSimpleName()).append("] ")
                        .append(obj).append("\n");
            }
        } else {
            sb.append("(no objects)\n");
        }

        sb.append("\nEXISTING MEMORIES:\n");
        if (existingMemories != null && !existingMemories.isEmpty()) {
            for (Object memory : existingMemories) {
                sb.append("- ").append(memory).append("\n");
            }
        } else {
            sb.append("(no existing memories)\n");
        }

        sb.append("\nReturn a JSON object with 'stored' (list of new memories to store) ");
        sb.append("and 'revoked' (list of existing memories to revoke). ");
        sb.append("If no memories should be stored or revoked, return empty lists.");

        return sb.toString();
    }
}
