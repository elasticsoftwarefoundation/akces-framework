package org.elasticsoftware.akces.aggregate;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

@DomainEventInfo(type = "AccountCreated")
public record InvalidAccountCreatedEvent(String lastName, String currency) implements DomainEvent {
        @Override
        public String getAggregateId() {
            return null;
        }
    }
