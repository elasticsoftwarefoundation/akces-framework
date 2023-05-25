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

package org.elasticsoftware.akces.aggregate;

import jakarta.annotation.Nullable;
import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.akces.events.EventHandlerFunction;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.protocol.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;

public abstract class AggregateRuntimeBase implements AggregateRuntime {
    private final AggregateStateType<?> type;
    private final Class<? extends Aggregate> aggregateClass;
    private final CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler;
    private final EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler;
    private final EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler;
    private final Map<Class<?>,DomainEventType<?>> domainEvents;
    private final Map<String, List<CommandType<?>>> commandTypes;
    private final Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers;
    private final Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers;
    private final Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers;
    private final boolean generateGDPRKeyOnCreate;

    public AggregateRuntimeBase(AggregateStateType<?> type,
                                Class<? extends Aggregate> aggregateClass,
                                CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler,
                                EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler,
                                EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler,
                                Map<Class<?>, DomainEventType<?>> domainEvents,
                                Map<String, List<CommandType<?>>> commandTypes,
                                Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers,
                                Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers,
                                Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers,
                                boolean generateGDPRKeyOnCreate) {
        this.type = type;
        this.aggregateClass = aggregateClass;
        this.commandCreateHandler = commandCreateHandler;
        this.eventCreateHandler = eventCreateHandler;
        this.createStateHandler = createStateHandler;
        this.domainEvents = domainEvents;
        this.commandTypes = commandTypes;
        this.commandHandlers = commandHandlers;
        this.eventHandlers = eventHandlers;
        this.eventSourcingHandlers = eventSourcingHandlers;
        this.generateGDPRKeyOnCreate = generateGDPRKeyOnCreate;
    }

    @Override
    public String getName() {
        return type.typeName();
    }

    @Override
    public Class<? extends Aggregate> getAggregateClass() {
        return aggregateClass;
    }

    @Override
    public void handleCommandRecord(CommandRecord commandRecord,
                                    Consumer<ProtocolRecord> protocolRecordConsumer,
                                    Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        // determine command
        CommandType<?> commandType = getCommandType(commandRecord);
        // TODO: need to raise an ErrorEvent in case of exception
        // find handler
        if(commandType.create()) {
            handleCreateCommand(commandType, commandRecord, protocolRecordConsumer);
        } else {
            handleCommand(commandType, commandRecord, protocolRecordConsumer, stateRecordSupplier);
        }
    }

    private CommandType<?> getCommandType(CommandRecord commandRecord) {
        CommandType<?> commandType = commandTypes.getOrDefault(commandRecord.name(), emptyList()).stream()
                .filter(ct -> ct.version() == commandRecord.version())
                .findFirst().orElseThrow(RuntimeException::new); // TODO: replace with specific exception
        return commandType;
    }

    private void handleCreateCommand(CommandType<?> commandType,
                                     CommandRecord commandRecord,
                                     Consumer<ProtocolRecord> protocolRecordConsumer) throws IOException {
        // materialize command
        Command command = materialize(commandType, commandRecord);
        // apply the command
        Stream<DomainEvent> domainEvents = commandCreateHandler.apply(command, null);
        // always treat the first event as a create event
        Iterator<DomainEvent> itr = domainEvents.iterator();
        DomainEvent domainEvent = itr.next();
        // create the state
        AggregateState state = createStateHandler.apply(domainEvent, null);
        // store the state, generation is 1 because it is the first record
        AggregateStateRecord stateRecord = new AggregateStateRecord(
                commandRecord.tenantId(),
                type.typeName(),
                type.version(),
                serialize(state),
                getEncoding(type),
                state.getAggregateId(),
                commandRecord.correlationId(),
                1L);
        protocolRecordConsumer.accept(stateRecord);
        // store the domain event
        DomainEventType<?> type = getDomainEventType(domainEvent.getClass());
        DomainEventRecord eventRecord = new DomainEventRecord(
                commandRecord.tenantId(),
                type.typeName(),
                type.version(),
                serialize(domainEvent),
                getEncoding(type),
                domainEvent.getAggregateId(),
                commandRecord.correlationId(),
                stateRecord.generation());
        protocolRecordConsumer.accept(eventRecord);
        // if there are more events, handle them as normal events
        AggregateStateRecord currentStateRecord = stateRecord;
        while(itr.hasNext()) {
            DomainEvent nextDomainEvent = itr.next();
            currentStateRecord = processDomainEvent(commandRecord.correlationId(), protocolRecordConsumer, currentStateRecord, nextDomainEvent);
        }
    }

