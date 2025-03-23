package org.elasticsoftware.authapi.domain.events;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when a user's password is changed.
 */
@DomainEventInfo(type = "PasswordChanged")
public record PasswordChangedEvent(
        UUID userId,
        String passwordHash,
        String salt,
        Instant changedAt
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}