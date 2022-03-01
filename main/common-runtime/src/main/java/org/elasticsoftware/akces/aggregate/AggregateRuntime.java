package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.commands.CreateAggregateCommandHandlerFunction;
import org.elasticsoftware.akces.events.CreateAggregateEventSourcingHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

public abstract class AggregateRuntime {
    private final AggregateStateType<?> type;
    private final CreateAggregateCommandHandlerFunction<Command, DomainEvent> commandCreateHandler;
    private final CreateAggregateEventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler;
    private final Map<Class<?>,DomainEventType<?>> domainEvents;
    private final Map<String, List<CommandType<?>>> commandTypes;
    private final Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers;
    private final Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers;

    public AggregateRuntime(AggregateStateType<?> type,
                            CreateAggregateCommandHandlerFunction<Command, DomainEvent> commandCreateHandler,
                            CreateAggregateEventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler,
                            Map<Class<?>, DomainEventType<?>> domainEvents,
                            Map<String, List<CommandType<?>>> commandTypes,
                            Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers,
                            Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers) {
        this.type = type;
        this.commandCreateHandler = commandCreateHandler;
        this.createStateHandler = createStateHandler;
        this.domainEvents = domainEvents;
        this.commandTypes = commandTypes;
        this.commandHandlers = commandHandlers;
        this.eventSourcingHandlers = eventSourcingHandlers;
    }

    public void handleCommandRecord(CommandRecord commandRecord) {
        // determine command
        CommandType<?> commandType = commandTypes.getOrDefault(commandRecord.name(), emptyList()).stream()
                .filter(ct -> ct.version() == commandRecord.version())
                .findFirst().orElseThrow(RuntimeException::new); // TODO: replace with specific exception
        // TODO: need to raise an ErrorEvent in case of exception
        // find handler
        if(commandType.create()) {
            handleCreateCommand(commandType, commandRecord);
        } else {
            handleCommand(commandType, commandRecord);
        }
    }

    private void handleCreateCommand(CommandType<?> commandType, CommandRecord commandRecord) {
        // materialize command
        var command = materialize(commandRecord);
        // apply the command
        DomainEvent domainEvent = commandCreateHandler.apply(command);
        // create the state
        AggregateState state = createStateHandler.apply(domainEvent);
        // store the state, generation is 1 because it is the first record
        AggregateStateRecord stateRecord = new AggregateStateRecord(
                type.typeName(),
                type.version(),
                serialize(state),
                getEncoding(type),
                state.getAggregateId(),
                1L);
        // store the domain event
        DomainEventType<?> type = getDomainEventType(domainEvent.getClass());
        DomainEventRecord eventRecord = new DomainEventRecord(
                type.typeName(),
                type.version(),
                serialize(domainEvent),
                getEncoding(type),
                domainEvent.getAggregateId(),
                stateRecord.generation());

    }

    private void handleCommand(CommandType<?> commandType, CommandRecord commandRecord) {
        Command command = materialize(commandRecord);
        AggregateStateRecord currentStateRecord = getCurrentState(command.getAggregateId());
        AggregateState currentState = materialize(currentStateRecord);
        DomainEvent domainEvent = commandHandlers.get(commandType).apply(command, currentState);
        DomainEventType<?> domainEventType = getDomainEventType(domainEvent.getClass());
        AggregateState nextState = eventSourcingHandlers.get(domainEventType).apply(domainEvent, currentState);
        // store the state, increasing the generation by 1
        AggregateStateRecord nextStateRecord = new AggregateStateRecord(
                type.typeName(),
                type.version(),
                serialize(nextState),
                getEncoding(type),
                currentStateRecord.aggregateId(),
                currentStateRecord.generation()+1L);
        DomainEventRecord eventRecord = new DomainEventRecord(
                domainEventType.typeName(),
                domainEventType.version(),
                serialize(domainEvent),
                getEncoding(domainEventType),
                domainEvent.getAggregateId(),
                nextStateRecord.generation());
    }

    private DomainEventType<?> getDomainEventType(Class<?> domainEventClass) {
        return domainEvents.get(domainEventClass);
    }

    protected abstract Command materialize(CommandRecord commandRecord);

    protected abstract AggregateState materialize(AggregateStateRecord stateRecord);

    protected abstract byte[] serialize(AggregateState state);

    protected abstract byte[] serialize(DomainEvent domainEvent);

    protected abstract PayloadEncoding getEncoding(CommandType<?> type);

    protected abstract PayloadEncoding getEncoding(DomainEventType<?> type);

    protected abstract PayloadEncoding getEncoding(AggregateStateType<?> type);

    protected abstract AggregateStateRecord getCurrentState(String aggregateId);

    public abstract void start();
}
