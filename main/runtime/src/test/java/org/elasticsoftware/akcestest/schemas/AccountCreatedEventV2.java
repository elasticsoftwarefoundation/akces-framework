package org.elasticsoftware.akcestest.schemas;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

@DomainEventInfo(type = "AccountCreatedEvent", version = 2)
public record AccountCreatedEventV2(
        @NotNull String userId,
        @NotNull String lastName,
        @NotNull AccountTypeV2 type,
        String firstName,
        String country
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
