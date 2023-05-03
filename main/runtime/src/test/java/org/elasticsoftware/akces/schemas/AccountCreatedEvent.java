package org.elasticsoftware.akces.schemas;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

@DomainEventInfo(type = "AccountCreatedEvent", version = 1)
public record AccountCreatedEvent(@NotNull String userId, @NotNull String lastName, @NotNull AccountTypeV1 type) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
