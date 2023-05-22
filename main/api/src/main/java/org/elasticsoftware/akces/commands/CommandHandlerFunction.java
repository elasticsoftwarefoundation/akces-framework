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

package org.elasticsoftware.akces.commands;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.events.DomainEvent;

import java.util.List;
import java.util.stream.Stream;

@FunctionalInterface
public interface CommandHandlerFunction<S extends AggregateState,C extends Command, E extends DomainEvent> {
    @NotNull Stream<E> apply(@NotNull C command, S state);

    default boolean isCreate() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override isCreate()");
    }

    default CommandType<C> getCommandType() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getCommandType()");
    }

    default Aggregate<S> getAggregate() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getAggregate()");
    }

    default List<DomainEventType<?>> getProducedDomainEventTypes() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getProducedDomainEventTypes()");
    }

    default List<DomainEventType<?>> getErrorEventTypes() {
        throw new UnsupportedOperationException("When implementing CommandHandlerFunction directly, you must override getErrorEventTypes()");
    }

    default CommandBus getCommandBus() {
        return CommandBusHolder.getCommandBus(getAggregate().getClass());
    }
}
