package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

public record WalletCreditedEvent(
        @AggregateIdentifier String id,
        BigDecimal amount,
        BigDecimal balance
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}
