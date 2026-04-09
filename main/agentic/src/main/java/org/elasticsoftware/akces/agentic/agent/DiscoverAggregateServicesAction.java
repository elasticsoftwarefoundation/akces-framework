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

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Embabel action that discovers other aggregates in the system and their supported commands.
 *
 * <p>This read-only action exposes {@link AggregateServiceRecord}s loaded from the
 * {@code Akces-Control} topic to the agent, enabling it to discover what other aggregates
 * exist in the system and what commands they accept. This supports dynamic, system-aware
 * reasoning — for example, a TradingAdvisor agent can discover that a {@code Wallet}
 * aggregate accepts {@code CreditWalletCommand} and a {@code Portfolio} aggregate accepts
 * {@code PlaceOrderCommand}.
 *
 * <p>The aggregate service records are populated on the blackboard by the agentic handler
 * adapters before agent invocation. The
 * {@link org.elasticsoftware.akces.agentic.runtime.AkcesAgenticAggregateController}
 * maintains a {@code Map<String, AggregateServiceRecord>} loaded from the
 * {@code Akces-Control} topic at startup.
 *
 * <p><strong>Blackboard inputs:</strong>
 * <ul>
 *   <li>{@code Collection<AggregateServiceRecord>} — all known aggregate service records,
 *       populated by the handler adapter as the {@code "aggregateServices"} binding</li>
 * </ul>
 *
 * <p><strong>Blackboard output:</strong>
 * <ul>
 *   <li>{@link String} — a structured text summary of all discovered aggregates, their
 *       topics, types, supported commands, produced events, and consumed events</li>
 * </ul>
 *
 * @see org.elasticsoftware.akces.control.AggregateServiceRecord
 * @see org.elasticsoftware.akces.agentic.runtime.AkcesAgenticAggregateController
 */
@EmbabelComponent
public class DiscoverAggregateServicesAction {

    private static final Logger logger = LoggerFactory.getLogger(DiscoverAggregateServicesAction.class);

    /**
     * Discovers other aggregates in the system and returns a structured summary.
     *
     * <p>For each {@link AggregateServiceRecord}, the summary includes:
     * <ul>
     *   <li>{@code aggregateName} — the logical name of the aggregate</li>
     *   <li>{@code commandTopic} — the Kafka topic for sending commands</li>
     *   <li>{@code domainEventTopic} — the topic where the aggregate publishes events</li>
     *   <li>{@code type} — {@code STANDARD} or {@code AGENTIC}</li>
     *   <li>{@code supportedCommands} — list of command types the aggregate accepts</li>
     *   <li>{@code producedEvents} — list of domain event types the aggregate produces</li>
     *   <li>{@code consumedEvents} — list of external event types the aggregate consumes</li>
     * </ul>
     *
     * @param aggregateServices the collection of aggregate service records; resolved from
     *                          the blackboard (populated as {@code "aggregateServices"} binding)
     * @return a structured text summary of all discovered aggregate services
     */
    @Action(description = "Discover other aggregates in the system and their supported commands",
            readOnly = true)
    @SuppressWarnings("unchecked")
    public String discoverServices(Collection<AggregateServiceRecord> aggregateServices) {
        if (aggregateServices == null || aggregateServices.isEmpty()) {
            logger.debug("No aggregate services discovered");
            return "No aggregate services are currently available in the system.";
        }

        logger.debug("Discovering {} aggregate services", aggregateServices.size());

        StringBuilder summary = new StringBuilder("Discovered Aggregate Services:\n\n");

        for (AggregateServiceRecord record : aggregateServices) {
            summary.append("--- ").append(record.aggregateName()).append(" ---\n");
            summary.append("  Type: ").append(record.effectiveType()).append('\n');
            summary.append("  Command Topic: ").append(record.commandTopic()).append('\n');
            summary.append("  Event Topic: ").append(record.domainEventTopic()).append('\n');

            if (record.supportedCommands() != null && !record.supportedCommands().isEmpty()) {
                summary.append("  Supported Commands:\n");
                record.supportedCommands().forEach(cmd ->
                        summary.append("    - ").append(cmd.typeName())
                                .append(" (v").append(cmd.version()).append(")\n"));
            }

            if (record.producedEvents() != null && !record.producedEvents().isEmpty()) {
                summary.append("  Produced Events:\n");
                record.producedEvents().forEach(evt ->
                        summary.append("    - ").append(evt.typeName())
                                .append(" (v").append(evt.version()).append(")\n"));
            }

            if (record.consumedEvents() != null && !record.consumedEvents().isEmpty()) {
                summary.append("  Consumed Events:\n");
                record.consumedEvents().forEach(evt ->
                        summary.append("    - ").append(evt.typeName())
                                .append(" (v").append(evt.version()).append(")\n"));
            }

            summary.append('\n');
        }

        String result = summary.toString();
        logger.debug("Aggregate service discovery summary generated ({} chars)", result.length());
        return result;
    }
}
