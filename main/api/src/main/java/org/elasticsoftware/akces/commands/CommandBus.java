package org.elasticsoftware.akces.commands;

import org.elasticsoftware.akces.events.DomainEvent;

import java.io.IOException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public interface CommandBus {
    void send(Command command) throws IOException;

    default CompletionStage<Stream<? extends DomainEvent>> sendWithResult(Command command) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
