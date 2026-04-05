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
}
