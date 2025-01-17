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

package org.elasticsoftware.akces.aggregate;

import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.schemas.SchemaException;

import java.io.IOException;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface AggregateRuntime {

    String getName();

    Class<? extends Aggregate> getAggregateClass();

    void handleCommandRecord(CommandRecord commandRecord,
                             Consumer<ProtocolRecord> protocolRecordConsumer,
                             BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                             Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException;

    void handleExternalDomainEventRecord(DomainEventRecord eventRecord,
                                         Consumer<ProtocolRecord> protocolRecordConsumer,
                                         BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                                         Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException;

    Collection<DomainEventType<?>> getAllDomainEventTypes();

    Collection<DomainEventType<?>> getProducedDomainEventTypes();

    Collection<DomainEventType<?>> getExternalDomainEventTypes();

    Collection<CommandType<?>> getAllCommandTypes();

    Collection<CommandType<?>> getLocalCommandTypes();

    Collection<CommandType<?>> getExternalCommandTypes();

    CommandType<?> getLocalCommandType(String type, int version);

    void registerAndValidate(DomainEventType<?> domainEventType, boolean forceRegisterOnIncompatible) throws SchemaException;

    default void registerAndValidate(DomainEventType<?> domainEventType) throws SchemaException {
        registerAndValidate(domainEventType, false);
    }

    void registerAndValidate(CommandType<?> commandType,  boolean forceRegisterOnIncompatible) throws SchemaException;

    default void registerAndValidate(CommandType<?> commandType) throws SchemaException {
        registerAndValidate(commandType, false);
    }

    Command materialize(CommandType<?> commandType, CommandRecord commandRecord) throws IOException;

    byte[] serialize(Command command) throws SerializationException;

    boolean shouldGenerateGPRKey(CommandRecord commandRecord);

    boolean shouldGenerateGPRKey(DomainEventRecord eventRecord);
}
