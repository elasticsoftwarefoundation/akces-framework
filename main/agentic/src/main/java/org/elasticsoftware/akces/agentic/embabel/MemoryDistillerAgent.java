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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

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
 * <p>The net number of new memories (stored minus revoked) is constrained by both
 * the {@code maxTotalMemories} capacity and the per-distillation budget
 * {@code maxMemoriesAdded}.
 */
@Agent(name = MemoryDistillerAgent.AGENT_NAME,
        description = "Distills relevant memories from a completed agent process",
        planner = PlannerType.GOAP)
public class MemoryDistillerAgent {

    /**
     * The well-known agent name used to locate this agent on the
     * {@link com.embabel.agent.core.AgentPlatform}.
     */
    public static final String AGENT_NAME = "MemoryDistiller";

    private final ObjectMapper objectMapper;

    /**
     * Creates a new {@code MemoryDistillerAgent}.
     *
     * @param objectMapper the Jackson {@link ObjectMapper} used for JSON serialization
     *                     of blackboard objects and memories in the LLM prompt
     */
    public MemoryDistillerAgent(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Distills memories from a completed agent process by analyzing the process
     * history and blackboard contents using an LLM.
     *
     * <p>The method reads the following bindings from the blackboard:
     * <ul>
     *   <li>{@code "history"} — the execution history from the completed process</li>
     *   <li>{@code "blackboardObjects"} — all objects from the completed process's
     *       blackboard</li>
     *   <li>{@code "existingMemories"} — the current list of memories from the aggregate
     *       state</li>
     *   <li>{@code "maxTotalMemories"} — the total memory capacity of the aggregate</li>
     *   <li>{@code "maxMemoriesAdded"} — the per-distillation budget for net new memories</li>
     * </ul>
     *
     * @param context the action context providing access to AI capabilities and blackboard
     * @return a {@link MemoryDistillationResult} with memories to store and revoke
     */
    @Action(description = "Distill relevant memories from a completed agent process")
    @AchievesGoal(description = "Distill and manage memories from completed agent processes")
    public MemoryDistillationResult distillMemories(ActionContext context) {
        var blackboard = context.getProcessContext().getAgentProcess().getBlackboard();

        Object historyBinding = blackboard.get("history");
        List<?> history = historyBinding instanceof List<?> historyList
                ? historyList
                : List.of();
        Object blackboardObjectsBinding = blackboard.get("blackboardObjects");
        List<?> blackboardObjects = blackboardObjectsBinding instanceof List<?> objectList
                ? objectList
                : List.of();
        Object existingMemoriesBinding = blackboard.get("existingMemories");
        List<?> existingMemories = existingMemoriesBinding instanceof List<?> memoryList
                ? memoryList
                : List.of();
        Object maxTotalMemoriesBinding = blackboard.get("maxTotalMemories");
        int maxTotalMemories = maxTotalMemoriesBinding instanceof Number number
                ? number.intValue()
                : 0;
        Object maxMemoriesAddedBinding = blackboard.get("maxMemoriesAdded");
        int maxMemoriesAdded = maxMemoriesAddedBinding instanceof Number number
                ? number.intValue()
                : 0;

        int currentCount = existingMemories.size();
        int capacityLeft = Math.max(0, maxTotalMemories - currentCount);
        int effectiveLimit = Math.min(capacityLeft, maxMemoriesAdded);

        String prompt = buildPrompt(history, blackboardObjects, existingMemories, effectiveLimit);

        return context.ai()
                .withDefaultLlm()
                .createObject(prompt, MemoryDistillationResult.class);
    }

    private String buildPrompt(List<?> history,
                                List<?> blackboardObjects,
                                List<?> existingMemories,
                                int maxNewMemories) {
        var sb = new StringBuilder();

        sb.append("""
                You are a memory distillation agent. Analyze the following completed agent process \
                and determine which facts should be stored as new memories and which existing \
                memories should be revoked (because they are outdated, incorrect, or superseded).

                CONSTRAINTS:
                """);

        sb.append("- The net number of new memories (stored count minus revoked count) must be <= ")
                .append(maxNewMemories).append("\n");

        sb.append("""
                - Only store memories that are actionable, likely to remain relevant, and \
                cannot always be inferred from limited context
                - Only revoke memories that are clearly outdated, incorrect, or superseded \
                by new information

                """);

        sb.append("PROCESS HISTORY (actions executed):\n");
        if (history != null && !history.isEmpty()) {
            for (Object invocation : history) {
                sb.append("- ").append(serialize(invocation)).append("\n");
            }
        } else {
            sb.append("(no actions recorded)\n");
        }

        sb.append("\nBLACKBOARD OBJECTS (process context and results):\n");
        if (blackboardObjects != null && !blackboardObjects.isEmpty()) {
            for (Object obj : blackboardObjects) {
                sb.append("- [").append(obj.getClass().getSimpleName()).append("] ")
                        .append(serialize(obj)).append("\n");
            }
        } else {
            sb.append("(no objects)\n");
        }

        sb.append("\nEXISTING MEMORIES:\n");
        if (existingMemories != null && !existingMemories.isEmpty()) {
            for (Object memory : existingMemories) {
                sb.append("- ").append(serialize(memory)).append("\n");
            }
        } else {
            sb.append("(no existing memories)\n");
        }

        return sb.toString();
    }

    /**
     * Serializes an object to JSON using the injected {@link ObjectMapper}.
     * Falls back to {@link Object#toString()} if serialization fails.
     */
    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
