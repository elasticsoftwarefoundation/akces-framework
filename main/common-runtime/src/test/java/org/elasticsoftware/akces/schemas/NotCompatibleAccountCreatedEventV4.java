package org.elasticsoftware.akces.schemas;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

@DomainEventInfo(type = "AccountCreatedEvent", version = 4)
public record NotCompatibleAccountCreatedEventV4(
        @NotNull String userId,
        @NotNull String lastName,
        String firstName,
        String country,
        String city
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
