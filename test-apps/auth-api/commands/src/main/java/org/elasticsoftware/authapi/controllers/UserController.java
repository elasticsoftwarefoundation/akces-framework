package org.elasticsoftware.authapi.controllers;

import org.elasticsoftware.akces.client.AkcesClient;
import org.elasticsoftware.authapi.domain.commands.ChangePasswordCommand;
import org.elasticsoftware.authapi.domain.commands.UpdateUserProfileCommand;
import org.elasticsoftware.authapi.domain.events.PasswordChangedEvent;
import org.elasticsoftware.authapi.domain.events.UserProfileUpdatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for user operations.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    private final AkcesClient akcesClient;
    
    @Autowired
    public UserController(AkcesClient akcesClient) {
        this.akcesClient = akcesClient;
    }
    
    /**
     * Update a user's profile.
     */
    @PutMapping("/profile/{userId}")
    @PreAuthorize("authentication.principal.uuid == #userId.toString() or hasRole('ADMIN')")
    public ResponseEntity<ProfileResponse> updateProfile(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        // Create and send UpdateUserProfileCommand
        UpdateUserProfileCommand command = new UpdateUserProfileCommand(
                userId,
                request.profileData
        );
        
        // Send command and get response
        UserProfileUpdatedEvent event = akcesClient.sendAndWaitFor(
                command,
                UserProfileUpdatedEvent.class,
                response -> response.userId().equals(userId)
        );
        
        // Return success response
        return ResponseEntity.ok(new ProfileResponse("Profile updated successfully"));
    }
    
    /**
     * Change a user's password.
     */
    @PutMapping("/password/{userId}")
    @PreAuthorize("authentication.principal.uuid == #userId.toString() or hasRole('ADMIN')")
    public ResponseEntity<PasswordResponse> changePassword(
            @PathVariable UUID userId,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        // Create and send ChangePasswordCommand
        ChangePasswordCommand command = new ChangePasswordCommand(
                userId,
                request.currentPassword,
                request.newPassword
        );
        
        // Send command and get response
        PasswordChangedEvent event = akcesClient.sendAndWaitFor(
                command,
                PasswordChangedEvent.class,
                response -> response.userId().equals(userId)
        );
        
        // Return success response
        return ResponseEntity.ok(new PasswordResponse("Password changed successfully"));
    }
    
    // Request/Response DTOs
    
    /**
     * Request DTO for updating a user's profile.
     */
    public record UpdateProfileRequest(
            Map<String, String> profileData
    ) {}
    
    /**
     * Response DTO for profile update.
     */
    public record ProfileResponse(
            String message
    ) {}
    
    /**
     * Request DTO for changing a user's password.
     */
    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required")
            String currentPassword,
            
            @NotBlank(message = "New password is required")
            @Size(min = 8, message = "New password must be at least 8 characters")
            String newPassword
    ) {}
    
    /**
     * Response DTO for password change.
     */
    public record PasswordResponse(
            String message
    ) {}
}