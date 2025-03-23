package org.elasticsoftware.authapi.domain.events;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when an authentication token is revoked.
 */
@DomainEventInfo(type = "AuthTokenRevoked")
public record AuthTokenRevokedEvent(
        UUID sessionId,
        UUID userId,
        UUID tokenId,
        Instant revokedAt
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}