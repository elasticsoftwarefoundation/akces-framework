package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;

import javax.annotation.Nonnull;

@DomainEventInfo(type = "InvalidAmountError")
public record InvalidAmountErrorEvent(String walletId) implements ErrorEvent {
    @Override
    public @Nonnull String getAggregateId() {
        return walletId();
    }
}
