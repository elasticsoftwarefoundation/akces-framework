package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.AuthErrorEvent;

import java.util.UUID;

/**
 * Error event emitted when an invalid MFA code is provided.
 */
@DomainEventInfo(type = "InvalidMFACodeError")
public record InvalidMFACodeErrorEvent(
        UUID deviceId
) implements AuthErrorEvent {
    
    @Override
    public String getMessage() {
        return "Invalid MFA code";
    }
    
    @Override
    public String getAggregateId() {
        return deviceId.toString();
    }
}