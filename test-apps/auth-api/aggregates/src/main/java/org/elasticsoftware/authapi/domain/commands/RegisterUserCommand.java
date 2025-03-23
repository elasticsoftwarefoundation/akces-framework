package org.elasticsoftware.authapi.domain.commands;

import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.annotations.PIIData;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/**
 * Command to register a new user.
 */
@CommandInfo(type = "RegisterUser")
public record RegisterUserCommand(
        UUID userId,
        
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @PIIData
        String email,
        
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String username,
        
        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,
        
        Map<String, String> profileData
) implements Command {
    
    @Override
    public String getAggregateId() {
        return userId.toString();
    }
}