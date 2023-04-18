package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.control.AkcesRegistry;
import org.elasticsoftware.akces.events.DomainEvent;

import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public class DefaultCommandBus extends CommandBusHolder implements CommandBus {
    private final AggregateRuntime aggregateRuntime;
    private final AkcesRegistry ackesRegistry;

    public DefaultCommandBus(AggregateRuntime aggregateRuntime,
                             AkcesRegistry ackesRegistry) {
        this.aggregateRuntime = aggregateRuntime;
        this.ackesRegistry = ackesRegistry;
        CommandBusHolder.commandBusMap.put(aggregateRuntime.getAggregateClass(), this);
    }

    @Override
    public void send(Command command) {
        // we need to resolve the command type
        CommandType<?> commandType = ackesRegistry.resolveType(command.getClass());
        if(commandType != null) {
            // now we need to find the topic
            String topic = ackesRegistry.resolveTopic(commandType);
            // and send the command to the topic

        }
    }

    @Override
    public CompletionStage<Stream<? extends DomainEvent>> sendWithResult(Command command) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
