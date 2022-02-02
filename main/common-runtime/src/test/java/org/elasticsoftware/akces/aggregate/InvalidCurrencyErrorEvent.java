package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.events.ErrorEvent;

import javax.annotation.Nonnull;

public record InvalidCurrencyErrorEvent(String walletId) implements ErrorEvent {
    @Nonnull
    @Override
    public String getAggregateId() {
        return walletId();
    }
}
