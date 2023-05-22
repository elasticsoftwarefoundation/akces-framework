package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

@DomainEventInfo(type = "BuyOrderPlaced", version = 1)
public record BuyOrderPlacedEvent(
        @NotNull @AggregateIdentifier String userId,
        String orderId,
        FxMarket market,
        BigDecimal quantity,
        BigDecimal limitPrice
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return orderId();
    }
}
