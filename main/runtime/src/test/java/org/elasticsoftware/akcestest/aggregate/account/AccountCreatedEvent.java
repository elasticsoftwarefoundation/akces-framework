package org.elasticsoftware.akcestest.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;


@DomainEventInfo(type = "AccountCreated")
public record AccountCreatedEvent(
        @AggregateIdentifier @NotNull String userId,
        String country
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
