package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.events.DomainEvent;

public record DomainEventType<T extends DomainEvent>(String typeName, int version, Class<T> typeClass, boolean create, boolean external) {
}
