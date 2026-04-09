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
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embabel action that sends a {@link Command} to another aggregate via the {@link CommandBus}.
 *
 * <p>This action enables cross-aggregate coordination from within an agentic aggregate's
 * AI-assisted reasoning loop. When the agent determines that it needs to interact with
 * another aggregate (e.g., sending a {@code CreditWalletCommand} to a Wallet aggregate),
 * it places the command on the blackboard and this action picks it up and routes it
 * through the {@link CommandBus}.
 *
 * <p>The {@link CommandBus} implementation is provided by
 * {@link org.elasticsoftware.akces.agentic.runtime.AgenticAggregatePartition}, which
 * resolves the target aggregate's command topic and sends the command via Kafka.
 *
 * <p><strong>Blackboard inputs:</strong>
 * <ul>
 *   <li>{@link Command} — the command to dispatch, created by the agent during reasoning</li>
 *   <li>{@link CommandBus} — the command bus for routing, populated by the handler adapter</li>
 * </ul>
 *
 * @see org.elasticsoftware.akces.commands.CommandBus
 * @see org.elasticsoftware.akces.agentic.runtime.AgenticAggregatePartition
 */
@EmbabelComponent
public class SendCommandAction {

    private static final Logger logger = LoggerFactory.getLogger(SendCommandAction.class);

    /**
     * Sends a command to another aggregate via the command bus.
     *
     * <p>The {@link Command} and {@link CommandBus} are resolved from the agent's blackboard.
     * The command bus routes the command to the appropriate aggregate's command topic
     * based on the command type.
     *
     * @param command    the command to send; resolved from the blackboard by type
     * @param commandBus the command bus for routing; resolved from the blackboard by type
     */
    @Action(description = "Send a command to another aggregate via the command bus")
    public void sendCommand(Command command, CommandBus commandBus) {
        logger.debug("Sending command {} to aggregate via command bus",
                command.getClass().getSimpleName());
        commandBus.send(command);
        logger.debug("Command {} dispatched successfully", command.getClass().getSimpleName());
    }
}
