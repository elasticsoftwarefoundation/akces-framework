package org.elasticsoftware.authapi.domain.events.errors;

import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.authapi.domain.aggregates.MFADeviceType;

import java.util.UUID;

/**
 * Error event emitted when an unsupported MFA device type is used.
 */
@DomainEventInfo(type = "UnsupportedMFADeviceTypeError")
public record UnsupportedMFADeviceTypeErrorEvent(
        UUID deviceId,
        MFADeviceType deviceType
) implements AuthErrorEvent {
    
    @Override
    public String getMessage() {
        return "Unsupported MFA device type: " + deviceType;
    }
    
    @Override
    public String getAggregateId() {
        return deviceId.toString();
    }
}