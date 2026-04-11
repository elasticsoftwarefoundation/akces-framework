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

package org.elasticsoftware.akces.control;

import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.commands.Command;

public interface AkcesRegistry {
    CommandType<?> resolveType(@Nonnull Class<? extends Command> commandClass);

    /**
     * Resolves the command topic for the given command type and command instance.
     *
     * <p>When multiple aggregate services support the same command type (e.g. the built-in
     * {@code AssignTask} command shared by all agentic aggregates), the command's
     * {@link Command#getAggregateId() aggregateId} is used to select the correct target
     * service. For {@link AggregateServiceType#AGENTIC AGENTIC} services the aggregate
     * identifier matches the service's
     * {@link AggregateServiceRecord#aggregateName() aggregateName}, which doubles as the
     * Kafka topic prefix. This identity does <em>not</em> hold for
     * {@link AggregateServiceType#STANDARD STANDARD} services where aggregate identifiers
     * are arbitrary domain keys.
     *
     * @param commandType the command type to route
     * @param command     the command instance (used for routing context such as aggregateId)
     * @return the Kafka command topic name
     */
    String resolveTopic(@Nonnull CommandType<?> commandType, @Nonnull Command command);

    String resolveTopic(@Nonnull DomainEventType<?> externalDomainEventType);

    /**
     * Resolves the target partition for a command, taking into account both the command
     * instance and the target service type.
     *
     * <p>For {@link AggregateServiceType#STANDARD STANDARD} services the partition is
     * determined by hashing the aggregate identifier. For
     * {@link AggregateServiceType#AGENTIC AGENTIC} services (which are always
     * single-partition) this method returns {@code 0}.
     *
     * @param commandType the command type being routed (used to look up the target service)
     * @param command     the command instance (used for routing context such as aggregateId)
     * @return the target partition number
     */
    Integer resolvePartition(@Nonnull CommandType<?> commandType, @Nonnull Command command);
}
