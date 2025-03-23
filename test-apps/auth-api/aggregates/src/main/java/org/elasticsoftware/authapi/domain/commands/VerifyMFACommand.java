package org.elasticsoftware.authapi.domain.commands;

import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Command to verify a multi-factor authentication code.
 */
@CommandInfo(type = "VerifyMFA")
public record VerifyMFACommand(
        @NotNull(message = "Device ID is required")
        UUID deviceId,
        
        @NotNull(message = "User ID is required")
        UUID userId,
        
        @NotBlank(message = "Verification code is required")
        String code
) implements Command {
    
    @Override
    public String getAggregateId() {
        return deviceId.toString();
    }
}