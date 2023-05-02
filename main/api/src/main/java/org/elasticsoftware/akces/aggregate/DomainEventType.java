package org.elasticsoftware.akces.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.elasticsoftware.akces.events.DomainEvent;

public record DomainEventType<T extends DomainEvent>(String typeName, int version, @JsonIgnore Class<T> typeClass, boolean create, boolean external, boolean error) {
}
