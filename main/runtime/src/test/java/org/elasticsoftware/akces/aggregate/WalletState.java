package org.elasticsoftware.akces.aggregate;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

@AggregateStateInfo(type = "Wallet", version = 1)
public record WalletState(
        @AggregateIdentifier @NotNull String id,
        String currency,
        BigDecimal balance
) implements AggregateState {
    @Override
    public String getAggregateId() {
        return id();
    }
}
