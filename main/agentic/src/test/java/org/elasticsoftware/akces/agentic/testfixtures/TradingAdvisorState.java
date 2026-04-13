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

package org.elasticsoftware.akces.agentic.testfixtures;

import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate state for the TradingAdvisor agentic aggregate.
 *
 * <p>Implements {@link MemoryAwareState} for AI memory management and
 * {@link TaskAwareState} for tracking assigned agent tasks.
 */
@AggregateStateInfo(type = "TradingAdvisorState", version = 1)
public record TradingAdvisorState(
        String id,
        List<AgenticAggregateMemory> memories,
        List<MemoryDistillation> memoryDistillations,
        List<AssignedTask> assignedTasks,
        List<String> analyses
) implements AggregateState, MemoryAwareState, TaskAwareState {

    public TradingAdvisorState(String id) {
        this(id, List.of(), List.of(), List.of(), List.of());
    }

    @Override
    @Nonnull
    public String getAggregateId() {
        return id;
    }

    // -- MemoryAwareState --

    @Override
    public List<AgenticAggregateMemory> getMemories() {
        return memories;
    }

    @Override
    public MemoryAwareState withMemory(AgenticAggregateMemory memory) {
        var updated = new ArrayList<>(memories);
        updated.add(memory);
        return new TradingAdvisorState(id, List.copyOf(updated), memoryDistillations, assignedTasks, analyses);
    }

    @Override
    public MemoryAwareState withoutMemory(String memoryId) {
        return new TradingAdvisorState(id,
                memories.stream().filter(m -> !m.memoryId().equals(memoryId)).toList(),
                memoryDistillations, assignedTasks, analyses);
    }

    @Override
    public List<MemoryDistillation> getMemoryDistillations() {
        return memoryDistillations;
    }

    @Override
    public MemoryAwareState withMemoryDistillation(MemoryDistillation distillation) {
        var updated = new ArrayList<>(memoryDistillations);
        updated.add(distillation);
        return new TradingAdvisorState(id, memories, List.copyOf(updated), assignedTasks, analyses);
    }

    @Override
    public MemoryAwareState withoutMemoryDistillation(String agentProcessId) {
        return new TradingAdvisorState(id, memories,
                memoryDistillations.stream().filter(d -> !d.agentProcessId().equals(agentProcessId)).toList(),
                assignedTasks, analyses);
    }

    // -- TaskAwareState --

    @Override
    public List<AssignedTask> getAssignedTasks() {
        return assignedTasks;
    }

    @Override
    public TaskAwareState withAssignedTask(AssignedTask task) {
        var updated = new ArrayList<>(assignedTasks);
        updated.add(task);
        return new TradingAdvisorState(id, memories, memoryDistillations, List.copyOf(updated), analyses);
    }

    @Override
    public TaskAwareState withoutAssignedTask(String agentProcessId) {
        return new TradingAdvisorState(id, memories, memoryDistillations,
                assignedTasks.stream().filter(t -> !t.agentProcessId().equals(agentProcessId)).toList(),
                analyses);
    }

    // -- TradingAdvisor-specific --

    /**
     * Returns a new state with the given analysis appended.
     */
    public TradingAdvisorState withAnalysis(String analysis) {
        var updated = new ArrayList<>(analyses);
        updated.add(analysis);
        return new TradingAdvisorState(id, memories, memoryDistillations, assignedTasks, List.copyOf(updated));
    }
}
