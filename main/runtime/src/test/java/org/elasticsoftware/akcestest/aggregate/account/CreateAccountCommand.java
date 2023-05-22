package org.elasticsoftware.akcestest.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

@CommandInfo(type = "CreateAccount")
public record CreateAccountCommand(@AggregateIdentifier @NotNull String userId, @NotNull String country) implements Command {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
