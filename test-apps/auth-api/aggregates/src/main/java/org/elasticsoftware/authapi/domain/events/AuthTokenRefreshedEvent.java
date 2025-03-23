package org.elasticsoftware.authapi.domain.events;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.authapi.domain.aggregates.AuthSessionState.DeviceInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when an authentication token is refreshed.
 */
@DomainEventInfo(type = "AuthTokenRefreshed")
public record AuthTokenRefreshedEvent(
        UUID userId,
        UUID sessionId,
        UUID previousTokenId,
        UUID newTokenId,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        DeviceInfo deviceInfo,
        @PIIData String ipAddress,
        Instant refreshedAt
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}