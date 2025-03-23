package org.elasticsoftware.authapi.domain.events;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when MFA setup is completed.
 */
@DomainEventInfo(type = "MFASetupCompleted")
public record MFASetupCompletedEvent(
        UUID deviceId,
        UUID userId,
        String deviceType,
        String deviceName,
        @PIIData String deviceSecret,
        Instant setupAt
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return deviceId.toString();
    }
}