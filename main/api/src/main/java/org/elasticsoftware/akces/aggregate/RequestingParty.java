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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Represents the entity that requested a task assignment to an
 * {@link AgenticAggregate} — either another AI agent or a human user.
 *
 * <p>This is a sealed interface with two permitted implementations:
 * <ul>
 *   <li>{@link AgentRequestingParty} — when the requesting party is another AI agent</li>
 *   <li>{@link HumanRequestingParty} — when the requesting party is a human user</li>
 * </ul>
 *
 * <p>Each variant carries a {@link #role()} describing the requesting party's role in the
 * system (e.g. "administrator", "analyst", "orchestrator").
 *
 * <p>Jackson polymorphic serialization is configured via {@link JsonTypeInfo} and
 * {@link JsonSubTypes}, using a {@code "type"} discriminator property with values
 * {@code "agent"} and {@code "human"}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AgentRequestingParty.class, name = "agent"),
        @JsonSubTypes.Type(value = HumanRequestingParty.class, name = "human")
})
public sealed interface RequestingParty permits AgentRequestingParty, HumanRequestingParty {

    /**
     * The role of this requesting party in the system.
     *
     * <p>Examples: {@code "administrator"}, {@code "analyst"}, {@code "orchestrator"},
     * {@code "supervisor"}.
     *
     * @return the role; never {@code null}
     */
    String role();
}
