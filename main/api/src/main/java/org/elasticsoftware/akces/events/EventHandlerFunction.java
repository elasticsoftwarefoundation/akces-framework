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

package org.elasticsoftware.akces.events;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.CommandBusHolder;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.commands.CommandBus;

import java.util.List;
import java.util.stream.Stream;

@FunctionalInterface
public interface EventHandlerFunction<S extends AggregateState,InputEvent extends DomainEvent, E extends DomainEvent> {
    @NotEmpty Stream<E> apply(@NotNull InputEvent event, S state);

    default DomainEventType<InputEvent> getEventType() {
        throw new UnsupportedOperationException("When implementing EventHandlerFunction directly, you must override getEventType()");
    }

    default Aggregate<S> getAggregate() {
        throw new UnsupportedOperationException("When implementing EventHandlerFunction directly, you must override getAggregate()");
    }

    default boolean isCreate() {
        throw new UnsupportedOperationException("When implementing EventHandlerFunction directly, you must override isCreate()");
    }

    default List<DomainEventType<?>> getProducedDomainEventTypes() {
        throw new UnsupportedOperationException("When implementing EventHandlerFunction directly, you must override getProducedDomainEventTypes()");
    }

    default List<DomainEventType<?>> getErrorEventTypes() {
        throw new UnsupportedOperationException("When implementing EventHandlerFunction directly, you must override getErrorEventTypes()");
    }

    default CommandBus getCommandBus() {
        return CommandBusHolder.getCommandBus(getAggregate().getClass());
    }
}
