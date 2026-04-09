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

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.common.OperationContext;
import org.elasticsoftware.akces.aggregate.AgenticAggregate;
import org.elasticsoftware.akces.aggregate.AgenticAggregateMemory;

import java.util.List;

/**
 * Central Embabel component that provides reusable Goals, Actions, and Conditions for
 * Akces-based agentic systems.
 *
 * <p>This class is annotated with {@link EmbabelComponent} and is automatically discovered
 * by the {@code AgentPlatform} via component scanning. It provides the foundational agent
 * capabilities for memory retrieval, condition evaluation, and LLM-powered memory
 * distillation that all agentic aggregates share.
 *
 * <p>Memory storage and revocation are handled by {@code @Tool}-annotated default methods
 * on the {@link AgenticAggregate} interface, which are exposed to the LLM via
 * {@code withToolObject()} during the learning goal.
 *
 * <h2>Actions</h2>
 * <ul>
 *   <li>{@link #recallMemories recallMemories} — Search stored memories by subject or
 *       keyword (read-only)</li>
 * </ul>
 *
 * <h2>Conditions</h2>
 * <ul>
 *   <li>{@link #hasMemories hasMemories} — Evaluates whether any memories are present on
 *       the blackboard</li>
 * </ul>
 *
 * <h2>Goals</h2>
 * <ul>
 *   <li>{@link #learnFromProcess learnFromProcess} — Multi-step LLM reasoning goal that
 *       analyzes session context and distills useful memories via
 *       {@link OperationContext#ai()} with the aggregate as a tool object</li>
 * </ul>
 *
 * @see AgenticAggregate#storeMemory
 * @see AgenticAggregate#forgetMemory
 * @see AgenticAggregateMemory
 * @see MemoryLearningResult
 */
@EmbabelComponent
public class AkcesAgentComponent {

