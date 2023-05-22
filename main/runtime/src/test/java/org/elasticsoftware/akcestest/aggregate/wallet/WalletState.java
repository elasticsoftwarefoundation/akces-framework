package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;

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

    public record Balance(String currency, BigDecimal amount, BigDecimal reservedAmount) {
        public Balance(@NotNull String currency) {
            this(currency, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        public Balance(@NotNull String currency, @NotNull BigDecimal amount) {
            this(currency, amount, BigDecimal.ZERO);
        }

        public BigDecimal getAvailableAmount() {
            return amount.subtract(reservedAmount);
        }
    }
}
