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

package org.elasticsoftware.akces.agentic;

import org.elasticsoftware.akces.aggregate.AgenticAggregateMemory;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.aggregate.MemoryAwareState;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;

import java.io.IOException;
import java.util.List;

/**
 * Extended runtime interface for {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}s.
 *
 * <p>Extends {@link AggregateRuntime} with memory-specific operations. Analogous to how
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate} extends
 * {@link org.elasticsoftware.akces.aggregate.Aggregate} to add memory awareness.
 *
 * <p>The key addition is {@link #getMemories(AggregateStateRecord)}, which allows the partition
 * to derive current memory state directly from a loaded state record — avoiding a separate
 * in-memory deque that must be rebuilt from the event log after restarts.
 */
public interface AgenticAggregateRuntime extends AggregateRuntime {

    /**
     * Returns the memories from the given aggregate state record.
     *
     * <p>Deserializes the state payload and returns the memory list when the state implements
     * {@link MemoryAwareState}; otherwise returns an empty list.
     *
     * @param stateRecord the state record to inspect (may be {@code null})
     * @return the list of memories, or an empty list when the state is {@code null} or does not
     *         implement {@link MemoryAwareState}
     * @throws IOException if deserialization fails
     */
    List<AgenticAggregateMemory> getMemories(AggregateStateRecord stateRecord) throws IOException;
}
