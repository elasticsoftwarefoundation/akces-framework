package org.elasticsoftware.akces.commands;

public interface CommandBus {
    void send(Command command);
}
