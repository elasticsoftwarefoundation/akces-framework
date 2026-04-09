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

import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Extension of {@link Aggregate} that provides memory-awareness and tool-based memory
 * management for agentic aggregates.
 *
 * <p>This interface adds:
 * <ul>
 *   <li>{@link #getMemories(AggregateState)} — extract memories from state</li>
 *   <li>{@link #storeMemory} — {@link Tool @Tool} method for LLM-driven memory storage</li>
 *   <li>{@link #forgetMemory} — {@link Tool @Tool} method for LLM-driven memory revocation</li>
 * </ul>
 *
 * <p>The {@code @Tool}-annotated default methods are exposed to the LLM during the
 * learning goal's {@code context.ai().withToolObject(aggregate)} call, allowing the LLM
 * to invoke them as function-call tools during memory distillation. The returned events
 * are collected from the {@code MemoryLearningResult} and applied through the standard
 * event-sourcing flow.
 *
 * @param <S> the aggregate state type
 * @see MemoryStoredEvent
 * @see MemoryRevokedEvent
 * @see AgenticAggregateMemory
 */
public interface AgenticAggregate<S extends AggregateState> extends Aggregate<S> {
    /**
     * Returns the memories held by the given state.
     * <p>
     * If {@code state} implements {@link MemoryAwareState} the list returned by
     * {@link MemoryAwareState#getMemories()} is returned; otherwise an empty list is returned.
     *
     * @param state the current aggregate state
     * @return the state's memories, or an empty list when the state does not implement
     *         {@link MemoryAwareState}
     */
    default List<AgenticAggregateMemory> getMemories(S state) {
        if (state instanceof MemoryAwareState memoryState) {
            return memoryState.getMemories();
        }
        return List.of();
    }

    /**
     * Stores a learned fact as a new memory entry for this agentic aggregate.
     *
     * <p>This method is annotated with {@link Tool @Tool} and is exposed to the LLM
     * when the aggregate instance is passed via {@code withToolObject()}. The LLM calls
     * this tool during the learning process to persist new memories.
     *
     * @param agenticAggregateId the unique identifier of the agentic aggregate instance
     * @param subject            a short (1–3 word) topic label for the memory
     * @param fact               a clear, concise factual statement (max ~200 characters)
     * @param citations          source reference — the command/event type that triggered
     *                           this learning, or relevant data points
     * @param reason             why this fact is worth remembering — what future decisions
     *                           it informs (2–3 sentences)
     * @return a {@link MemoryStoredEvent} containing all memory metadata
     */
    @Tool(description = "Store a learned fact as a memory entry for the agentic aggregate")
    default MemoryStoredEvent storeMemory(
            @ToolParam(description = "The unique identifier of the agentic aggregate instance")
            String agenticAggregateId,
            @ToolParam(description = "A short 1-3 word topic label for the memory")
            String subject,
            @ToolParam(description = "A clear, concise factual statement (max ~200 characters)")
            String fact,
            @ToolParam(description = "Source reference - the command/event type that triggered this learning")
            String citations,
            @ToolParam(description = "Why this fact is worth remembering - 2-3 sentences explaining significance")
            String reason) {
        return new MemoryStoredEvent(
                agenticAggregateId,
                UUID.randomUUID().toString(),
                subject,
                fact,
                citations,
                reason,
                Instant.now());
    }

    /**
     * Revokes (forgets) a memory entry that is no longer relevant or accurate.
     *
     * <p>This method is annotated with {@link Tool @Tool} and is exposed to the LLM
     * when the aggregate instance is passed via {@code withToolObject()}. The LLM calls
     * this tool to remove outdated or incorrect memories, or to enforce capacity limits
     * by evicting the oldest entries.
     *
     * @param agenticAggregateId the unique identifier of the agentic aggregate instance
     * @param memoryId           the UUID of the memory entry to revoke
     * @param reason             the reason the memory is being revoked
     * @return a {@link MemoryRevokedEvent} for the identified memory entry
     */
    @Tool(description = "Revoke a memory entry that is no longer relevant or accurate")
    default MemoryRevokedEvent forgetMemory(
            @ToolParam(description = "The unique identifier of the agentic aggregate instance")
            String agenticAggregateId,
            @ToolParam(description = "The UUID of the memory entry to revoke")
            String memoryId,
            @ToolParam(description = "The reason the memory is being revoked")
            String reason) {
        return new MemoryRevokedEvent(
                agenticAggregateId,
                memoryId,
                reason,
                Instant.now());
    }
}
