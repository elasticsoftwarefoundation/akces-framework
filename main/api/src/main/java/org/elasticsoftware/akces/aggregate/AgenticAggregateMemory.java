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

import java.time.Instant;

/**
 * An immutable memory entry stored by an {@link AgenticAggregate}.
 *
 * @param memoryId  UUID uniquely identifying this memory entry
 * @param subject   1–2 word topic label for the memory (e.g. "naming conventions")
 * @param fact      The fact to remember (max 200 characters)
 * @param citations Source of the fact (e.g. a file path and line number)
 * @param reason    Why this fact is being stored
 * @param storedAt  The instant at which this memory was recorded
 */
public record AgenticAggregateMemory(
        String memoryId,
        String subject,
        String fact,
        String citations,
        String reason,
        Instant storedAt
) {}
