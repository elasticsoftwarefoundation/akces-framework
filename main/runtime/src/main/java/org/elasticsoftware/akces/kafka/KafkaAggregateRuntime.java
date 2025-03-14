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

package org.elasticsoftware.akces.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import jakarta.annotation.Nullable;
import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.errors.AggregateAlreadyExistsErrorEvent;
import org.elasticsoftware.akces.errors.CommandExecutionErrorEvent;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils;
import org.elasticsoftware.akces.protocol.*;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.everit.json.schema.ValidationException;
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

public class KafkaAggregateRuntime implements AggregateRuntime {
    private static final Logger log = LoggerFactory.getLogger(KafkaAggregateRuntime.class);
    private final AggregateStateType<?> type;
    private final Class<? extends Aggregate<?>> aggregateClass;
    private final CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler;
    private final EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler;
    private final EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler;
    private final Map<Class<?>, DomainEventType<?>> domainEvents;
    private final Map<String, List<CommandType<?>>> commandTypes;
    private final Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers;
    private final Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers;
    private final Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers;
    private final Map<DomainEventType<?>, EventBridgeHandlerFunction<AggregateState, DomainEvent>> eventBridgeHandlers;
    private final Map<AggregateStateType<?>, UpcastingHandlerFunction<AggregateState, AggregateState, AggregateStateType<AggregateState>, AggregateStateType<AggregateState>>> stateUpcastingHandlers;
    private final Map<DomainEventType<?>, UpcastingHandlerFunction<DomainEvent, DomainEvent, DomainEventType<DomainEvent>, DomainEventType<DomainEvent>>> eventUpcastingHandlers;
    private final boolean generateGDPRKeyOnCreate;
    private final boolean shouldHandlePIIData;
    private final KafkaSchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends DomainEvent>, JsonSchema> domainEventSchemas = new HashMap<>();
    private final Map<Class<? extends Command>, JsonSchema> commandSchemas = new HashMap<>();

