package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

@DomainEventInfo(type = "BuyOrderRejected", version = 1)
public record BuyOrderRejectedEvent(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String orderId,
        @NotNull String clientReference
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
