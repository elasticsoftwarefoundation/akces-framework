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

/**
 * A {@link RequestingParty} implementation representing an AI agent that requested
 * a task assignment.
 *
 * @param agentId   the unique identifier of the requesting agent
 * @param agentName the display name of the requesting agent
 * @param role      the role of the requesting agent in the system
 *                  (e.g. "orchestrator", "supervisor", "analyst")
 */
public record AgentRequestingParty(
        String agentId,
        String agentName,
        String role
) implements RequestingParty {
}