    private void handleCommand(CommandType<?> commandType,
                               CommandRecord commandRecord,
                               Consumer<ProtocolRecord> protocolRecordConsumer,
                               Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        Command command = materialize(commandType, commandRecord);
        AggregateStateRecord currentStateRecord = stateRecordSupplier.get();
        AggregateState currentState = materialize(currentStateRecord);
        Stream<DomainEvent> domainEvents = commandHandlers.get(commandType).apply(command, currentState);
        for(DomainEvent domainEvent : domainEvents.toList()) {
            currentStateRecord = processDomainEvent(commandRecord.correlationId(), protocolRecordConsumer, currentStateRecord, domainEvent);
        }
    }

    private void handleCreateEvent(DomainEventType<?> eventType,
                                   DomainEventRecord domainEventRecord,
                                   Consumer<ProtocolRecord> protocolRecordConsumer) throws IOException {
        // materialize the external event
        DomainEvent externalEvent = materialize(eventType, domainEventRecord);
        // apply the event(s)
        Stream<DomainEvent> domainEvents = eventCreateHandler.apply(externalEvent, null);
        // always treat the first event as the create event
        Iterator<DomainEvent> itr = domainEvents.iterator();
        DomainEvent domainEvent = itr.next();
        // create the state
        AggregateState state = createStateHandler.apply(domainEvent, null);
        // store the state, generation is 1 because it is the first record
        AggregateStateRecord stateRecord = new AggregateStateRecord(
                domainEventRecord.tenantId(),
                type.typeName(),
                type.version(),
                serialize(state),
                getEncoding(type),
                state.getAggregateId(),
                domainEventRecord.correlationId(),
                1L);
        protocolRecordConsumer.accept(stateRecord);
        // store the domain event
        DomainEventType<?> type = getDomainEventType(domainEvent.getClass());
        DomainEventRecord eventRecord = new DomainEventRecord(
                domainEventRecord.tenantId(),
                type.typeName(),
                type.version(),
                serialize(domainEvent),
                getEncoding(type),
                domainEvent.getAggregateId(),
                domainEventRecord.correlationId(),
                stateRecord.generation());
        protocolRecordConsumer.accept(eventRecord);
        // if there are more events, handle them as normal events
        AggregateStateRecord currentStateRecord = stateRecord;
        while(itr.hasNext()) {
            DomainEvent nextDomainEvent = itr.next();
            currentStateRecord = processDomainEvent(domainEventRecord.correlationId(), protocolRecordConsumer, currentStateRecord, nextDomainEvent);
        }
    }

    private void handleEvent(DomainEventType<?> eventType,
                             DomainEventRecord domainEventRecord,
                             Consumer<ProtocolRecord> protocolRecordConsumer,
                             Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        // materialize the event
        DomainEvent externalEvent = materialize(eventType, domainEventRecord);
        AggregateStateRecord currentStateRecord = stateRecordSupplier.get();
        AggregateState currentState = materialize(currentStateRecord);
        Stream<DomainEvent> domainEvents = eventHandlers.get(eventType).apply(externalEvent, currentState);
        for(DomainEvent domainEvent : domainEvents.toList()) {
            currentStateRecord = processDomainEvent(domainEventRecord.correlationId(), protocolRecordConsumer, currentStateRecord, domainEvent);
        }

    }

    private AggregateStateRecord processDomainEvent(String correlationId,
                                                    Consumer<ProtocolRecord> protocolRecordConsumer,
                                                    AggregateStateRecord currentStateRecord,
                                                    DomainEvent domainEvent) throws IOException {
        AggregateState currentState = materialize(currentStateRecord);
        DomainEventType<?> domainEventType = getDomainEventType(domainEvent.getClass());
        // error events don't change the state
        if(!(domainEvent instanceof ErrorEvent)) {
            AggregateState nextState = eventSourcingHandlers.get(domainEventType).apply(domainEvent, currentState);
            // store the state, increasing the generation by 1
            AggregateStateRecord nextStateRecord = new AggregateStateRecord(
                    currentStateRecord.tenantId(),
                    type.typeName(),
                    type.version(),
                    serialize(nextState),
                    getEncoding(type),
                    currentStateRecord.aggregateId(),
                    correlationId,
                    currentStateRecord.generation() + 1L);
            protocolRecordConsumer.accept(nextStateRecord);
            DomainEventRecord eventRecord = new DomainEventRecord(
                    currentStateRecord.tenantId(),
                    domainEventType.typeName(),
                    domainEventType.version(),
                    serialize(domainEvent),
                    getEncoding(domainEventType),
                    domainEvent.getAggregateId(),
                    correlationId,
                    nextStateRecord.generation());
            protocolRecordConsumer.accept(eventRecord);
            return nextStateRecord;
        } else {
            // this is an ErrorEvent, this doesn't alter the state but needs to be produced
            DomainEventRecord eventRecord = new DomainEventRecord(
                    currentStateRecord.tenantId(),
                    domainEventType.typeName(),
                    domainEventType.version(),
                    serialize(domainEvent),
                    getEncoding(domainEventType),
                    domainEvent.getAggregateId(),
                    correlationId,
                    -1L);  // ErrorEvents have no generation number because they don't alter the state
            protocolRecordConsumer.accept(eventRecord);
            // return the current state record since nothing was changed
            return currentStateRecord;
        }
    }

