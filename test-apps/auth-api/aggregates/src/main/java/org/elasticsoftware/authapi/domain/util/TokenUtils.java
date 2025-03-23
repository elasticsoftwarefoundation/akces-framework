package org.elasticsoftware.authapi.domain.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Utility class for token operations.
 */
public class TokenUtils {
    
    /**
     * Generates a fingerprint from a token, which can be safely stored.
     *
     * @param token The token
     * @return A fingerprint of the token
     */
    public static String getTokenFingerprint(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate token fingerprint", e);
        }
    }
}