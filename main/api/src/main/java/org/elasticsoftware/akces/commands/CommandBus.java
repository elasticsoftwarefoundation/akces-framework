package org.elasticsoftware.akces.commands;

import org.elasticsoftware.akces.events.DomainEvent;

import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public interface CommandBus {
    void send(Command command);

    CompletionStage<Stream<? extends DomainEvent>> sendWithResult(Command command);
}
