package org.elasticsoftware.authapi.query.controllers;

import org.elasticsoftware.akces.query.models.QueryModels;
import org.elasticsoftware.authapi.query.models.UserProfileQueryModel.UserProfileState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for user profile operations.
 */
@RestController
@RequestMapping("/api/users")
public class UserProfileController {
    
    private final QueryModels queryModels;
    
    @Autowired
    public UserProfileController(QueryModels queryModels) {
        this.queryModels = queryModels;
    }
    
    /**
     * Get a user's profile.
     */
    @GetMapping("/profile/{userId}")
    @PreAuthorize("authentication.principal.uuid == #userId.toString() or hasRole('ADMIN')")
    public ResponseEntity<UserProfileResponse> getUserProfile(@PathVariable UUID userId) {
        // Get user profile from query model
        UserProfileState state = queryModels.get(UserProfileState.class, userId);
        
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        
        // Convert to response DTO
        UserProfileResponse response = new UserProfileResponse(
                state.userId(),
                state.email(),
                state.username(),
                state.status().name(),
                state.profileData(),
                state.mfaEnabled(),
                state.createdAt(),
                state.lastModifiedAt()
        );
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * DTO for user profile response.
     */
    public record UserProfileResponse(
            UUID userId,
            String email,
            String username,
            String status,
            Map<String, String> profileData,
            boolean mfaEnabled,
            java.time.Instant createdAt,
            java.time.Instant lastModifiedAt
    ) {
    }
}