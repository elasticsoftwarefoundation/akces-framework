package org.elasticsoftware.authapi.domain.events;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event emitted when a user's profile is updated.
 */
@DomainEventInfo(type = "UserProfileUpdated")
public record UserProfileUpdatedEvent(
        UUID userId,
        Map<String, String> profileData,
        Instant updatedAt
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}