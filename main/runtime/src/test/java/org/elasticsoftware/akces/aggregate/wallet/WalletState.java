package org.elasticsoftware.akces.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.List;

@AggregateStateInfo(type = "Wallet", version = 1)
public record WalletState(
        @AggregateIdentifier @NotNull String id,
        List<Balance> balances
) implements AggregateState {
    @Override
    public String getAggregateId() {
        return id();
    }

    public record Balance(String currency, BigDecimal amount) {
    }
}
