package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventHandlerFunction;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.protocol.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    public AggregateRuntimeBase(AggregateStateType<?> type,
                                Class<? extends Aggregate> aggregateClass,
                                CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler,
                                EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler,
                                EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler,
                                Map<Class<?>, DomainEventType<?>> domainEvents,
                                Map<String, List<CommandType<?>>> commandTypes,
                                Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers,
                                Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers,
                                Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers) {
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
        CommandType<?> commandType = commandTypes.getOrDefault(commandRecord.name(), emptyList()).stream()
                .filter(ct -> ct.version() == commandRecord.version())
                .findFirst().orElseThrow(RuntimeException::new); // TODO: replace with specific exception
        // TODO: need to raise an ErrorEvent in case of exception
        // find handler
        if(commandType.create()) {
            handleCreateCommand(commandType, commandRecord, protocolRecordConsumer);
        } else {
            handleCommand(commandType, commandRecord, protocolRecordConsumer, stateRecordSupplier);
        }
    }

    private void handleCreateCommand(CommandType<?> commandType,
                                     CommandRecord commandRecord,
                                     Consumer<ProtocolRecord> protocolRecordConsumer) throws IOException {
        // materialize command
        Command command = materialize(commandType, commandRecord);
        // apply the command
        DomainEvent domainEvent = commandCreateHandler.apply(command, null);
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
    }

    private void handleCommand(CommandType<?> commandType,
                               CommandRecord commandRecord,
                               Consumer<ProtocolRecord> protocolRecordConsumer,
                               Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        Command command = materialize(commandType, commandRecord);
        AggregateStateRecord currentStateRecord = stateRecordSupplier.get();
        AggregateState currentState = materialize(currentStateRecord);
        DomainEvent domainEvent = commandHandlers.get(commandType).apply(command, currentState);
        DomainEventType<?> domainEventType = getDomainEventType(domainEvent.getClass());
        AggregateState nextState = eventSourcingHandlers.get(domainEventType).apply(domainEvent, currentState);
        // store the state, increasing the generation by 1
        AggregateStateRecord nextStateRecord = new AggregateStateRecord(
                currentStateRecord.tenantId(),
                type.typeName(),
                type.version(),
                serialize(nextState),
                getEncoding(type),
                currentStateRecord.aggregateId(),
                commandRecord.correlationId(),
                currentStateRecord.generation()+1L);
        protocolRecordConsumer.accept(nextStateRecord);
        DomainEventRecord eventRecord = new DomainEventRecord(
                currentStateRecord.tenantId(),
                domainEventType.typeName(),
                domainEventType.version(),
                serialize(domainEvent),
                getEncoding(domainEventType),
                domainEvent.getAggregateId(),
                commandRecord.correlationId(),
                nextStateRecord.generation());
        protocolRecordConsumer.accept(eventRecord);
    }

    private void handleCreateEvent(DomainEventType<?> eventType,
                                   DomainEventRecord domainEventRecord,
                                   Consumer<ProtocolRecord> protocolRecordConsumer) throws IOException {
        // materialize the event
        DomainEvent externalEvent = materialize(eventType, domainEventRecord);
        // apply the event
        DomainEvent domainEvent = eventCreateHandler.apply(externalEvent, null);
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
    }

    private void handleEvent(DomainEventType<?> eventType,
                             DomainEventRecord domainEventRecord,
                             Consumer<ProtocolRecord> protocolRecordConsumer,
                             Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        // materialize the event
        DomainEvent externalEvent = materialize(eventType, domainEventRecord);
        AggregateStateRecord currentStateRecord = stateRecordSupplier.get();
        AggregateState currentState = materialize(currentStateRecord);
        DomainEvent domainEvent = eventHandlers.get(eventType).apply(externalEvent, currentState);
        DomainEventType<?> domainEventType = getDomainEventType(domainEvent.getClass());
        AggregateState nextState = eventSourcingHandlers.get(domainEventType).apply(domainEvent, currentState);
        // store the state, increasing the generation by 1
        AggregateStateRecord nextStateRecord = new AggregateStateRecord(
                currentStateRecord.tenantId(),
                type.typeName(),
                type.version(),
                serialize(nextState),
                getEncoding(type),
                currentStateRecord.aggregateId(),
                domainEventRecord.correlationId(),
                currentStateRecord.generation()+1L);
        protocolRecordConsumer.accept(nextStateRecord);
        DomainEventRecord eventRecord = new DomainEventRecord(
                currentStateRecord.tenantId(),
                domainEventType.typeName(),
                domainEventType.version(),
                serialize(domainEvent),
                getEncoding(domainEventType),
                domainEvent.getAggregateId(),
                domainEventRecord.correlationId(),
                nextStateRecord.generation());
        protocolRecordConsumer.accept(eventRecord);
    }

    @Override
    public void handleExternalDomainEventRecord(DomainEventRecord eventRecord, Consumer<ProtocolRecord> protocolRecordConsumer, Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException {
        // determine the type to use for the external event
        // because it is an external event we need to find the highest version that is smaller than the eventRecord version
        DomainEventType<?> domainEventType = domainEvents.entrySet().stream()
                .filter(entry -> entry.getValue().external())
                .filter(entry -> entry.getValue().typeName().equals(eventRecord.name()))
                .filter(entry -> entry.getValue().version() <= eventRecord.version())
                .max(Comparator.comparingInt(entry -> entry.getValue().version()))
                .orElseThrow(RuntimeException::new).getValue(); // TODO replace with specific exception
        if(domainEventType.create()) {
            handleCreateEvent(domainEventType, eventRecord, protocolRecordConsumer);
        } else {
            handleEvent(domainEventType, eventRecord, protocolRecordConsumer, stateRecordSupplier);
        }
    }

    @Override
    public Collection<DomainEventType<?>> getDomainEventTypes() {
        return this.domainEvents.values();
    }

    @Override
    public Collection<DomainEventType<?>> getExternalDomainEventTypes() {
        return this.domainEvents.values().stream().filter(DomainEventType::external).toList();
    }

    @Override
    public Collection<CommandType<?>> getCommandTypes() {
        return this.commandTypes.values().stream().flatMap(Collection::stream).toList();
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

    protected abstract DomainEvent materialize(DomainEventType<?> domainEventType, DomainEventRecord eventRecord) throws IOException;

    protected abstract AggregateState materialize(AggregateStateRecord stateRecord) throws IOException;

    protected abstract byte[] serialize(AggregateState state) throws IOException;

    protected abstract byte[] serialize(DomainEvent domainEvent) throws IOException;

    protected abstract PayloadEncoding getEncoding(CommandType<?> type);

    protected abstract PayloadEncoding getEncoding(DomainEventType<?> type);

    protected abstract PayloadEncoding getEncoding(AggregateStateType<?> type);

    protected AggregateStateType<?> getAggregateStateType(AggregateStateRecord record) {
        // TODO: add support for more state versions
        return type;
    }
}
