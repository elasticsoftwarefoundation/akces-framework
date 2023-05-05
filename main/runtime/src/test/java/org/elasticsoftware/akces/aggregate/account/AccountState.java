package org.elasticsoftware.akces.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;

@AggregateStateInfo(type = "Account", version = 1)
public record AccountState(@NotNull String userId,@NotNull String country) implements AggregateState {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