    private KafkaAggregateRuntime(KafkaSchemaRegistry schemaRegistry,
                                  ObjectMapper objectMapper,
                                  AggregateStateType<?> stateType,
                                  Class<? extends Aggregate<?>> aggregateClass,
                                  CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler,
                                  EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler,
                                  EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler,
                                  Map<Class<?>, DomainEventType<?>> domainEvents,
                                  Map<String, List<CommandType<?>>> commandTypes,
                                  Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers,
                                  Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers,
                                  Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers,
                                  Map<DomainEventType<?>, EventBridgeHandlerFunction<AggregateState, DomainEvent>> eventBridgeHandlers, Map<AggregateStateType<?>, UpcastingHandlerFunction<AggregateState, AggregateState, AggregateStateType<AggregateState>, AggregateStateType<AggregateState>>> stateUpcastingHandlers, Map<DomainEventType<?>, UpcastingHandlerFunction<DomainEvent, DomainEvent, DomainEventType<DomainEvent>, DomainEventType<DomainEvent>>> eventUpcastingHandlers,
                                  boolean generateGDPRKeyOnCreate,
                                  boolean shouldHandlePIIData) {
        this.type = stateType;
        this.aggregateClass = aggregateClass;
        this.commandCreateHandler = commandCreateHandler;
        this.eventCreateHandler = eventCreateHandler;
        this.createStateHandler = createStateHandler;
        this.domainEvents = domainEvents;
        this.commandTypes = commandTypes;
        this.commandHandlers = commandHandlers;
        this.eventHandlers = eventHandlers;
        this.eventSourcingHandlers = eventSourcingHandlers;
        this.eventBridgeHandlers = eventBridgeHandlers;
        this.stateUpcastingHandlers = stateUpcastingHandlers;
        this.eventUpcastingHandlers = eventUpcastingHandlers;
        this.generateGDPRKeyOnCreate = generateGDPRKeyOnCreate;
        this.shouldHandlePIIData = shouldHandlePIIData;
        this.schemaRegistry = schemaRegistry;
        this.objectMapper = objectMapper;
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
                                    BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
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
        } catch (Throwable t) {
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
                                            BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                                            boolean createIndex) {
        if (type.indexed()) {
            domainEventIndexer.accept(domainEventRecord, new IndexParams(type.indexName(), state.getIndexKey(), createIndex));
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
        indexDomainEventIfRequired(eventRecord, state, domainEventIndexer, true);
        // if there are more events, handle them as normal events
        AggregateStateRecord currentStateRecord = stateRecord;
        while (itr.hasNext()) {
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
        // it is possible to receive a command that doesn't have PIIData. However it could be that the state
        // does have PIIData. in that case we need to load the proper
        Command command = materialize(commandType, commandRecord);
        AggregateStateRecord currentStateRecord = stateRecordSupplier.get();
        AggregateState currentState = materialize(currentStateRecord);
        Stream<DomainEvent> domainEvents = commandHandlers.get(commandType).apply(command, currentState);
        for (DomainEvent domainEvent : domainEvents.toList())
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
        indexDomainEventIfRequired(eventRecord, state, domainEventIndexer, true);
        // if there are more events, handle them as normal events
        AggregateStateRecord currentStateRecord = stateRecord;
        while (itr.hasNext()) {
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
        for (DomainEvent domainEvent : domainEvents.toList())
            currentStateRecord = processDomainEvent(
                    domainEventRecord.correlationId(),
                    protocolRecordConsumer,
                    domainEventIndexer,
                    currentStateRecord,
                    domainEvent);

    }

    private void handleBridgedEvent(DomainEventType<?> eventType,
                                    DomainEventRecord domainEventRecord,
                                    CommandBus commandBus) throws IOException {
        // materialize the event
        DomainEvent externalEvent = materialize(eventType, domainEventRecord);
        eventBridgeHandlers.get(eventType).apply(externalEvent, commandBus);
    }

    private AggregateStateRecord processDomainEvent(String correlationId,
                                                    Consumer<ProtocolRecord> protocolRecordConsumer,
                                                    BiConsumer<DomainEventRecord, IndexParams> domainEventIndexer,
                                                    AggregateStateRecord currentStateRecord,
                                                    DomainEvent domainEvent) throws IOException {
        AggregateState currentState = materialize(currentStateRecord);
        DomainEventType<?> domainEventType = getDomainEventType(domainEvent.getClass());
        // error events don't change the state
        if (!(domainEvent instanceof ErrorEvent)) {
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
            indexDomainEventIfRequired(eventRecord, nextState, domainEventIndexer, false);
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
                                                Supplier<AggregateStateRecord> stateRecordSupplier,
                                                CommandBus commandBus) throws IOException {
        // determine the type to use for the external event
        DomainEventType<?> domainEventType = getDomainEventType(eventRecord);
        // with external domainevents we should look at the handler and not at the type of the external event
        if (domainEventType != null) {
            if (eventCreateHandler != null && eventCreateHandler.getEventType().equals(domainEventType)) {
                // if the state already exists, this is an error.
                if (stateRecordSupplier.get() != null) {
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
                if (eventHandlers.containsKey(domainEventType)) {
                    handleEvent(domainEventType, eventRecord, protocolRecordConsumer, domainEventIndexer, stateRecordSupplier);
                } else if(eventBridgeHandlers.containsKey(domainEventType)) { // it can also be an event bridge
                    handleBridgedEvent(domainEventType, eventRecord, commandBus);
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
    public boolean shouldGenerateGDPRKey(CommandRecord commandRecord) {
        return getCommandType(commandRecord).create() && generateGDPRKeyOnCreate;
    }

    @Override
    public boolean shouldGenerateGDPRKey(DomainEventRecord eventRecord) {
        return Optional.ofNullable(getDomainEventType(eventRecord))
                .map(domainEventType -> domainEventType.create() && generateGDPRKeyOnCreate).orElse(false);
    }

    @Override
    public boolean requiresGDPRContext(DomainEventRecord eventRecord) {
        // @TODO this will give issues with an EventBridgeHandler that has PIIData on the input event an PIIData on the outgoing command
        DomainEventType<?> domainEventType = getDomainEventType(eventRecord);
        if(domainEventType == null) {
            return false;
        } else {
            return domainEventType.piiData() || (!eventBridgeHandlers.containsKey(domainEventType) && type.piiData());
        }
    }

    @Override
    public boolean requiresGDPRContext(CommandRecord commandRecord) {
        return this.type.piiData() || getCommandType(commandRecord).piiData();
    }

    @Override
    public boolean shouldHandlePIIData() {
        return shouldHandlePIIData || generateGDPRKeyOnCreate;
    }

    @Override
    public void registerAndValidate(DomainEventType<?> domainEventType, boolean forceRegisterOnIncompatible) throws SchemaException {
        // generate the local schema version
        JsonSchema localSchema = schemaRegistry.registerAndValidate(domainEventType, forceRegisterOnIncompatible);
        // schema is fine, add to map
        domainEventSchemas.put(domainEventType.typeClass(), localSchema);
    }

    @Override
    public void registerAndValidate(CommandType<?> commandType,  boolean forceRegisterOnIncompatible) throws SchemaException {
        if (!commandSchemas.containsKey(commandType.typeClass())) {
            JsonSchema localSchema = schemaRegistry.registerAndValidate(commandType, forceRegisterOnIncompatible);
            commandSchemas.put(commandType.typeClass(), localSchema);
            if (commandType.external()) addCommand(commandType);
        }
    }

    @Override
    public Command materialize(CommandType<?> type, CommandRecord commandRecord) throws IOException {
        return objectMapper.readValue(commandRecord.payload(), type.typeClass());
    }

    private DomainEvent materialize(DomainEventType<?> domainEventType, DomainEventRecord eventRecord) throws IOException {
        DomainEvent domainEvent = objectMapper.readValue(eventRecord.payload(), domainEventType.typeClass());
        // check if we need to upcast the event
        if(eventUpcastingHandlers.containsKey(domainEventType)) {
            return upcast(domainEvent,  domainEventType);
        } else {
            return domainEvent;
        }
    }

    private DomainEvent upcast(DomainEvent domainEvent, DomainEventType<?> domainEventType) {
        final UpcastingHandlerFunction<DomainEvent, DomainEvent, DomainEventType<DomainEvent>, DomainEventType<DomainEvent>> upcastingHandlerFunction = eventUpcastingHandlers.get(domainEventType);
        DomainEvent upcastedEvent = upcastingHandlerFunction.apply(domainEvent);
        // see if we need to upcast the event
        if(eventUpcastingHandlers.containsKey(upcastingHandlerFunction.getOutputType())) {
            return upcast(upcastedEvent, upcastingHandlerFunction.getOutputType());
        } else {
            return upcastedEvent;
        }
    }

    private AggregateState materialize(AggregateStateRecord stateRecord) throws IOException {
        AggregateStateType<?> stateType = getAggregateStateType(stateRecord);
        AggregateState state = objectMapper.readValue(stateRecord.payload(), getAggregateStateType(stateRecord).typeClass());
        // see if we need to upcast the state
        if(!stateType.equals(type)) {
            return upcast(state, stateType);
        } else {
            return state;
        }
    }

    private AggregateState upcast(AggregateState state, AggregateStateType<?> inputStateType) {
        final UpcastingHandlerFunction<AggregateState, AggregateState, AggregateStateType<AggregateState>, AggregateStateType<AggregateState>> upcastingHandlerFunction = stateUpcastingHandlers.get(inputStateType);
        AggregateState upcastedState = upcastingHandlerFunction.apply(state);
        if(!upcastingHandlerFunction.getOutputType().equals(type)) {
            return upcast(upcastedState, upcastingHandlerFunction.getOutputType());
        } else {
            return upcastedState;
        }
    }

    private byte[] serialize(AggregateState state) throws IOException {
        return objectMapper.writeValueAsBytes(state);
    }

    private byte[] serialize(DomainEvent domainEvent) throws SerializationException {
        JsonNode jsonNode = objectMapper.convertValue(domainEvent, JsonNode.class);
        try {
            domainEventSchemas.get(domainEvent.getClass()).validate(jsonNode);
            return objectMapper.writeValueAsBytes(jsonNode);
        } catch (ValidationException e) {
            throw new SerializationException("Validation Failed while Serializing DomainEventClass " + domainEvent.getClass().getName(), e);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Serialization Failed while Serializing DomainEventClass " + domainEvent.getClass().getName(), e);
        }
    }

    @Override
    public byte[] serialize(Command command) throws SerializationException {
        JsonNode jsonNode = objectMapper.convertValue(command, JsonNode.class);
        try {
            commandSchemas.get(command.getClass()).validate(jsonNode);
            return objectMapper.writeValueAsBytes(command);
        } catch (ValidationException e) {
            throw new SerializationException("Validation Failed while Serializing CommandClass " + command.getClass().getName(), e);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Serialization Failed while Serializing CommandClass " + command.getClass().getName(), e);
        }

    }

    private PayloadEncoding getEncoding(CommandType<?> type) {
        return PayloadEncoding.JSON;
    }

    private PayloadEncoding getEncoding(DomainEventType<?> type) {
        return PayloadEncoding.JSON;
    }

    private PayloadEncoding getEncoding(AggregateStateType<?> type) {
        return PayloadEncoding.JSON;
    }

    private AggregateStateType<?> getAggregateStateType(AggregateStateRecord record) {
        // there is only one active state type, however there can be multiple versions. if so, there must be an upcaster
        // for the older versions to the current version
        // TODO: this should be checked at initialization time and the runtime should fail to start!
        if(type.typeName().equals(record.name()) && type.version() == record.version()) {
            return type;
        } else {
            // it's an older version. we need to get the upcaster for this version
            return stateUpcastingHandlers.keySet().stream()
                    .filter(aggregateStateType -> aggregateStateType.version() == record.version())
                    .findAny().orElseThrow(() -> new IllegalStateException("Aggregate state type for " + record.name() + " with version " + record.version() +" does not exist"));
        }
    }

    private void addCommand(CommandType<?> commandType) {
        this.commandTypes.computeIfAbsent(commandType.typeName(), typeName -> new ArrayList<>()).add(commandType);
    }

    public static class Builder {
        private KafkaSchemaRegistry schemaRegistry;
        private ObjectMapper objectMapper;
        private AggregateStateType<?> stateType;
        private Class<? extends Aggregate<?>> aggregateClass;
        private CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler;
        private EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler;
        private EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler;
        private final Map<Class<?>, DomainEventType<?>> domainEvents = new HashMap<>();
        private final Map<String, List<CommandType<?>>> commandTypes = new HashMap<>();
        private final Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers = new HashMap<>();
        private final Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers = new HashMap<>();
        private final Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers = new HashMap<>();
        private final Map<DomainEventType<?>, EventBridgeHandlerFunction<AggregateState, DomainEvent>> eventBridgeHandlers = new HashMap<>();
        private final Map<AggregateStateType<?>, UpcastingHandlerFunction<AggregateState, AggregateState, AggregateStateType<AggregateState>, AggregateStateType<AggregateState>>> stateUpcastingHandlers = new HashMap<>();
        private final Map<DomainEventType<?>, UpcastingHandlerFunction<DomainEvent, DomainEvent, DomainEventType<DomainEvent>, DomainEventType<DomainEvent>>> eventUpcastingHandlers = new HashMap<>();
        private boolean generateGDPRKeyOnCreate = false;

        public Builder setSchemaRegistry(KafkaSchemaRegistry schemaRegistry) {
            this.schemaRegistry = schemaRegistry;
            return this;
        }

        public Builder setObjectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder setStateType(AggregateStateType<?> stateType) {
            this.stateType = stateType;
            return this;
        }

        public Builder setAggregateClass(Class<? extends Aggregate<?>> aggregateClass) {
            this.aggregateClass = aggregateClass;
            return this;
        }

        public Builder setCommandCreateHandler(CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler) {
            this.commandCreateHandler = commandCreateHandler;
            return this;
        }

        public Builder setEventCreateHandler(EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler) {
            this.eventCreateHandler = eventCreateHandler;
            return this;
        }

        public Builder setEventSourcingCreateHandler(EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler) {
            this.createStateHandler = createStateHandler;
            return this;
        }

        public Builder addDomainEvent(DomainEventType<?> domainEvent) {
            this.domainEvents.put(domainEvent.typeClass(), domainEvent);
            return this;
        }

        public Builder addCommand(CommandType<?> commandType) {
            this.commandTypes.computeIfAbsent(commandType.typeName(), s -> new ArrayList<>()).add(commandType);
            return this;
        }

        public Builder addCommandHandler(CommandType<?> commandType, CommandHandlerFunction<AggregateState, Command, DomainEvent> commandHandler) {
            this.commandHandlers.put(commandType, commandHandler);
            return this;
        }

        public Builder addExternalEventHandler(DomainEventType<?> eventType, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventHandler) {
            this.eventHandlers.put(eventType, eventHandler);
            return this;
        }

        public Builder addEventSourcingHandler(DomainEventType<?> eventType, EventSourcingHandlerFunction<AggregateState, DomainEvent> eventSourcingHandler) {
            this.eventSourcingHandlers.put(eventType, eventSourcingHandler);
            return this;
        }

        public Builder addEventBridgeHandler(DomainEventType<?> eventType, EventBridgeHandlerFunction<AggregateState, DomainEvent> eventBridgeHandler) {
            this.eventBridgeHandlers.put(eventType, eventBridgeHandler);
            return this;
        }

        public Builder addStateUpcastingHandler(AggregateStateType<?> inputType,
                                               UpcastingHandlerFunction<AggregateState, AggregateState, AggregateStateType<AggregateState>, AggregateStateType<AggregateState>> upcastingHandler) {
            this.stateUpcastingHandlers.put(inputType, upcastingHandler);
            return this;
        }

        public Builder addEventUpcastingHandler(DomainEventType<?> inputType,
                                                UpcastingHandlerFunction<DomainEvent, DomainEvent, DomainEventType<DomainEvent>, DomainEventType<DomainEvent>> upcastingHandler) {
            this.eventUpcastingHandlers.put(inputType, upcastingHandler);
            return this;
        }


        public Builder setGenerateGDPRKeyOnCreate(boolean generateGDPRKeyOnCreate) {
            this.generateGDPRKeyOnCreate = generateGDPRKeyOnCreate;
            return this;
        }

        public KafkaAggregateRuntime build() {
            // see if we have a command and/or domain event with PIIData
            final boolean shouldHandlePIIData = domainEvents.values().stream().map(DomainEventType::typeClass)
                    .anyMatch(GDPRAnnotationUtils::hasPIIDataAnnotation) ||
                    commandTypes.values().stream().flatMap(List::stream).map(CommandType::typeClass)
                            .anyMatch(GDPRAnnotationUtils::hasPIIDataAnnotation);

            return new KafkaAggregateRuntime(
                    schemaRegistry,
                    objectMapper,
                    stateType,
                    aggregateClass,
                    commandCreateHandler,
                    eventCreateHandler,
                    createStateHandler,
                    domainEvents,
                    commandTypes,
                    commandHandlers,
                    eventHandlers,
                    eventSourcingHandlers,
                    eventBridgeHandlers,
                    stateUpcastingHandlers,
                    eventUpcastingHandlers,
                    generateGDPRKeyOnCreate,
                    shouldHandlePIIData);
        }
    }
}