    @Override
    public void handleExternalDomainEventRecord(DomainEventRecord eventRecord, Consumer<ProtocolRecord> protocolRecordConsumer, Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        // determine the type to use for the external event
        DomainEventType<?> domainEventType = getDomainEventType(eventRecord);
        if(domainEventType != null) {
            if (domainEventType.create()) {
                handleCreateEvent(domainEventType, eventRecord, protocolRecordConsumer);
            } else {
                handleEvent(domainEventType, eventRecord, protocolRecordConsumer, stateRecordSupplier);
            }
        } // ignore if we don't have an external domainevent registered
    }

    @Nullable
    private DomainEventType<?> getDomainEventType(DomainEventRecord eventRecord) {
        // because it is an external event we need to find the highest version that is smaller than the eventRecord version
        return domainEvents.entrySet().stream()
                .filter(entry -> entry.getValue().external())
                .filter(entry -> entry.getValue().typeName().equals(eventRecord.name()))
                .filter(entry -> entry.getValue().version() <= eventRecord.version())
                .max(Comparator.comparingInt(entry -> entry.getValue().version()))
                .map(Map.Entry::getValue).orElse(null);
    }

    @Override
    public Collection<DomainEventType<?>> getAllDomainEventTypes() {
        return this.domainEvents.values();
    }

    @Override
    public Collection<DomainEventType<?>> getProducedDomainEventTypes() {
        return this.domainEvents.values().stream().filter(domainEventType -> !domainEventType.external()).collect(Collectors.toSet());
    }

    @Override
    public Collection<DomainEventType<?>> getExternalDomainEventTypes() {
        return this.domainEvents.values().stream().filter(DomainEventType::external).collect(Collectors.toSet());
    }

    @Override
    public Collection<CommandType<?>> getAllCommandTypes() {
        return this.commandTypes.values().stream().flatMap(Collection::stream).collect(Collectors.toSet());
    }

    @Override
    public Collection<CommandType<?>> getLocalCommandTypes() {
        return this.commandTypes.values().stream().flatMap(Collection::stream).filter(commandType -> !commandType.external()).collect(Collectors.toSet());
    }

    @Override
    public Collection<CommandType<?>> getExternalCommandTypes() {
        return this.commandTypes.values().stream().flatMap(Collection::stream).filter(CommandType::external).collect(Collectors.toSet());
    }

    private DomainEventType<?> getDomainEventType(Class<?> domainEventClass) {
        return domainEvents.get(domainEventClass);
    }

    @Override
    public CommandType<?> getLocalCommandType(String type, int version) {
        return commandTypes.getOrDefault(type, Collections.emptyList()).stream()
                .filter(commandType -> commandType.version() == version)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No CommandType found for type " + type + " and version " + version));
    }

    @Override
    public boolean shouldGenerateGPRKey(CommandRecord commandRecord) {
        return getCommandType(commandRecord).create() && generateGDPRKeyOnCreate;
    }

    @Override
    public boolean shouldGenerateGPRKey(DomainEventRecord eventRecord) {
        return Optional.ofNullable(getDomainEventType(eventRecord))
                .map(domainEventType -> domainEventType.create() && generateGDPRKeyOnCreate).orElse(false);
    }

    protected abstract DomainEvent materialize(DomainEventType<?> domainEventType, DomainEventRecord eventRecord) throws IOException;

    protected abstract AggregateState materialize(AggregateStateRecord stateRecord) throws IOException;

    protected abstract byte[] serialize(AggregateState state) throws IOException;

    protected abstract byte[] serialize(DomainEvent domainEvent) throws SerializationException;

    protected abstract PayloadEncoding getEncoding(CommandType<?> type);

    protected abstract PayloadEncoding getEncoding(DomainEventType<?> type);

    protected abstract PayloadEncoding getEncoding(AggregateStateType<?> type);

    protected AggregateStateType<?> getAggregateStateType(AggregateStateRecord record) {
        // TODO: add support for more state versions
        return type;
    }
}
