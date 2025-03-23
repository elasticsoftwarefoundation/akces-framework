package org.elasticsoftware.authapi.domain.commands;

import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Command to end a user's session.
 */
@CommandInfo(type = "Logout")
public record LogoutCommand(
        @NotNull(message = "Session ID is required")
        UUID sessionId,
        
        @NotNull(message = "User ID is required")
        UUID userId,
        
        @NotNull(message = "Token ID is required")
        UUID tokenId
) implements Command {
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}