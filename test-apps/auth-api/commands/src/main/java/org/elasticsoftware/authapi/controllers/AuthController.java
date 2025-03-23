package org.elasticsoftware.authapi.controllers;

import org.elasticsoftware.akces.client.AkcesClient;
import org.elasticsoftware.authapi.domain.aggregates.AuthSessionState.DeviceInfo;
import org.elasticsoftware.authapi.domain.commands.LoginCommand;
import org.elasticsoftware.authapi.domain.commands.LogoutCommand;
import org.elasticsoftware.authapi.domain.commands.RefreshTokenCommand;
import org.elasticsoftware.authapi.domain.commands.RegisterUserCommand;
import org.elasticsoftware.authapi.domain.events.AuthTokenIssuedEvent;
import org.elasticsoftware.authapi.domain.events.AuthTokenRefreshedEvent;
import org.elasticsoftware.authapi.domain.events.AuthTokenRevokedEvent;
import org.elasticsoftware.authapi.domain.events.UserRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for authentication operations.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    
    private final AkcesClient akcesClient;
    
    @Autowired
    public AuthController(AkcesClient akcesClient) {
        this.akcesClient = akcesClient;
    }
    
    /**
     * Register a new user.
     */
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        UUID userId = UUID.randomUUID();
        
        // Create and send RegisterUserCommand
        RegisterUserCommand command = new RegisterUserCommand(
                userId,
                request.email,
                request.username,
                request.password,
                request.profileData
        );
        
        // Send command and get response
        UserRegisteredEvent event = akcesClient.sendAndWaitFor(
                command,
                UserRegisteredEvent.class,
                response -> response.userId().equals(userId)
        );
        
        // Return response with generated user ID
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponse(userId, "User registered successfully"));
    }
    
    /**
     * Authenticate a user.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            WebRequest webRequest
    ) {
        UUID commandId = UUID.randomUUID();
        
        // Create device info
        DeviceInfo deviceInfo = new DeviceInfo(
                request.deviceType,
                webRequest.getHeader("User-Agent"),
                request.deviceName
        );
        
        // Get client IP address
        String ipAddress = webRequest.getRemoteAddr();
        
        // Create and send LoginCommand
        LoginCommand command = new LoginCommand(
                commandId,
                request.username,
                request.password,
                deviceInfo,
                ipAddress,
                request.mfaCode
        );
        
        // Send command and get response
        AuthTokenIssuedEvent event = akcesClient.send(
                command,
                AuthTokenIssuedEvent.class,
                response -> true // We'll get the first AuthTokenIssuedEvent
        );
        
        // Return response with tokens
        return ResponseEntity.ok(new LoginResponse(
                event.accessToken(),
                event.refreshToken(),
                event.userId(),
                event.accessTokenExpiresAt(),
                event.refreshTokenExpiresAt()
        ));
    }
    
    /**
     * Refresh an authentication token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            WebRequest webRequest
    ) {
        // Create device info
        DeviceInfo deviceInfo = new DeviceInfo(
                request.deviceType,
                webRequest.getHeader("User-Agent"),
                request.deviceName
        );
        
        // Get client IP address
        String ipAddress = webRequest.getRemoteAddr();
        
        // Parse session and user IDs from refresh token
        // Note: In a real application, you would extract these from the token or from userDetails
        // For simplicity, we're accepting them in the request
        
        // Create and send RefreshTokenCommand
        RefreshTokenCommand command = new RefreshTokenCommand(
                request.sessionId,
                request.userId,
                request.refreshToken,
                deviceInfo,
                ipAddress
        );
        
        // Send command and get response
        AuthTokenRefreshedEvent event = akcesClient.sendAndWaitFor(
                command,
                AuthTokenRefreshedEvent.class,
                response -> response.sessionId().equals(request.sessionId)
        );
        
        // Return response with new tokens
        return ResponseEntity.ok(new LoginResponse(
                event.accessToken(),
                event.refreshToken(),
                event.userId(),
                event.accessTokenExpiresAt(),
                event.refreshTokenExpiresAt()
        ));
    }
    
    /**
     * End a user's session.
     */
    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(@Valid @RequestBody LogoutRequest request) {
        // Create and send LogoutCommand
        LogoutCommand command = new LogoutCommand(
                request.sessionId,
                request.userId,
                request.tokenId
        );
        
        // Send command and get response
        AuthTokenRevokedEvent event = akcesClient.sendAndWaitFor(
                command,
                AuthTokenRevokedEvent.class,
                response -> response.sessionId().equals(request.sessionId) &&
                        response.tokenId().equals(request.tokenId)
        );
        
        // Return success response
        return ResponseEntity.ok(new LogoutResponse("Logged out successfully"));
    }
    
    // Request/Response DTOs
    
    /**
     * Request DTO for user registration.
     */
    public record RegisterRequest(
            @NotBlank(message = "Email is required")
            String email,
            
            @NotBlank(message = "Username is required")
            @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
            String username,
            
            @NotBlank(message = "Password is required")
            @Size(min = 8, message = "Password must be at least 8 characters")
            String password,
            
            Map<String, String> profileData
    ) {}
    
    /**
     * Response DTO for user registration.
     */
    public record RegisterResponse(
            UUID userId,
            String message
    ) {}
    
    /**
     * Request DTO for login.
     */
    public record LoginRequest(
            @NotBlank(message = "Username is required")
            String username,
            
            @NotBlank(message = "Password is required")
            String password,
            
            String deviceType,
            
            String deviceName,
            
            String mfaCode
    ) {}
    
    /**
     * Response DTO for login.
     */
    public record LoginResponse(
            String accessToken,
            String refreshToken,
            UUID userId,
            Instant accessTokenExpiresAt,
            Instant refreshTokenExpiresAt
    ) {}
    
    /**
     * Request DTO for token refresh.
     */
    public record RefreshTokenRequest(
            @NotBlank(message = "Refresh token is required")
            String refreshToken,
            
            UUID sessionId,
            
            UUID userId,
            
            String deviceType,
            
            String deviceName
    ) {}
    
    /**
     * Request DTO for logout.
     */
    public record LogoutRequest(
            UUID sessionId,
            UUID userId,
            UUID tokenId
    ) {}
    
    /**
     * Response DTO for logout.
     */
    public record LogoutResponse(
            String message
    ) {}
}