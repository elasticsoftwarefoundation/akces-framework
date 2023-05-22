package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

@DomainEventInfo(type = "BuyOrderCreated", version = 1)
public record BuyOrderCreatedEvent(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String orderId,
        @NotNull FxMarket market,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal limitPrice,
        @NotNull String clientReference
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return orderId();
    }
}