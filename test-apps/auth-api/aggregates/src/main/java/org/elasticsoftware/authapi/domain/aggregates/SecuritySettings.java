package org.elasticsoftware.authapi.domain.aggregates;

/**
 * Represents user security settings.
 */
public record SecuritySettings(
        boolean loginNotificationsEnabled,
        boolean enhancedSecurity
) {
    /**
     * Creates a new instance with updated settings.
     *
     * @param loginNotificationsEnabled Whether to notify the user of login attempts
     * @param enhancedSecurity Whether to enable enhanced security features
     * @return A new SecuritySettings instance
     */
    public SecuritySettings withSettings(boolean loginNotificationsEnabled, boolean enhancedSecurity) {
        return new SecuritySettings(loginNotificationsEnabled, enhancedSecurity);
    }
}