package org.elasticsoftware.akces.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

@DomainEventInfo(type = "WalletCredited")
public record WalletCreditedEvent(
        @AggregateIdentifier @NotNull String id,
        BigDecimal amount,
        BigDecimal balance
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}
