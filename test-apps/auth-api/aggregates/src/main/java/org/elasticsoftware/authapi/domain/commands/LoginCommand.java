package org.elasticsoftware.authapi.domain.commands;

import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.authapi.domain.aggregates.AuthSessionState.DeviceInfo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Command to authenticate a user.
 */
@CommandInfo(type = "Login")
public record LoginCommand(
        UUID commandId,
        
        @NotBlank(message = "Username is required")
        String username,
        
        @NotBlank(message = "Password is required")
        String password,
        
        @NotNull(message = "Device info is required")
        DeviceInfo deviceInfo,
        
        @PIIData
        String ipAddress,
        
        String mfaCode
) implements Command {
    
    @Override
    public String getAggregateId() {
        // Login doesn't target a specific aggregate until the User is found
        return commandId.toString();
    }
}