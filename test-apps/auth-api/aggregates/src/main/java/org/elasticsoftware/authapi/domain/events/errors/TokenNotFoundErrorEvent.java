package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.AuthErrorEvent;

import java.util.UUID;

/**
 * Error event emitted when a token is not found.
 */
@DomainEventInfo(type = "TokenNotFoundError")
public record TokenNotFoundErrorEvent(
        UUID sessionId,
        UUID tokenId
) implements AuthErrorEvent {
    
    @Override
    public String getMessage() {
        return "Token not found";
    }
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}