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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MemoryDistillationResult}.
 */
class MemoryDistillationResultTest {

    @Test
    void shouldCreateWithStoredAndRevokedMemories() {
        var stored = List.of(
                new MemoryDistillationResult.StoredMemory("testing", "Use JUnit 5", "src/test", "best practice"),
                new MemoryDistillationResult.StoredMemory("logging", "Use SLF4J", "src/main", "consistency")
        );
        var revoked = List.of(
                new MemoryDistillationResult.RevokedMemory("mem-old-1", "superseded by new info")
        );

        var result = new MemoryDistillationResult(stored, revoked);

        assertThat(result.stored()).hasSize(2);
        assertThat(result.revoked()).hasSize(1);
        assertThat(result.stored().getFirst().subject()).isEqualTo("testing");
        assertThat(result.stored().getFirst().fact()).isEqualTo("Use JUnit 5");
        assertThat(result.revoked().getFirst().memoryId()).isEqualTo("mem-old-1");
    }

    @Test
    void shouldCreateWithEmptyLists() {
        var result = new MemoryDistillationResult(List.of(), List.of());

        assertThat(result.stored()).isEmpty();
        assertThat(result.revoked()).isEmpty();
    }

    @Test
    void storedMemoryShouldExposeAllFields() {
        var stored = new MemoryDistillationResult.StoredMemory(
                "conventions", "Use records for DTOs", "src/main/Model.java:10", "immutability");

        assertThat(stored.subject()).isEqualTo("conventions");
        assertThat(stored.fact()).isEqualTo("Use records for DTOs");
        assertThat(stored.citations()).isEqualTo("src/main/Model.java:10");
        assertThat(stored.reason()).isEqualTo("immutability");
    }

    @Test
    void revokedMemoryShouldExposeAllFields() {
        var revoked = new MemoryDistillationResult.RevokedMemory("mem-123", "outdated");

        assertThat(revoked.memoryId()).isEqualTo("mem-123");
        assertThat(revoked.reason()).isEqualTo("outdated");
    }
}
