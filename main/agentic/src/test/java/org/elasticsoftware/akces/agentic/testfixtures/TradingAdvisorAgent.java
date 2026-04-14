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

package org.elasticsoftware.akces.agentic.testfixtures;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.CoreToolGroups;
import org.elasticsoftware.akces.agentic.commands.AssignTaskCommand;

/**
 * Embabel agent that handles tasks for the {@link TradingAdvisorAggregate}.
 *
 * <p>This agent is resolved by the framework via the {@code TradingAdvisorAgent} suffix
 * convention (aggregate name "TradingAdvisor" + "Agent" suffix). It uses the Anthropic LLM
 * to analyze market data based on the assigned task description and produces a
 * {@link MarketAnalysisCompletedEvent} as the result.
 *
 * <p>The agent uses the UTILITY planner which makes it suitable for single-action plans.
 * The {@code @Action} method receives the {@link AssignTaskCommand} from the blackboard
 * (injected by the framework when the task is assigned) along with the current aggregate
 * state, and produces a domain event that will be applied to the aggregate.
 */
@Agent(name = TradingAdvisorAggregate.AGGREGATE_ID,
        description = "AI-powered trading advisor that analyzes markets and produces trading insights",
        planner = PlannerType.GOAP)
public class TradingAdvisorAgent {

    /**
     * Analyzes a market based on the assigned task and produces a market analysis event.
     *
     * <p>The LLM is used to generate a concise market analysis based on the task description.
     * The result is wrapped in a {@link MarketAnalysisCompletedEvent} which will be applied
     * to the aggregate state via the {@code @EventSourcingHandler} on the aggregate.
     *
     * @param command the AssignTask command containing the task description and aggregate id
     * @param state   the current TradingAdvisor state (injected from blackboard)
     * @param context the Embabel action context providing access to AI capabilities
     * @return a {@link MarketAnalysisCompletedEvent} with the analysis result
     */
    @Action(description = "Analyze market data based on the assigned task and produce a market analysis")
    @AchievesGoal(description = "Complete the assigned task")
    public MarketAnalysisCompletedEvent analyzeMarket(
            AssignTaskCommand command,
            TradingAdvisorState state,
            ActionContext context) {

        String prompt = """
                You are a trading advisor AI. Analyze the following market task and provide a brief,
                one-paragraph analysis. Keep your response concise (max 200 words).
                
                Task: %s
                Aggregate ID: %s
                
                Provide your market analysis:""".formatted(
                command.taskDescription(),
                command.agenticAggregateId());

        return context.ai()
                .withDefaultLlm()
                .withToolGroup(CoreToolGroups.WEB)
                .createObject(prompt, MarketAnalysisCompletedEvent.class);

    }
}
