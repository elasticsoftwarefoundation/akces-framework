package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.AuthErrorEvent;

import java.util.UUID;

/**
 * Error event emitted when an invalid token is provided.
 */
@DomainEventInfo(type = "InvalidTokenError")
public record InvalidTokenErrorEvent(
        UUID sessionId
) implements AuthErrorEvent {
    
    @Override
    public String getMessage() {
        return "Invalid token";
    }
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}