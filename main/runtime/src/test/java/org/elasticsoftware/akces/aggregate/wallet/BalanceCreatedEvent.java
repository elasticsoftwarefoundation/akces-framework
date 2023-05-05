package org.elasticsoftware.akces.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

@DomainEventInfo(type = "BalanceCreated")
public record BalanceCreatedEvent(@AggregateIdentifier @NotNull String id, String currency) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}
