package org.elasticsoftware.authapi.domain.commands;

import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Command to set up multi-factor authentication.
 */
@CommandInfo(type = "SetupMFA")
public record SetupMFACommand(
        @NotNull(message = "User ID is required")
        UUID userId,
        
        @NotNull(message = "Device ID is required")
        UUID deviceId,
        
        @NotBlank(message = "Device type is required")
        String deviceType,
        
        String deviceName
) implements Command {
    
    @Override
    public String getAggregateId() {
        return deviceId.toString();
    }
}