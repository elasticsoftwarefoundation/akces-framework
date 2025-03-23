package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.AuthErrorEvent;

import java.util.UUID;

/**
 * Error event emitted when an invalid MFA device type is provided.
 */
@DomainEventInfo(type = "InvalidMFADeviceTypeError")
public record InvalidMFADeviceTypeErrorEvent(
        UUID deviceId,
        String deviceType
) implements AuthErrorEvent {
    
    @Override
    public String getMessage() {
        return "Invalid MFA device type: " + deviceType;
    }
    
    @Override
    public String getAggregateId() {
        return deviceId.toString();
    }
}