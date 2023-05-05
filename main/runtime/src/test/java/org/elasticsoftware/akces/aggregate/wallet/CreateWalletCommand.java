package org.elasticsoftware.akces.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

@CommandInfo(type = "CreateWallet", version = 1)
public record CreateWalletCommand(
        @AggregateIdentifier String id,
        String currency
) implements Command {
    @NotNull
    @Override
    public String getAggregateId() {
        return id();
    }
}
