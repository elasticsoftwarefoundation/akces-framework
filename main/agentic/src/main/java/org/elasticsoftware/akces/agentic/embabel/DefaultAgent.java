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

import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.PlannerType;

/**
 * The default Embabel agent that is always present on the {@link com.embabel.agent.core.AgentPlatform}.
 * This agent is used as a fallback when no other agent matches the aggregate name during
 * agent resolution in the agentic command and event handler adapters.
 *
 * <p>Uses the {@link PlannerType#UTILITY UTILITY} planner for flexible goal-based planning.
 * Actions and conditions will be added as the agentic framework evolves.
 */
@Agent(name = DefaultAgent.AGENT_NAME, description = "Default Akces agentic aggregate agent", planner = PlannerType.UTILITY)
public class DefaultAgent {

    /**
     * The well-known agent name used to locate this default agent on the
     * {@link com.embabel.agent.core.AgentPlatform} during fallback resolution.
     */
    public static final String AGENT_NAME = "DefaultAgent";
}
