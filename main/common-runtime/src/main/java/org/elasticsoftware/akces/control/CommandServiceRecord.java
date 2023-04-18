package org.elasticsoftware.akces.control;

import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;

import java.util.List;

public record CommandServiceRecord(
        String aggregateName,
        String commandTopic,
        List<CommandServiceCommandType> supportedCommands,
        List<CommandServiceDomainEventType> producedEvents,
        List<CommandServiceDomainEventType> consumedEvents
) implements AkcesControlRecord {
}
