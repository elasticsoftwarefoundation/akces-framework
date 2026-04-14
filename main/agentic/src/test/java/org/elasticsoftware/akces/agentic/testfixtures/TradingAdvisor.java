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
import org.elasticsoftware.akces.aggregate.AgenticAggregate;
import org.elasticsoftware.akces.annotations.AgenticAggregateInfo;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A test agentic aggregate and Embabel agent combined in a single class.
 *
 * <p>This class serves as both:
 * <ul>
 *   <li>An {@link AgenticAggregate} that auto-creates its singleton state via
 *       {@link #getCreateDomainEvent()} and defines {@code @EventSourcingHandler}s
 *       for its domain events.</li>
 *   <li>An Embabel {@link Agent} that handles tasks assigned to the aggregate.
 *       The {@code @Action} method uses the Anthropic LLM to analyze market data
 *       and produces a {@link MarketAnalysisCompletedEvent}.</li>
 * </ul>
 *
 * <p>Used for integration testing of the agentic layer with Testcontainers Kafka.
 */
@AgenticAggregateInfo(
        value = TradingAdvisor.AGGREGATE_ID,
        stateClass = TradingAdvisorState.class
)
@Agent(name = TradingAdvisor.AGGREGATE_ID,
        description = "AI-powered trading advisor that analyzes markets and produces trading insights",
        planner = PlannerType.GOAP)
public class TradingAdvisor implements AgenticAggregate<TradingAdvisorState> {

    public static final String AGGREGATE_ID = "TradingAdvisor";

    // -------------------------------------------------------------------------
    // AgenticAggregate implementation
    // -------------------------------------------------------------------------

    @Override
    public Class<TradingAdvisorState> getStateClass() {
        return TradingAdvisorState.class;
    }

    @Override
    public String getName() {
        return AGGREGATE_ID;
    }

    @Override
    public DomainEvent getCreateDomainEvent() {
        return new TradingAdvisorCreatedEvent(getName());
    }

    @EventSourcingHandler(create = true)
    public TradingAdvisorState create(TradingAdvisorCreatedEvent event, TradingAdvisorState isNull) {
        return new TradingAdvisorState(event.advisorId());
    }

    @EventSourcingHandler
    public TradingAdvisorState apply(MarketAnalysisCompletedEvent event, TradingAdvisorState state) {
        var analyses = new ArrayList<>(state.analyses());
        analyses.add(event.analysis());
        return new TradingAdvisorState(
                state.id(),
                state.memories(),
                state.memoryDistillations(),
                state.assignedTasks(),
                List.copyOf(analyses));
    }

    // -------------------------------------------------------------------------
    // Embabel Agent action
    // -------------------------------------------------------------------------

    /**
     * Analyzes a market based on the assigned task and produces a market analysis event.
     *
     * <p>The LLM is used to generate a concise market analysis based on the task description.
     * The result is wrapped in a {@link MarketAnalysisCompletedEvent} which will be applied
     * to the aggregate state via the {@code @EventSourcingHandler} on this class.
     *
     * @param command the AssignTask command containing the task description and aggregate id
     * @param context the Embabel action context providing access to AI capabilities
     * @return a {@link MarketAnalysisCompletedEvent} with the analysis result
     */
    @Action(description = "Analyze market data based on the assigned task and produce a market analysis")
    @AchievesGoal(description = "Analyze a Crypto Market")
    public MarketAnalysisCompletedEvent analyzeMarket(
            AssignTaskCommand command,
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
