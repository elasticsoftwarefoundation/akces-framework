package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.AuthErrorEvent;

import java.util.UUID;

/**
 * Error event emitted when an operation is attempted on an expired session.
 */
@DomainEventInfo(type = "SessionExpiredError")
public record SessionExpiredErrorEvent(
        UUID sessionId
) implements AuthErrorEvent {
    
    @Override
    public String getMessage() {
        return "Session has expired";
    }
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}