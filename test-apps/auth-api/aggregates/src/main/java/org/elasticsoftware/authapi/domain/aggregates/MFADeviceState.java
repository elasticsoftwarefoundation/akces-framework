package org.elasticsoftware.authapi.domain.aggregates;

import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.annotations.PIIData;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents the state of a multi-factor authentication device.
 */
@AggregateStateInfo(type = "MFADevice")
public record MFADeviceState(
        UUID deviceId,
        UUID userId,
        MFADeviceType deviceType,
        @PIIData String deviceSecret,
        String deviceName,
        boolean verified,
        Instant lastUsed,
        Instant createdAt
) implements AggregateState {

    public MFADeviceState withVerified(boolean isVerified) {
        return new MFADeviceState(
                deviceId,
                userId,
                deviceType,
                deviceSecret,
                deviceName,
                isVerified,
                isVerified ? Instant.now() : lastUsed,
                createdAt
        );
    }
    
    public MFADeviceState withLastUsed() {
        return new MFADeviceState(
                deviceId,
                userId,
                deviceType,
                deviceSecret,
                deviceName,
                verified,
                Instant.now(),
                createdAt
        );
    }
    
    public MFADeviceState withUpdatedName(String newName) {
        return new MFADeviceState(
                deviceId,
                userId,
                deviceType,
                deviceSecret,
                newName,
                verified,
                lastUsed,
                createdAt
        );
    }
    
    @Override
    public String getAggregateId() {
        return deviceId.toString();
    }
}