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
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

/**
 * Describes a domain-event type produced or consumed by an aggregate service, as
 * published on the {@code Akces-Control} topic.
 *
 * @param typeName    the logical name of the domain-event type
 * @param version     the schema version of the domain event
 * @param create      whether this event creates a new aggregate instance
 * @param external    whether this event originates from another aggregate
 * @param schemaName  the schema registry subject name for this domain event
 * @param description a human-readable description of the domain event; may be {@code null}
 */
public record AggregateServiceDomainEventType(
        String typeName,
        int version,
        boolean create,
        boolean external,
        String schemaName,
        @Nullable String description
) {
    /**
     * Converts this service domain-event type into a {@link DomainEventType} for use
     * within an aggregate runtime.
     *
     * @param typeClass the Java class of the domain event
     * @param error     whether this event represents an error
     * @param <E>       the domain-event type
     * @return a new {@link DomainEventType} instance
     */
    public <E extends DomainEvent> DomainEventType<E> toLocalDomainEventType(Class<E> typeClass, boolean error) {
        return new DomainEventType<>(typeName, version, typeClass, create, external, error, hasPIIDataAnnotation(typeClass));
    }
}
