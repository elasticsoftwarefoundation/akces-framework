package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.AuthErrorEvent;

import java.util.UUID;

/**
 * Error event emitted when invalid credentials are provided.
 */
@DomainEventInfo(type = "InvalidCredentialsError")
public record InvalidCredentialsErrorEvent(
        UUID userId
) implements AuthErrorEvent {
    
    @Override
    public String getMessage() {
        return "Invalid credentials";
    }
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}