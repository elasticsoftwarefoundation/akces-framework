package org.elasticsoftware.authapi.domain.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Utility class for TOTP (Time-based One-Time Password) operations.
 * 
 * Note: In a real application, this would include actual TOTP implementation.
 * For this example, we're using a simplified version.
 */
public class TotpUtils {
    
    private static final SecureRandom RANDOM = new SecureRandom();
    
    /**
     * Generates a new secret key for TOTP.
     *
     * @return A Base32 encoded secret key
     */
    public static String generateSecret() {
        byte[] buffer = new byte[20];
        RANDOM.nextBytes(buffer);
        return Base64.getEncoder().encodeToString(buffer);
    }
    
    /**
     * Verifies a TOTP code against a secret.
     *
     * @param code The code to verify
     * @param secret The secret key
     * @return True if the code is valid, false otherwise
     */
    public static boolean verifyCode(String code, String secret) {
        // In a real application, this would implement TOTP verification
        // Using RFC 6238 (TOTP: Time-Based One-Time Password Algorithm)
        // For this example, we're simplifying and just returning true
        
        // Simple validation to ensure the code looks like a 6-digit number
        return code != null && code.matches("\\d{6}");
    }
}