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

package org.elasticsoftware.akces.query;

import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;

@FunctionalInterface
public interface DatabaseModelEventHandlerFunction<E extends DomainEvent> {
    void accept(@Nonnull E event);

    default DomainEventType<E> getEventType() {
        throw new UnsupportedOperationException("When implementing QueryModelEventHandlerFunction directly, you must override getEventType()");
    }

    default DatabaseModel getDatabaseModel() {
        throw new UnsupportedOperationException("When implementing QueryModelEventHandlerFunction directly, you must override getDatabaseModel()");
    }
}
