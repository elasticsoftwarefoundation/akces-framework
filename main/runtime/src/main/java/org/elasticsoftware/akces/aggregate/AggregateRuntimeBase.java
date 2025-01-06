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

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.errors.AggregateAlreadyExistsErrorEvent;
import org.elasticsoftware.akces.errors.CommandExecutionErrorEvent;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.akces.kafka.KafkaAggregateRuntime;
import org.elasticsoftware.akces.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;

public abstract class AggregateRuntimeBase implements AggregateRuntime {
    protected static final Logger log = LoggerFactory.getLogger(KafkaAggregateRuntime.class);
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
                                    BiConsumer<DomainEventRecord,IndexParams> domainEventIndexer,
                                    Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        // determine command
        CommandType<?> commandType = getCommandType(commandRecord);
        try {
            if (commandType.create()) {
                // if we already have state, this is an error and the aggregate already exists
                if (stateRecordSupplier.get() != null) {
                    log.warn("Command {} wants to create a {} Aggregate with id {}, but the state already exists. Generating a AggregateAlreadyExistsError",
                            commandRecord.name(),
                            getName(),
                            commandRecord.aggregateId());
                    aggregateAlreadyExists(commandRecord, protocolRecordConsumer);
                } else {
                    handleCreateCommand(commandType, commandRecord, protocolRecordConsumer, domainEventIndexer);
                }
            } else {
                // see if we have a handler configured for this command
                if (commandHandlers.containsKey(commandType)) {
                    handleCommand(commandType, commandRecord, protocolRecordConsumer, domainEventIndexer, stateRecordSupplier);
                } else {
                    commandExecutionError(commandRecord, protocolRecordConsumer, "No handler found for command " + commandRecord.name());
                }
            }
        } catch(Throwable t) {
            // TODO: potentially see if we need to terminate the runtime
            log.error("Exception while handling command, sending CommandExecutionError", t);
            commandExecutionError(commandRecord, protocolRecordConsumer, t);
        }
    }

    private void aggregateAlreadyExists(ProtocolRecord commandOrDomainEventRecord,
                                        Consumer<ProtocolRecord> protocolRecordConsumer) {
        AggregateAlreadyExistsErrorEvent errorEvent = new AggregateAlreadyExistsErrorEvent(commandOrDomainEventRecord.aggregateId(), this.getName());
        DomainEventType<?> type = getDomainEventType(AggregateAlreadyExistsErrorEvent.class);
        DomainEventRecord eventRecord = new DomainEventRecord(
                commandOrDomainEventRecord.tenantId(),
                type.typeName(),
                type.version(),
                serialize(errorEvent),
                getEncoding(type),
                errorEvent.getAggregateId(),
                commandOrDomainEventRecord.correlationId(),
                -1L); // ErrorEvents have no generation number because they don't alter the state
        protocolRecordConsumer.accept(eventRecord);
    }

    private void commandExecutionError(CommandRecord commandRecord,
                                       Consumer<ProtocolRecord> protocolRecordConsumer,
                                       Throwable exception) {
        commandExecutionError(commandRecord, protocolRecordConsumer, exception.getMessage());
    }

    private void commandExecutionError(CommandRecord commandRecord,
                                       Consumer<ProtocolRecord> protocolRecordConsumer,
                                       String errorDescription) {
        CommandExecutionErrorEvent errorEvent = new CommandExecutionErrorEvent(
                commandRecord.aggregateId(),
                this.getName(),
                commandRecord.name(),
                errorDescription);
        DomainEventType<?> type = getDomainEventType(AggregateAlreadyExistsErrorEvent.class);
        DomainEventRecord eventRecord = new DomainEventRecord(
                commandRecord.tenantId(),
                type.typeName(),
                type.version(),
                serialize(errorEvent),
                getEncoding(type),
                errorEvent.getAggregateId(),
                commandRecord.correlationId(),
                -1L); // ErrorEvents have no generation number because they don't alter the state
        protocolRecordConsumer.accept(eventRecord);
    }

    private CommandType<?> getCommandType(CommandRecord commandRecord) {
        CommandType<?> commandType = commandTypes.getOrDefault(commandRecord.name(), emptyList()).stream()
                .filter(ct -> ct.version() == commandRecord.version())
                .findFirst().orElseThrow(RuntimeException::new); // TODO: replace with specific exception
        return commandType;
    }

    private void indexDomainEventIfRequired(DomainEventRecord domainEventRecord,
                                            AggregateState state,
                                            BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer) {
        if(type.indexed()) {
            domainEventIndexer.accept(domainEventRecord, new IndexParams(type.indexName(),state.getIndexKey()));
        }
    }

    private void handleCreateCommand(CommandType<?> commandType,
                                     CommandRecord commandRecord,
                                     Consumer<ProtocolRecord> protocolRecordConsumer,
                                     BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer) throws IOException {
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
        indexDomainEventIfRequired(eventRecord, state, domainEventIndexer);
        // if there are more events, handle them as normal events
        AggregateStateRecord currentStateRecord = stateRecord;
        while(itr.hasNext()) {
            DomainEvent nextDomainEvent = itr.next();
            currentStateRecord = processDomainEvent(
                    commandRecord.correlationId(),
                    protocolRecordConsumer,
                    domainEventIndexer,
                    currentStateRecord,
                    nextDomainEvent);
        }
    }

    private void handleCommand(CommandType<?> commandType,
                               CommandRecord commandRecord,
                               Consumer<ProtocolRecord> protocolRecordConsumer,
                               BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                               Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {

        Command command = materialize(commandType, commandRecord);
        AggregateStateRecord currentStateRecord = stateRecordSupplier.get();
        AggregateState currentState = materialize(currentStateRecord);
        Stream<DomainEvent> domainEvents = commandHandlers.get(commandType).apply(command, currentState);
        for(DomainEvent domainEvent : domainEvents.toList())
            currentStateRecord = processDomainEvent(commandRecord.correlationId(),
                    protocolRecordConsumer,
                    domainEventIndexer,
                    currentStateRecord,
                    domainEvent);
    }

    private void handleCreateEvent(DomainEventType<?> eventType,
                                   DomainEventRecord domainEventRecord,
                                   Consumer<ProtocolRecord> protocolRecordConsumer,
                                   BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer) throws IOException {
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
        indexDomainEventIfRequired(eventRecord, state, domainEventIndexer);
        // if there are more events, handle them as normal events
        AggregateStateRecord currentStateRecord = stateRecord;
        while(itr.hasNext()) {
            DomainEvent nextDomainEvent = itr.next();
            currentStateRecord = processDomainEvent(
                    domainEventRecord.correlationId(),
                    protocolRecordConsumer,
                    domainEventIndexer,
                    currentStateRecord,
                    nextDomainEvent);
        }
    }

    private void handleEvent(DomainEventType<?> eventType,
                             DomainEventRecord domainEventRecord,
                             Consumer<ProtocolRecord> protocolRecordConsumer,
                             BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                             Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        // materialize the event
        DomainEvent externalEvent = materialize(eventType, domainEventRecord);
        AggregateStateRecord currentStateRecord = stateRecordSupplier.get();
        AggregateState currentState = materialize(currentStateRecord);
        Stream<DomainEvent> domainEvents = eventHandlers.get(eventType).apply(externalEvent, currentState);
        for(DomainEvent domainEvent : domainEvents.toList())
            currentStateRecord = processDomainEvent(
                    domainEventRecord.correlationId(),
                    protocolRecordConsumer,
                    domainEventIndexer,
                    currentStateRecord,
                    domainEvent);

    }

    private AggregateStateRecord processDomainEvent(String correlationId,
                                                    Consumer<ProtocolRecord> protocolRecordConsumer,
                                                    BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                                                    AggregateStateRecord currentStateRecord,
                                                    DomainEvent domainEvent) throws IOException {
        AggregateState currentState = materialize(currentStateRecord);
        DomainEventType<?> domainEventType = getDomainEventType(domainEvent.getClass());
        // error events don't change the state
        if(!(domainEvent instanceof ErrorEvent)) {
            AggregateState nextState = eventSourcingHandlers.get(domainEventType).apply(domainEvent, currentState);
            // store the state, increasing the generation by 1
            AggregateStateRecord nextStateRecord = new AggregateStateRecord(
                    currentStateRecord.tenantId(), // inherit tenantId from the state record
                    type.typeName(),
                    type.version(),
                    serialize(nextState),
                    getEncoding(type),
                    currentStateRecord.aggregateId(),
                    correlationId,
                    currentStateRecord.generation() + 1L);
            protocolRecordConsumer.accept(nextStateRecord);
            DomainEventRecord eventRecord = new DomainEventRecord(
                    currentStateRecord.tenantId(), // inherit tenantId from the state record
                    domainEventType.typeName(),
                    domainEventType.version(),
                    serialize(domainEvent),
                    getEncoding(domainEventType),
                    domainEvent.getAggregateId(),
                    correlationId,
                    nextStateRecord.generation());
            protocolRecordConsumer.accept(eventRecord);
            indexDomainEventIfRequired(eventRecord, nextState, domainEventIndexer);
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
            // NOTE: we don't need to index the ErrorEvent since it does not change any state
            // return the current state record since nothing was changed
            return currentStateRecord;
        }
    }

    @Override
    public void handleExternalDomainEventRecord(DomainEventRecord eventRecord,
                                                Consumer<ProtocolRecord> protocolRecordConsumer,
                                                BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                                                Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        // determine the type to use for the external event
        DomainEventType<?> domainEventType = getDomainEventType(eventRecord);
        // with external domainevents we should look at the handler and not at the type of the external event
        if(domainEventType != null) {
            if (eventCreateHandler != null && eventCreateHandler.getEventType().equals(domainEventType) ) {
                // if the state already exists, this is an error.
                if(stateRecordSupplier.get() != null) {
                    // this is an error, log it and generate a AggregateAlreadyExistsError
                    log.warn("External DomainEvent {} wants to create a {} Aggregate with id {}, but the state already exists. Generate a AggregateAlreadyExistsError",
                            eventRecord.name(),
                            getName(),
                            eventRecord.aggregateId());
                    aggregateAlreadyExists(eventRecord, protocolRecordConsumer);
                } else {
                    handleCreateEvent(domainEventType, eventRecord, protocolRecordConsumer, domainEventIndexer);
                }
            } else {
                // only process the event if we have a handler for it
                if(eventHandlers.containsKey(domainEventType)) {
                    handleEvent(domainEventType, eventRecord, protocolRecordConsumer, domainEventIndexer, stateRecordSupplier);
                }
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

    protected void addCommand(CommandType<?> commandType) {
        this.commandTypes.computeIfAbsent(commandType.typeName(), typeName -> new ArrayList<>()).add(commandType);
    }
}
