package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.AuthErrorEvent;

import java.util.UUID;

/**
 * Error event emitted when an operation is attempted on an inactive account.
 */
@DomainEventInfo(type = "AccountNotActiveError")
public record AccountNotActiveErrorEvent(
        UUID userId
) implements AuthErrorEvent {
    
    @Override
    public String getMessage() {
        return "Account is not active";
    }
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}