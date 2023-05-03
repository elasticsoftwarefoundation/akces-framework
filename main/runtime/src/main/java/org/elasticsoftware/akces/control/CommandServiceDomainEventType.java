package org.elasticsoftware.akces.control;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.elasticsoftware.akces.events.DomainEvent;

public record CommandServiceDomainEventType<T extends DomainEvent>(
        String typeName,
        int version,
        boolean create,
        boolean external
) {
}
