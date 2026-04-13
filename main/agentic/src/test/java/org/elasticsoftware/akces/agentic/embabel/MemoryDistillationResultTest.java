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

import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MemoryDistillationResult}.
 */
class MemoryDistillationResultTest {

    private static final Instant NOW = Instant.now();

    @Test
    void shouldCreateWithStoredAndRevokedEvents() {
        var stored = List.of(
                new MemoryStoredEvent("agg-1", "mem-1", "testing", "Use JUnit 5",
                        "src/test", "best practice", NOW),
                new MemoryStoredEvent("agg-1", "mem-2", "logging", "Use SLF4J",
                        "src/main", "consistency", NOW)
        );
        var revoked = List.of(
                new MemoryRevokedEvent("agg-1", "mem-old-1", "superseded by new info", NOW)
        );

        var result = new MemoryDistillationResult(stored, revoked);

        assertThat(result.stored()).hasSize(2);
        assertThat(result.revoked()).hasSize(1);
    }

    @Test
    void shouldCreateWithEmptyLists() {
        var result = new MemoryDistillationResult(List.of(), List.of());

        assertThat(result.stored()).isEmpty();
        assertThat(result.revoked()).isEmpty();
    }
}
