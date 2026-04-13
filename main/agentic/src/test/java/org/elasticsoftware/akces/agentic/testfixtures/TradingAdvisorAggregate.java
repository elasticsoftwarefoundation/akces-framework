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

import org.elasticsoftware.akces.aggregate.AgenticAggregate;
import org.elasticsoftware.akces.annotations.AgenticAggregateInfo;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A test agentic aggregate that simulates a trading advisor AI agent.
 *
 * <p>This aggregate is used for integration testing of the agentic layer. It:
 * <ul>
 *   <li>Auto-creates its singleton state via {@link #getCreateDomainEvent()}</li>
 *   <li>Declares {@link AnalyzeMarketCommand} as an agent-handled command</li>
 *   <li>Defines an {@code @EventSourcingHandler} for {@link MarketAnalysisCompletedEvent}</li>
 * </ul>
 */
@AgenticAggregateInfo(
        value = "TradingAdvisor",
        stateClass = TradingAdvisorState.class,
        agentHandledCommands = {AnalyzeMarketCommand.class}
)
public class TradingAdvisorAggregate implements AgenticAggregate<TradingAdvisorState> {

    @Override
    public Class<TradingAdvisorState> getStateClass() {
        return TradingAdvisorState.class;
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
}
