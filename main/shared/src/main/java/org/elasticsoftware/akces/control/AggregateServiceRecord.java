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

import jakarta.annotation.Nullable;

import java.util.List;

/**
 * Describes an aggregate service registered on the {@code Akces-Control} topic.
 *
 * <p>The optional {@link #type()} field discriminates between standard and agentic
 * aggregate services. It defaults to {@link AggregateServiceType#STANDARD} for records
 * that pre-date the introduction of this field (see {@link #effectiveType()}).
 *
 * <p>The optional {@link #description()} field provides a human-readable summary of
 * the aggregate, sourced from the {@code @AggregateInfo} or {@code @AgenticAggregateInfo}
 * annotation. It may be {@code null} for legacy records or when no description was
 * specified in the annotation.
 *
 * @param aggregateName    the logical name of the aggregate
 * @param commandTopic     the Kafka topic to which commands for this aggregate are sent
 * @param domainEventTopic the Kafka topic on which this aggregate publishes domain events
 * @param type             the service type; may be {@code null} for legacy records
 * @param supportedCommands the command types supported by this service
 * @param producedEvents    the domain-event types produced by this service
 * @param consumedEvents    the external domain-event types consumed by this service
 * @param description      a human-readable description of the aggregate; may be {@code null}
 */
public record AggregateServiceRecord(
        String aggregateName,
        String commandTopic,
        String domainEventTopic,
        AggregateServiceType type,
        List<AggregateServiceCommandType> supportedCommands,
        List<AggregateServiceDomainEventType> producedEvents,
        List<AggregateServiceDomainEventType> consumedEvents,
        @Nullable String description
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

    /**
     * Finds the matching aggregate service for a command when multiple services support the
     * same command type.
     *
     * <p>For {@link AggregateServiceType#AGENTIC AGENTIC} services, the aggregate identifier
     * is matched against the service's {@link #aggregateName()} (since agentic aggregate IDs
     * equal the aggregate name). If no matching AGENTIC service is found, falls back to the
     * single {@link AggregateServiceType#STANDARD STANDARD} service if one exists.
     *
     * @param services    the candidate services that support the command type
     * @param aggregateId the aggregate identifier from the command instance
     * @return the matching service, or {@code null} if no unique match can be determined
     */
    @Nullable
    public static AggregateServiceRecord resolveAgenticTarget(
            List<AggregateServiceRecord> services, String aggregateId) {
        // Try to find the AGENTIC service whose aggregate name matches the aggregateId
        AggregateServiceRecord agenticMatch = services.stream()
                .filter(s -> s.effectiveType() == AggregateServiceType.AGENTIC
                        && s.aggregateName().equals(aggregateId))
                .findFirst()
                .orElse(null);
        if (agenticMatch != null) {
            return agenticMatch;
        }
        // Fall back to the single STANDARD service if one exists
        List<AggregateServiceRecord> standard = services.stream()
                .filter(s -> s.effectiveType() == AggregateServiceType.STANDARD)
                .toList();
        if (standard.size() == 1) {
            return standard.getFirst();
        }
        return null;
    }
}
