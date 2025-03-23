package org.elasticsoftware.authapi.domain.commands;

import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/**
 * Command to update a user's profile information.
 */
@CommandInfo(type = "UpdateUserProfile")
public record UpdateUserProfileCommand(
        @NotNull(message = "User ID is required")
        UUID userId,
        
        @NotNull(message = "Profile data is required")
        Map<String, String> profileData
) implements Command {
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}