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

package org.elasticsoftware.akces.agentic.embabel;

import java.util.List;

/**
 * Result object produced by the {@link MemoryDistillerAgent} after distilling
 * relevant memories from a completed {@link com.embabel.agent.core.AgentProcess}.
 *
 * <p>Contains two lists:
 * <ul>
 *   <li>{@code stored} — new memory entries to be persisted as
 *       {@link org.elasticsoftware.akces.agentic.events.MemoryStoredEvent}s</li>
 *   <li>{@code revoked} — existing memory entries to be removed as
 *       {@link org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent}s</li>
 * </ul>
 *
 * @param stored  the list of memories to store; each entry contains subject, fact,
 *                citations, and reason fields
 * @param revoked the list of memories to revoke; each entry contains memoryId and reason
 */
public record MemoryDistillationResult(
        List<StoredMemory> stored,
        List<RevokedMemory> revoked
) {

    /**
     * A memory entry to be stored.
     *
     * @param subject   a short (1–2 word) topic label
     * @param fact      the fact to remember (max 200 characters)
     * @param citations the source citation for the fact
     * @param reason    why this memory should be stored
     */
    public record StoredMemory(
            String subject,
            String fact,
            String citations,
            String reason
    ) {
    }

    /**
     * A memory entry to be revoked.
     *
     * @param memoryId the UUID of the memory to revoke
     * @param reason   why this memory should be revoked
     */
    public record RevokedMemory(
            String memoryId,
            String reason
    ) {
    }
}
