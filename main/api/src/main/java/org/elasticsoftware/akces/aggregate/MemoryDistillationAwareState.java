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

import jakarta.annotation.Nullable;

/**
 * State interface for agentic aggregates that track in-progress memory distillation processes.
 *
 * <p>When an agent task finishes successfully, the framework starts a memory distillation
 * process. This process is tracked in the aggregate state so that it can be resumed after
 * a crash or restart, using the same tick-based processing model as regular agent tasks.
 *
 * <p>The framework's built-in event-sourcing handler for {@code MemoryDistillationStartedEvent}
 * calls {@link #withMemoryDistillation(MemoryDistillation)} to record the in-progress
 * distillation, and the handler for {@code MemoryDistillationFinishedEvent} calls
 * {@link #withoutMemoryDistillation()} to clear it.
 *
 * <p>All mutation methods return new instances — implementations must be immutable.
 */
public interface MemoryDistillationAwareState {

    /**
     * Returns the currently active memory distillation process, or {@code null} if
     * no distillation is in progress.
     *
     * @return the active distillation, or {@code null}
     */
    @Nullable
    MemoryDistillation getMemoryDistillation();

    /**
     * Returns a new state instance with the given distillation recorded as active.
     *
     * @param distillation the memory distillation to track; must not be {@code null}
     * @return a new state instance with the distillation set
     */
    MemoryDistillationAwareState withMemoryDistillation(MemoryDistillation distillation);

    /**
     * Returns a new state instance with the active memory distillation cleared.
     *
     * @return a new state instance with no active distillation
     */
    MemoryDistillationAwareState withoutMemoryDistillation();
}
