package org.elasticsoftware.akces.schemas;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import org.elasticsoftware.akces.events.DomainEvent;

public record AccountCreatedEventV2(
        @NotNull String userId,
        @NotNull String lastName,
        @NotNull AccountTypeV2 type,
        String firstName
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