    /**
     * Maximum number of new memories that may be stored in a single agent process
     * execution. This prevents memory bloat from a single interaction.
     */
    static final int MAX_NEW_MEMORIES_PER_EXECUTION = 3;

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    /**
     * Searches stored memories by subject or keyword to retrieve relevant facts.
     *
     * <p>Reads the current {@code List<AgenticAggregateMemory>} from the blackboard and
     * filters entries based on the provided {@code query}. Matching is case-insensitive
     * and checks the memory's subject, fact content, and reason fields. If no query is
     * provided (null or blank), all memories are returned.
     *
     * <p>This action is declared {@code readOnly = true} since it has no side effects —
     * it only inspects the current memory list without modifying state.
     *
     * @param memories the current list of memories from the blackboard; may be
     *                 {@code null} or empty
     * @param query    a search term to filter memories by subject, fact, or reason;
     *                 if {@code null} or blank, all memories are returned
     * @return an unmodifiable list of matching memories; never {@code null}
     */
    @Action(description = "Search stored memories by subject or keyword to retrieve relevant facts",
            readOnly = true)
    public List<AgenticAggregateMemory> recallMemories(
            List<AgenticAggregateMemory> memories,
            String query) {
        if (memories == null || memories.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return List.copyOf(memories);
        }
        return memories.stream()
                .filter(m -> matchesQuery(m, query))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Conditions
    // -------------------------------------------------------------------------

    /**
     * Evaluates whether the blackboard contains one or more
     * {@link AgenticAggregateMemory} objects.
     *
     * <p>This condition is used as a precondition for actions that depend on prior
     * knowledge. For example, "recall memories" is only meaningful when memories exist.
     *
     * @param memories the current list of memories from the blackboard; may be
     *                 {@code null} or empty
     * @return {@code true} if at least one memory is present; {@code false} otherwise
     */
    @Condition(name = "hasMemories")
    public boolean hasMemories(List<AgenticAggregateMemory> memories) {
        return memories != null && !memories.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Goals
    // -------------------------------------------------------------------------

    /**
     * Goal action that completes the learning cycle by analyzing current session
     * information and distilling useful memories using LLM reasoning.
     *
     * <p>This method is annotated with both {@link AchievesGoal} and {@link Action}.
     * The {@code @AchievesGoal} annotation declares the "LearnFromProcess" goal in the
     * Embabel GOAP planner. The method uses {@link OperationContext#ai()} to invoke LLM
     * reasoning, passing the {@link AgenticAggregate} instance as a tool object so the
     * LLM can call the {@code @Tool}-annotated {@code storeMemory()} and
     * {@code forgetMemory()} methods during reasoning.
     *
     * <p>The LLM produces a {@link MemoryLearningResult} containing:
     * <ul>
     *   <li>{@code memoriesStored} — list of {@code MemoryStoredEvent}s created by calling
     *       {@link AgenticAggregate#storeMemory}</li>
     *   <li>{@code memoriesRevoked} — list of {@code MemoryRevokedEvent}s created by calling
     *       {@link AgenticAggregate#forgetMemory}</li>
     *   <li>{@code summary} — human-readable summary of the learning process</li>
     * </ul>
     *
     * <p><b>Constraints enforced by the LLM:</b>
     * <ul>
     *   <li>Maximum {@value #MAX_NEW_MEMORIES_PER_EXECUTION} new memories per agent
     *       process execution</li>
     *   <li>No duplicate memories — existing memories must be checked before storing</li>
     *   <li>Memory capacity enforcement — after storing, if total exceeds
     *       {@code maxMemories}, evict oldest via {@code forgetMemory}</li>
     * </ul>
     *
     * @param memories  the current list of memories from the blackboard
     * @param aggregate the {@link AgenticAggregate} instance, passed as a tool object
     *                  to expose {@code storeMemory()} and {@code forgetMemory()} to the LLM
     * @param context   the Embabel {@link OperationContext} providing access to LLM
     *                  capabilities via {@link OperationContext#ai()}
     * @return a {@link MemoryLearningResult} containing the events produced and a summary
     */
    @AchievesGoal(description = "Learned from current session and stored and/or removed memories")
    @Action(description = "Analyze current session and distill useful memories using LLM reasoning")
    public MemoryLearningResult learnFromProcess(
            List<AgenticAggregateMemory> memories,
            AgenticAggregate<?> aggregate,
            OperationContext context) {
        int currentCount = (memories != null) ? memories.size() : 0;

        String prompt = """
                Analyze the current session information available on the blackboard and \
                distill useful memories. Use the storeMemory and forgetMemory tools to \
                create the appropriate events.
                
                Current memories: %d
                
                Constraints:
                - Maximum %d new memories per agent process execution (prevents memory bloat)
                - No duplicate memories: check existing memories before storing and skip any \
                fact that is already stored (same subject + substantially similar fact content)
                - Memory capacity enforcement: after storing new memories, if total memory count \
                exceeds maxMemories, evict oldest entries using forgetMemory until within limit
                
                Memory field guidance:
                - subject: 1-3 word topic label (e.g. "error handling", "user preferences")
                - fact: clear, concise factual statement (max ~200 chars), actionable and self-contained
                - citations: source reference — the command/event type that triggered this learning
                - reason: why this fact is worth remembering, 2-3 sentences explaining significance
                
                Return a MemoryLearningResult with:
                - memoriesStored: list of MemoryStoredEvent objects from storeMemory tool calls
                - memoriesRevoked: list of MemoryRevokedEvent objects from forgetMemory tool calls
                - summary: human-readable summary of the learning process""".formatted(
                currentCount, MAX_NEW_MEMORIES_PER_EXECUTION);

        return context.ai()
                .withDefaultLlm()
                .withToolObject(aggregate)
                .createObject(prompt, MemoryLearningResult.class);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Checks whether a memory entry matches a search query.
     *
     * <p>The match is case-insensitive (locale-independent) and checks the memory's
     * {@code subject}, {@code fact}, and {@code reason} fields.
     *
     * @param memory the memory entry to check
     * @param query  the search query
     * @return {@code true} if any of the checked fields contain the query
     */
    private static boolean matchesQuery(AgenticAggregateMemory memory, String query) {
        return containsIgnoreCase(memory.subject(), query)
                || containsIgnoreCase(memory.fact(), query)
                || containsIgnoreCase(memory.reason(), query);
    }

    /**
     * Null-safe, locale-independent, case-insensitive substring check using
     * {@link String#regionMatches(boolean, int, String, int, int)}.
     *
     * @param text  the text to search within; may be {@code null}
     * @param query the search term; must not be {@code null} or empty
     * @return {@code true} if {@code text} contains {@code query} (case-insensitive)
     */
    private static boolean containsIgnoreCase(String text, String query) {
        if (text == null || query == null || query.isEmpty()) {
            return false;
        }
        int searchLength = query.length();
        int maxStart = text.length() - searchLength;
        for (int i = 0; i <= maxStart; i++) {
            if (text.regionMatches(true, i, query, 0, searchLength)) {
                return true;
            }
        }
        return false;
    }
}
