package org.elasticsoftware.authapi.domain.commands;

import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Command to change a user's password.
 */
@CommandInfo(type = "ChangePassword")
public record ChangePasswordCommand(
        @NotNull(message = "User ID is required")
        UUID userId,
        
        @NotBlank(message = "Current password is required")
        String currentPassword,
        
        @NotBlank(message = "New password is required")
        @Size(min = 8, message = "New password must be at least 8 characters")
        String newPassword
) implements Command {
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}