package org.elasticsoftware.authapi.domain.events;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.authapi.domain.aggregates.AuthSessionState.DeviceInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when a user successfully logs in.
 */
@DomainEventInfo(type = "UserLoggedIn")
public record UserLoggedInEvent(
        UUID userId,
        DeviceInfo deviceInfo,
        @PIIData String ipAddress,
        Instant loginTime
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}