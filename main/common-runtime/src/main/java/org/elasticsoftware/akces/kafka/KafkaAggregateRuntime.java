package org.elasticsoftware.akces.kafka;

import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.commands.CreateAggregateCommandHandlerFunction;
import org.elasticsoftware.akces.events.CreateAggregateEventSourcingHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;

import java.util.List;
import java.util.Map;

public class KafkaAggregateRuntime extends AggregateRuntime {
    public KafkaAggregateRuntime(AggregateStateType<?> stateType,
                                 CreateAggregateCommandHandlerFunction<Command, DomainEvent> commandCreateHandler,
                                 CreateAggregateEventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler,
                                 Map<Class<?>, DomainEventType<?>> domainEvents,
                                 Map<String, List<CommandType<?>>> commandTypes,
                                 Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers,
                                 Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers) {
        super(stateType, commandCreateHandler, createStateHandler, domainEvents, commandTypes, commandHandlers, eventSourcingHandlers);
    }

    @Override
    protected Command materialize(CommandRecord commandRecord) {
        return null;
    }

    @Override
    protected AggregateState materialize(AggregateStateRecord stateRecord) {
        return null;
    }

    @Override
    protected byte[] serialize(AggregateState state) {
        return new byte[0];
    }

    @Override
    protected byte[] serialize(DomainEvent domainEvent) {
        return new byte[0];
    }

    @Override
    protected PayloadEncoding getEncoding(CommandType<?> type) {
        return PayloadEncoding.JSON;
    }

    @Override
    protected PayloadEncoding getEncoding(DomainEventType<?> type) {
        return PayloadEncoding.JSON;
    }

    @Override
    protected PayloadEncoding getEncoding(AggregateStateType<?> type) {
        return PayloadEncoding.JSON;
    }

    @Override
    protected AggregateStateRecord getCurrentState(String aggregateId) {
        return null;
    }

    @Override
    public void start() {
        // create the consumer and wait
    }
}
