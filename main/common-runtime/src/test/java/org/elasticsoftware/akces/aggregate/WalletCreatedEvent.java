package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

@DomainEventInfo(type = "WalletCreated")
public record WalletCreatedEvent(
        @AggregateIdentifier String id,
        String currency,
        BigDecimal balance
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}
