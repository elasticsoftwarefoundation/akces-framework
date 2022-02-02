package org.elasticsoftware.akces.aggregate;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.commands.Command;

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
