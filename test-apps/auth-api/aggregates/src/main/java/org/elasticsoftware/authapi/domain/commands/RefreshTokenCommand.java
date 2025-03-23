package org.elasticsoftware.authapi.domain.commands;

import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.authapi.domain.aggregates.AuthSessionState.DeviceInfo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Command to refresh an authentication token.
 */
@CommandInfo(type = "RefreshToken")
public record RefreshTokenCommand(
        @NotNull(message = "Session ID is required")
        UUID sessionId,
        
        @NotNull(message = "User ID is required")
        UUID userId,
        
        @NotBlank(message = "Refresh token is required")
        String refreshToken,
        
        @NotNull(message = "Device info is required")
        DeviceInfo deviceInfo,
        
        @PIIData
        String ipAddress
) implements Command {
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}