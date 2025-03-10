/*
 * Copyright 2022 - 2025 The Original Authors
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

import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

public record AggregateServiceDomainEventType(
        String typeName,
        int version,
        boolean create,
        boolean external,
        String schemaName
) {
    public <E extends DomainEvent> DomainEventType<E> toLocalDomainEventType(Class<E> typeClass, boolean error) {
        return new DomainEventType<>(typeName, version, typeClass, create, external, error, hasPIIDataAnnotation(typeClass));
    }
}
