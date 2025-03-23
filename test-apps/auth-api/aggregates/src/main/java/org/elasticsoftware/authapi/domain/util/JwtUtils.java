package org.elasticsoftware.authapi.domain.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Utility class for generating and validating JWT tokens.
 */
public class JwtUtils {
    
    // In a real application, this should be retrieved from a secure configuration
    private static final Key SIGNING_KEY = Keys.secretKeyFor(SignatureAlgorithm.HS512);
    
    /**
     * Generates an access token for the specified user.
     *
     * @param userId The user ID
     * @param tokenId The token ID
     * @param expiresAt The expiration time
     * @return The generated JWT access token
     */
    public static String generateAccessToken(UUID userId, UUID tokenId, Instant expiresAt) {
        return Jwts.builder()
                .setSubject(userId.toString())
                .setIssuedAt(Date.from(Instant.now()))
                .setExpiration(Date.from(expiresAt))
                .setId(tokenId.toString())
                .claim("type", "access")
                .signWith(SIGNING_KEY)
                .compact();
    }
    
    /**
     * Generates a refresh token for the specified user.
     *
     * @param userId The user ID
     * @param tokenId The token ID
     * @param expiresAt The expiration time
     * @return The generated JWT refresh token
     */
    public static String generateRefreshToken(UUID userId, UUID tokenId, Instant expiresAt) {
        return Jwts.builder()
                .setSubject(userId.toString())
                .setIssuedAt(Date.from(Instant.now()))
                .setExpiration(Date.from(expiresAt))
                .setId(tokenId.toString())
                .claim("type", "refresh")
                .signWith(SIGNING_KEY)
                .compact();
    }
}