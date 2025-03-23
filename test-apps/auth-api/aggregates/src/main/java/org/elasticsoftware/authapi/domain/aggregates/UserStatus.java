package org.elasticsoftware.authapi.domain.aggregates;

/**
 * Represents the possible statuses of a user account.
 */
public enum UserStatus {
    /**
     * Account created but not yet verified.
     */
    PENDING_VERIFICATION,
    
    /**
     * Account is active and can be used.
     */
    ACTIVE,
    
    /**
     * Account is locked due to security reasons.
     */
    LOCKED,
    
    /**
     * Account is deactivated by the user.
     */
    DEACTIVATED,
    
    /**
     * Account is blocked by an administrator.
     */
    BLOCKED
}