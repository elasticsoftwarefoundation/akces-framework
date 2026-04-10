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
 * State interface for agentic aggregates that track assigned tasks.
 *
 * <p>Mirrors the {@link MemoryAwareState} pattern: the framework's built-in event-sourcing
 * handler for {@code AgentTaskAssignedEvent} checks whether the aggregate state implements
 * this interface, and if so, calls {@link #withAssignedTask(AssignedTask)} to update the
 * state. If the state does not implement this interface, an {@link IllegalStateException}
 * is thrown.
 *
 * <p>States may implement both {@link MemoryAwareState} and {@code TaskAwareState} when
 * an agentic aggregate needs to track both memories and assigned tasks.
 *
 * <p>All mutation methods return new instances — implementations must be immutable.
 */
public interface TaskAwareState {

    /**
     * Returns the list of currently assigned tasks.
     *
     * @return an unmodifiable list of assigned tasks; never {@code null}
     */
    List<AssignedTask> getAssignedTasks();

    /**
     * Returns a new state instance with the given task appended to the assigned tasks list.
     *
     * @param task the task to add; must not be {@code null}
     * @return a new state instance with the task added
     */
    TaskAwareState withAssignedTask(AssignedTask task);

    /**
     * Returns a new state instance with the task identified by the given
     * {@code agentProcessId} removed from the assigned tasks list.
     *
     * @param agentProcessId the Embabel AgentProcess ID of the task to remove
     * @return a new state instance with the matching task removed
     */
    TaskAwareState withoutAssignedTask(String agentProcessId);
}
