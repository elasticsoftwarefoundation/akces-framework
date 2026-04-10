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

import com.embabel.agent.core.AgentPlatform;
import org.elasticsoftware.akces.agentic.commands.AssignTaskCommand;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.elasticsoftware.akces.aggregate.AgenticAggregateMemory;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.aggregate.MemoryAwareState;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;

import java.io.IOException;
import java.util.List;

/**
 * Extended runtime interface for {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}s.
 *
 * <p>Extends {@link AggregateRuntime} with memory-specific and agent-platform operations.
 * Analogous to how {@link org.elasticsoftware.akces.aggregate.AgenticAggregate} extends
 * {@link org.elasticsoftware.akces.aggregate.Aggregate} to add memory awareness.
 *
 * <p>Key additions over the base interface:
 * <ul>
 *   <li>{@link #getMemories(AggregateStateRecord)} — derives current memory state directly
 *       from a loaded state record, avoiding a separate in-memory deque that would need to
 *       be rebuilt from the event log after restarts.</li>
 *   <li>{@link #getAgentPlatform()} — exposes the Embabel {@link AgentPlatform} that the
 *       agentic handler adapters use to create and run {@code AgentProcess} instances during
 *       command and event processing.</li>
 * </ul>
 */
public interface AgenticAggregateRuntime extends AggregateRuntime {

    /** Built-in domain event type for {@link MemoryStoredEvent}. */
    DomainEventType<MemoryStoredEvent> MEMORY_STORED_TYPE = new DomainEventType<>(
            "MemoryStored", 1, MemoryStoredEvent.class, false, false, false, false);

    /** Built-in domain event type for {@link MemoryRevokedEvent}. */
    DomainEventType<MemoryRevokedEvent> MEMORY_REVOKED_TYPE = new DomainEventType<>(
            "MemoryRevoked", 1, MemoryRevokedEvent.class, false, false, false, false);

    /** Built-in command type for {@link AssignTaskCommand}. */
    CommandType<AssignTaskCommand> ASSIGN_TASK_COMMAND_TYPE = new CommandType<>(
            "AssignTask", 1, AssignTaskCommand.class, false, false, false);

    /** Built-in domain event type for {@link AgentTaskAssignedEvent}. */
    DomainEventType<AgentTaskAssignedEvent> AGENT_TASK_ASSIGNED_TYPE = new DomainEventType<>(
            "AgentTaskAssigned", 1, AgentTaskAssignedEvent.class, false, false, false, false);

    /**
     * Returns the Embabel {@link AgentPlatform} used to create and run agent processes.
     *
     * <p>This platform is the entry point for GOAP-based planning, LLM reasoning, and tool use
     * during agent-handled command and event processing.
     *
     * @return the {@link AgentPlatform}; never {@code null}
     */
    AgentPlatform getAgentPlatform();

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
