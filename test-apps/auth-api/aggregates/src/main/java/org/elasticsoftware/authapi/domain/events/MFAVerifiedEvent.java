package org.elasticsoftware.authapi.domain.events;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when MFA verification is successful.
 */
@DomainEventInfo(type = "MFAVerified")
public record MFAVerifiedEvent(
        UUID deviceId,
        UUID userId,
        Instant verifiedAt
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return deviceId.toString();
    }
}