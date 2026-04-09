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

package org.elasticsoftware.akces.agentic.agent;

import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;

import java.util.List;

/**
 * Result object produced by the {@link AkcesAgentComponent#learnFromProcess
 * learnFromProcess} action when the learning cycle completes.
 *
 * <p>This record is the output type of the
 * {@link AkcesAgentComponent#learnFromProcess learnFromProcess} action and serves
 * as a structured summary of the memory management operations performed during a single
 * agent process execution. The LLM populates the event lists by calling the
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate#storeMemory storeMemory}
 * and {@link org.elasticsoftware.akces.aggregate.AgenticAggregate#forgetMemory forgetMemory}
 * {@code @Tool} methods during the learning process.
 *
 * @param memoriesStored  the list of {@link MemoryStoredEvent}s produced during this
 *                        learning cycle
 * @param memoriesRevoked the list of {@link MemoryRevokedEvent}s produced during this
 *                        learning cycle
 * @param summary         a human-readable summary of what was learned and any capacity
 *                        management actions taken
 */
public record MemoryLearningResult(
        List<MemoryStoredEvent> memoriesStored,
        List<MemoryRevokedEvent> memoriesRevoked,
        String summary
) {}
