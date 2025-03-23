package org.elasticsoftware.authapi.query.models;

import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.annotations.QueryModelEventHandler;
import org.elasticsoftware.akces.annotations.QueryModelInfo;
import org.elasticsoftware.akces.annotations.QueryModelStateInfo;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelState;
import org.elasticsoftware.authapi.domain.aggregates.AuthSessionState.DeviceInfo;
import org.elasticsoftware.authapi.domain.events.*;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Query model for security audit trail.
 */
@QueryModelInfo(value = "SecurityAuditQueryModel", indexName = "SecurityAudits")
public class SecurityAuditQueryModel implements QueryModel<SecurityAuditQueryModel.SecurityAuditState> {
    
    @Override
    public String getName() {
        return "SecurityAuditQueryModel";
    }
    
    @Override
    public Class<SecurityAuditState> getStateClass() {
        return SecurityAuditState.class;
    }
    
    @Override
    public String getIndexName() {
        return "SecurityAudits";
    }
    
    /**
     * State for security audit query model.
     */
    @QueryModelStateInfo(type = "SecurityAuditState")
    public record SecurityAuditState(
            UUID userId,
            List<SecurityEvent> events
    ) implements QueryModelState {
        
        public SecurityAuditState {
            if (events == null) {
                events = Collections.emptyList();
            } else {
                events = Collections.unmodifiableList(new ArrayList<>(events));
            }
        }
        
        /**
         * Adds a new security event to the audit trail.
         */
        public SecurityAuditState withEvent(SecurityEvent event) {
            List<SecurityEvent> updatedEvents = new ArrayList<>(events);
            updatedEvents.add(event);
            return new SecurityAuditState(userId, updatedEvents);
        }
        
        @Override
        public String getAggregateId() {
            return userId.toString();
        }
    }
    
    /**
     * Represents a security-related event in the audit trail.
     */
    public record SecurityEvent(
            UUID eventId,
            String eventType,
            @PIIData String ipAddress,
            DeviceInfo deviceInfo,
            Instant timestamp,
            boolean success,
            String details
    ) {
    }
    
    /**
     * Creates a new security audit state when a user logs in.
     */
    @QueryModelEventHandler(create = true)
    public SecurityAuditState handle(UserLoggedInEvent event, SecurityAuditState isNull) {
        SecurityEvent securityEvent = new SecurityEvent(
                UUID.randomUUID(),
                "LOGIN",
                event.ipAddress(),
                event.deviceInfo(),
                event.loginTime(),
                true,
                "User logged in successfully"
        );
        
        return new SecurityAuditState(
                event.userId(),
                List.of(securityEvent)
        );
    }
    
    /**
     * Adds a token issuance event to the audit trail.
     */
    @QueryModelEventHandler
    public SecurityAuditState handle(AuthTokenIssuedEvent event, SecurityAuditState state) {
        SecurityEvent securityEvent = new SecurityEvent(
                UUID.randomUUID(),
                "TOKEN_ISSUED",
                event.ipAddress(),
                event.deviceInfo(),
                event.issuedAt(),
                true,
                "Authentication token issued"
        );
        
        return state.withEvent(securityEvent);
    }
    
    /**
     * Adds a token refresh event to the audit trail.
     */
    @QueryModelEventHandler
    public SecurityAuditState handle(AuthTokenRefreshedEvent event, SecurityAuditState state) {
        SecurityEvent securityEvent = new SecurityEvent(
                UUID.randomUUID(),
                "TOKEN_REFRESHED",
                event.ipAddress(),
                event.deviceInfo(),
                event.refreshedAt(),
                true,
                "Authentication token refreshed"
        );
        
        return state.withEvent(securityEvent);
    }
    
    /**
     * Adds a token revocation event to the audit trail.
     */
    @QueryModelEventHandler
    public SecurityAuditState handle(AuthTokenRevokedEvent event, SecurityAuditState state) {
        SecurityEvent securityEvent = new SecurityEvent(
                UUID.randomUUID(),
                "TOKEN_REVOKED",
                "Unknown",
                null,
                event.revokedAt(),
                true,
                "Authentication token revoked"
        );
        
        return state.withEvent(securityEvent);
    }
    
    /**
     * Adds a password change event to the audit trail.
     */
    @QueryModelEventHandler
    public SecurityAuditState handle(PasswordChangedEvent event, SecurityAuditState state) {
        SecurityEvent securityEvent = new SecurityEvent(
                UUID.randomUUID(),
                "PASSWORD_CHANGED",
                "Unknown",
                null,
                event.changedAt(),
                true,
                "User password changed"
        );
        
        return state.withEvent(securityEvent);
    }
}