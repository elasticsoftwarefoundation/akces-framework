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

package org.elasticsoftware.akces.agentic.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embabel action that analyzes the current aggregate state using LLM reasoning.
 *
 * <p>This is a <strong>framework building block</strong> — a reusable, read-only action that
 * serializes the current aggregate state into a structured JSON representation suitable for
 * LLM consumption. The serialized state is returned as a {@link String} on the blackboard,
 * making it available for subsequent LLM reasoning steps, MCP tool calls, or other actions
 * within the agent's plan.
 *
 * <p>The action does not invoke the LLM directly; instead, it prepares the state in a format
 * that the Embabel GOAP planner can feed into LLM reasoning steps. This separation of
 * concerns allows the planner to compose state analysis with other actions (e.g.,
 * {@code RecallMemoriesAction}) as part of a larger goal plan.
 *
 * <p><strong>Blackboard inputs:</strong>
 * <ul>
 *   <li>{@link AggregateState} — the current aggregate state, populated by the handler adapter</li>
 * </ul>
 *
 * <p><strong>Blackboard output:</strong>
 * <ul>
 *   <li>{@link String} — a JSON representation of the aggregate state for LLM analysis</li>
 * </ul>
 *
 * @see ProcessCommandGoal
 * @see ReactToExternalEventGoal
 */
@EmbabelComponent
public class AnalyzeAggregateStateAction {

    private static final Logger logger = LoggerFactory.getLogger(AnalyzeAggregateStateAction.class);

    private final ObjectMapper objectMapper;

    /**
     * Creates a new {@code AnalyzeAggregateStateAction}.
     *
     * @param objectMapper the Jackson {@link ObjectMapper} used to serialize aggregate state;
     *                     provided by Spring auto-configuration
     */
    public AnalyzeAggregateStateAction(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serializes the current aggregate state to a structured JSON representation for
     * LLM reasoning.
     *
     * <p>The aggregate state is resolved from the blackboard by type. The resulting JSON
     * string is placed back on the blackboard and can be consumed by LLM reasoning steps
     * or other actions within the agent's plan.
     *
     * <p>If serialization fails, a fallback representation using {@link Object#toString()}
     * is used to ensure the action does not fail the entire agent process.
     *
     * @param state the current aggregate state; resolved from the blackboard by type
     * @return a JSON string representation of the aggregate state
     */
    @Action(description = "Analyze the current aggregate state using LLM reasoning", readOnly = true)
    public String analyzeState(AggregateState state) {
        logger.debug("Analyzing aggregate state of type {}", state.getClass().getSimpleName());
        try {
            String serialized = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            logger.debug("Aggregate state serialized successfully ({} chars)", serialized.length());
            return serialized;
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize aggregate state of type {}, falling back to toString()",
                    state.getClass().getSimpleName(), e);
            return state.toString();
        }
    }
}
