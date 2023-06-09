/*
 * Copyright 2022 - 2023 The Original Authors
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

package org.elasticsoftware.akces.client;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import java.util.concurrent.CompletionStage;

public interface AkcesClient {
    /**
     * Dispatch a Command to the AkcesSystem. The Command will be sent to the AggregateService that is responsible for
     * handling it. There is no response, it is however possible check the {@link CompletionStage<String>} for either
     * a successful send or a failure.
     * <p>
     * The CompletionStage can be completed exceptionally with a {@link CommandRefusedException} if
     * the {@link AkcesClient} is not in RUNNING state when the {@link Command} is handled or it can be completed
     * exceptionally with a {@link CommandSendingFailedException} if their was a Kafka exception while sending the record
     * to the topic.
     * </p>
     *
     * @param           tenantId    The tenantId
     * @param           command     The Command to dispatch
     * @return          A CompletionStage that will be completed with the generated correlationId of the Command
     * @throws          CommandRefusedException if the AkcesClient is not in RUNNING state when this method is called
     * @throws          UnknownSchemaException if the {@link Command} has a schemaIdentifier that is not known to the AkcesSystem
     * @throws          UnroutableCommandException if the {@link Command} is not routable to any AggregateService
     * @throws          IllegalArgumentException if the {@link Command} class is not annotated with {@link CommandInfo}
     */
    default CompletionStage<String> send(@Nonnull String tenantId,@Nonnull Command command) {
        return send(tenantId, null, command);
    }

    CompletionStage<String> send(@Nonnull String tenantId, @Nullable String correlationId,@Nonnull Command command);

    default void sendAndForget(@Nonnull String tenantId, @Nonnull Command command){
        sendAndForget(tenantId, null, command);
    }

    void sendAndForget(@Nonnull String tenantId,@Nullable String correlationId,@Nonnull Command command);
}
