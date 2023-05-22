package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;

@DomainEventInfo(type = "AmountReserved", version = 1)
public record AmountReservedEvent(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String currency,
        @NotNull BigDecimal amount,
        @NotNull String referenceId
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
