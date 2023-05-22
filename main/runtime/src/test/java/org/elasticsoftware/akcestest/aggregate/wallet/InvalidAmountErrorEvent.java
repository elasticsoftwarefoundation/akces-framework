package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;

import javax.annotation.Nonnull;

@DomainEventInfo(type = "InvalidAmountError")
public record InvalidAmountErrorEvent(
        @NotNull @AggregateIdentifier String walletId,
        @NotNull String currency,
        String referenceId
) implements ErrorEvent {
    public InvalidAmountErrorEvent(@NotNull String walletId, @NotNull String currency) {
        this(walletId, currency, null);
    }

    @Override
    public @Nonnull String getAggregateId() {
        return walletId();
    }
}
