package org.elasticsoftware.authapi.domain.events;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.authapi.domain.aggregates.AuthSessionState.DeviceInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when an authentication token is issued.
 */
@DomainEventInfo(type = "AuthTokenIssued")
public record AuthTokenIssuedEvent(
        UUID userId,
        UUID sessionId,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        DeviceInfo deviceInfo,
        @PIIData String ipAddress,
        Instant issuedAt
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}