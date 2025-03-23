package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.AuthErrorEvent;

import java.util.UUID;

/**
 * Error event emitted when an expired token is used.
 */
@DomainEventInfo(type = "TokenExpiredError")
public record TokenExpiredErrorEvent(
        UUID sessionId,
        UUID tokenId
) implements AuthErrorEvent {
    
    @Override
    public String getMessage() {
        return "Token has expired";
    }
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}