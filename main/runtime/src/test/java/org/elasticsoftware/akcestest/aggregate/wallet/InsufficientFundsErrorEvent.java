package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

@DomainEventInfo(type = "InsufficientFundsError")
public record InsufficientFundsErrorEvent(
        @NotNull @AggregateIdentifier String walletId,
        @NotNull String currency,
        @NotNull BigDecimal availableAmount,
        @NotNull BigDecimal requestedAmount,
        @Nullable String referenceId
) implements ErrorEvent {
    @Override
    public @Nonnull String getAggregateId() {
        return walletId();
    }
}
