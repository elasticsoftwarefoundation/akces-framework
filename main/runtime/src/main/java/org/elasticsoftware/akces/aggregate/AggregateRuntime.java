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

package org.elasticsoftware.akces.aggregate;

import jakarta.annotation.Nullable;
import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.elasticsoftware.akces.schemas.SchemaRegistry;

import org.elasticsoftware.akces.events.DomainEvent;

import java.io.IOException;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface AggregateRuntime {

    String getName();

    /**
     * Returns the human-readable description of this aggregate, as specified in
     * the {@code @AggregateInfo} or {@code @AgenticAggregateInfo} annotation.
     *
     * @return the description, or {@code null} if none was specified
     */
    @Nullable
    String getDescription();

    Class<? extends Aggregate<?>> getAggregateClass();

    void handleCommandRecord(CommandRecord commandRecord,
                             Consumer<ProtocolRecord> protocolRecordConsumer,
                             BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                             Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException;

    void handleExternalDomainEventRecord(DomainEventRecord eventRecord,
                                         Consumer<ProtocolRecord> protocolRecordConsumer,
                                         BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                                         Supplier<AggregateStateRecord> stateRecordSupplier,
                                         CommandBus commandBus) throws IOException;

    Collection<DomainEventType<?>> getAllDomainEventTypes();

    Collection<DomainEventType<?>> getProducedDomainEventTypes();

    Collection<DomainEventType<?>> getExternalDomainEventTypes();

    Collection<CommandType<?>> getAllCommandTypes();

    Collection<CommandType<?>> getLocalCommandTypes();

    Collection<CommandType<?>> getExternalCommandTypes();

    CommandType<?> getLocalCommandType(String type, int version);

    void registerAndValidate(DomainEventType<?> domainEventType, SchemaRegistry schemaRegistry, boolean forceRegisterOnIncompatible) throws SchemaException;

    default void registerAndValidate(DomainEventType<?> domainEventType, SchemaRegistry schemaRegistry) throws SchemaException {
        registerAndValidate(domainEventType, schemaRegistry, false);
    }

    void registerAndValidate(CommandType<?> commandType, SchemaRegistry schemaRegistry, boolean forceRegisterOnIncompatible) throws SchemaException;

    default void registerAndValidate(CommandType<?> commandType, SchemaRegistry schemaRegistry) throws SchemaException {
        registerAndValidate(commandType, schemaRegistry, false);
    }

    Command materialize(CommandType<?> commandType, CommandRecord commandRecord) throws IOException;

    byte[] serialize(Command command) throws SerializationException;

    boolean shouldGenerateGDPRKey(CommandRecord commandRecord);

    boolean shouldGenerateGDPRKey(DomainEventRecord eventRecord);

    boolean requiresGDPRContext(DomainEventRecord eventRecord);

    boolean requiresGDPRContext(CommandRecord eventRecord);

    boolean shouldHandlePIIData();

    /**
     * Materializes an {@link AggregateState} from the given {@link AggregateStateRecord},
     * applying any necessary state upcasting when the stored version differs from the
     * current schema.
     *
     * @param stateRecord the state record to deserialize and potentially upcast
     * @return the materialized aggregate state
     * @throws IOException if deserialization fails
     */
    AggregateState materializeState(AggregateStateRecord stateRecord) throws IOException;

    /**
     * Processes a stream of {@link DomainEvent}s against
     * the current aggregate state, applying event-sourcing handlers and emitting the
     * resulting {@link ProtocolRecord}s (domain-event records and state records) through
     * the given consumer.
     *
     * <p>This method is used by agentic aggregate runtimes to process events produced by
     * an agent tick outside of the normal command or external-event processing paths.
     *
     * @param events                  the domain events to process
     * @param correlationId           correlation ID to set on produced records (may be {@code null})
     * @param protocolRecordConsumer  consumer that receives the produced protocol records
     * @param stateRecordSupplier     supplier for the current aggregate state record
     * @throws IOException if serialization or deserialization fails
     */
    default void processDomainEvents(java.util.stream.Stream<DomainEvent> events,
                                     String correlationId,
                                     Consumer<ProtocolRecord> protocolRecordConsumer,
                                     Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        throw new UnsupportedOperationException("processDomainEvents not supported by this runtime");
    }
}
