package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;

import javax.annotation.Nonnull;

@DomainEventInfo(type = "InvalidCurrencyError")
public record InvalidCurrencyErrorEvent(
        @NotNull @AggregateIdentifier String walletId,
        @NotNull String currency,
        @Nullable String referenceId
) implements ErrorEvent {
    public InvalidCurrencyErrorEvent(@NotNull String walletId, @NotNull String currency) {
        this(walletId, currency, null);
    }

    @Nonnull
    @Override
    public String getAggregateId() {
        return walletId();
    }
}
