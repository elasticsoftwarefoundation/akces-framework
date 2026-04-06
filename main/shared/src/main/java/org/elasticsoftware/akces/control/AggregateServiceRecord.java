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

import java.util.List;

/**
 * Describes an aggregate service registered on the {@code Akces-Control} topic.
 *
 * <p>The optional {@link #type()} field discriminates between standard and agentic
 * aggregate services. It defaults to {@link AggregateServiceType#STANDARD} for records
 * that pre-date the introduction of this field (see {@link #effectiveType()}).
 *
 * @param aggregateName    the logical name of the aggregate
 * @param commandTopic     the Kafka topic to which commands for this aggregate are sent
 * @param domainEventTopic the Kafka topic on which this aggregate publishes domain events
 * @param type             the service type; may be {@code null} for legacy records
 * @param supportedCommands the command types supported by this service
 * @param producedEvents    the domain-event types produced by this service
 * @param consumedEvents    the external domain-event types consumed by this service
 */
public record AggregateServiceRecord(
        String aggregateName,
        String commandTopic,
        String domainEventTopic,
        AggregateServiceType type,
        List<AggregateServiceCommandType> supportedCommands,
        List<AggregateServiceDomainEventType> producedEvents,
        List<AggregateServiceDomainEventType> consumedEvents
) implements AkcesControlRecord {

    /**
     * Returns the effective service type, defaulting to {@link AggregateServiceType#STANDARD}
     * when the field is absent in legacy records (deserialized from JSON without a {@code type}
     * property).
     *
     * @return the service type, never {@code null}
     */
    public AggregateServiceType effectiveType() {
        return type != null ? type : AggregateServiceType.STANDARD;
    }
}
