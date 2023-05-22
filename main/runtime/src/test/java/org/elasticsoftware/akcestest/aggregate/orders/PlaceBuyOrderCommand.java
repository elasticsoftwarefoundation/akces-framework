package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import java.math.BigDecimal;

@CommandInfo(type = "PlaceBuyOrder", version = 1)
public record PlaceBuyOrderCommand(
        @NotNull @AggregateIdentifier String userId,
        @NotNull FxMarket market,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal limitPrice,
        @NotNull String clientReference
) implements Command {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
