package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import java.math.BigDecimal;

@CommandInfo(type = "ReserveAmount", version = 1)
public record ReserveAmountCommand(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String currency,
        @NotNull BigDecimal amount,
        @NotNull String referenceId
) implements Command {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
