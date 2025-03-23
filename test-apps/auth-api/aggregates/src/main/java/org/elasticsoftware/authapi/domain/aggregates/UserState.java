package org.elasticsoftware.authapi.domain.aggregates;

import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.annotations.PIIData;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents the state of a User Aggregate.
 */
@AggregateStateInfo(type = "User")
public record UserState(
        UUID userId,
        @PIIData String email,
        String username,
        String passwordHash,
        String salt,
        UserStatus status,
        Map<String, String> profileData,
        SecuritySettings securitySettings,
        boolean mfaEnabled,
        Instant createdAt,
        Instant lastModifiedAt
) implements AggregateState {

    public UserState {
        if (profileData == null) {
            profileData = Collections.emptyMap();
        } else {
            // Create a defensive copy
            profileData = Collections.unmodifiableMap(new HashMap<>(profileData));
        }
        
        if (securitySettings == null) {
            securitySettings = new SecuritySettings(false, false);
        }
    }
    
    public UserState withUpdatedProfile(Map<String, String> newProfileData) {
        return new UserState(
                userId,
                email,
                username,
                passwordHash,
                salt,
                status,
                newProfileData,
                securitySettings,
                mfaEnabled,
                createdAt,
                Instant.now()
        );
    }
    
    public UserState withUpdatedPassword(String newPasswordHash, String newSalt) {
        return new UserState(
                userId,
                email,
                username,
                newPasswordHash,
                newSalt,
                status,
                profileData,
                securitySettings,
                mfaEnabled,
                createdAt,
                Instant.now()
        );
    }
    
    public UserState withUpdatedStatus(UserStatus newStatus) {
        return new UserState(
                userId,
                email,
                username,
                passwordHash,
                salt,
                newStatus,
                profileData,
                securitySettings,
                mfaEnabled,
                createdAt,
                Instant.now()
        );
    }
    
    public UserState withMfaEnabled(boolean enabled) {
        return new UserState(
                userId,
                email,
                username,
                passwordHash,
                salt,
                status,
                profileData,
                securitySettings,
                enabled,
                createdAt,
                Instant.now()
        );
    }
    
    public UserState withSecuritySettings(SecuritySettings newSecuritySettings) {
        return new UserState(
                userId,
                email,
                username,
                passwordHash,
                salt,
                status,
                profileData,
                newSecuritySettings,
                mfaEnabled,
                createdAt,
                Instant.now()
        );
    }
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}