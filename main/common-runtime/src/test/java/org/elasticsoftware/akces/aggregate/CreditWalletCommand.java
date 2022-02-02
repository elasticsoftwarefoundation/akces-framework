package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.commands.Command;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

public record CreditWalletCommand(
        @AggregateIdentifier String id,
        String currency,
        BigDecimal amount
) implements Command {
    @Nonnull
    @Override
    public String getAggregateId() {
        return id();
    }
}
