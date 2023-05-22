package org.elasticsoftware.akcestest.aggregate.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

@DomainEventInfo(type = "UserOrderProcessesCreated", version = 1)
public record UserOrderProcessesCreatedEvent(@NotNull @AggregateIdentifier String userId) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
