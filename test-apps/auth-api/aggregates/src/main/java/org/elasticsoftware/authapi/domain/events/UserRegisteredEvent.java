package org.elasticsoftware.authapi.domain.events;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.authapi.domain.aggregates.UserStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event emitted when a user is successfully registered.
 */
@DomainEventInfo(type = "UserRegistered")
public record UserRegisteredEvent(
        UUID userId,
        @PIIData String email,
        String username,
        String passwordHash,
        String salt,
        UserStatus status,
        Map<String, String> profileData,
        Instant createdAt
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}