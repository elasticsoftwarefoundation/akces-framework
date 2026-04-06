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

import java.util.List;

/**
 * Implemented by aggregate states that maintain a list of {@link AgenticAggregateMemory} entries.
 *
 * <p>State classes (typically Java records) must implement all three methods:
 * <ul>
 *   <li>{@link #getMemories()} — return the current list of memories</li>
 *   <li>{@link #withMemory(AgenticAggregateMemory)} — return a new state instance with the given memory appended</li>
 *   <li>{@link #withoutMemory(String)} — return a new state instance with the memory identified by {@code memoryId} removed</li>
 * </ul>
 */
public interface MemoryAwareState {

    /**
     * Returns the current list of memories stored in this state.
     *
     * <p>Implementations should return an unmodifiable list.
     *
     * @return a list of memories; never {@code null}
     */
    List<AgenticAggregateMemory> getMemories();

    /**
     * Returns a new state instance that includes the given memory appended to the end of
     * the current memories list.
     *
     * @param memory the memory entry to add; must not be {@code null}
     * @return a new state instance with the memory added
     */
    MemoryAwareState withMemory(AgenticAggregateMemory memory);

    /**
     * Returns a new state instance that excludes the memory entry identified by
     * {@code memoryId}.  If no such memory exists, the returned state is equal to
     * {@code this}.
     *
     * @param memoryId the unique identifier of the memory entry to remove; must not be {@code null}
     * @return a new state instance with the matching memory removed
     */
    MemoryAwareState withoutMemory(String memoryId);
}
