/*
 * Copyright 2022 - 2025 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.cryptotrading.auth.security.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * JWT Token Provider for generating and signing JWT tokens.
 * 
 * <p>This implementation uses HS256 (HMAC with SHA-256) for token signing with a configurable secret.
 * In production environments, this should be replaced with RS256 using GCP Service Account keys
 * for enhanced security.
 * 
 * <p><strong>Security Note:</strong> This implementation is simplified for development and testing.
 * Production deployments should use asymmetric signing (RS256) with proper key management through
 * GCP Secret Manager or similar services.
 */
@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);
    
    private static final String HMAC_SHA256 = "HmacSHA256";
    
    private final String jwtSecret;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private final String issuer;
    
    /**
     * Constructs a JWT Token Provider with configuration from application properties.
     *
     * @param jwtSecret the secret key for signing tokens
     * @param accessTokenExpiration access token expiration time in milliseconds
     * @param refreshTokenExpiration refresh token expiration time in milliseconds
     * @param issuer the issuer claim for JWT tokens
     */
    public JwtTokenProvider(
            @Value("${app.jwt.secret:default-secret-change-in-production}") String jwtSecret,
            @Value("${app.jwt.access-token-expiration:900000}") long accessTokenExpiration,
            @Value("${app.jwt.refresh-token-expiration:604800000}") long refreshTokenExpiration,
            @Value("${app.jwt.issuer:akces-crypto-trading}") String issuer) {
        this.jwtSecret = jwtSecret;
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        this.issuer = issuer;
        
        if ("default-secret-change-in-production".equals(jwtSecret)) {
            logger.warn("Using default JWT secret. Please configure app.jwt.secret for production use.");
        }
    }
    
    /**
     * Generates an access token for the authenticated user.
     * 
     * @param authentication the authentication object containing user details
     * @return the generated JWT access token
     */
    public String generateAccessToken(Authentication authentication) {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        
        long nowMillis = System.currentTimeMillis();
        long expMillis = nowMillis + accessTokenExpiration;
        
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");
        String userId = oauth2User.getAttribute("sub");
        
        return generateToken(userId, email, name, nowMillis, expMillis, "access");
    }
    
    /**
     * Generates a refresh token for the authenticated user.
     * 
     * @param authentication the authentication object containing user details
     * @return the generated JWT refresh token
     */
    public String generateRefreshToken(Authentication authentication) {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        
        long nowMillis = System.currentTimeMillis();
        long expMillis = nowMillis + refreshTokenExpiration;
        
        String userId = oauth2User.getAttribute("sub");
        
        return generateToken(userId, null, null, nowMillis, expMillis, "refresh");
    }
    
    /**
     * Generates a JWT token with the specified claims.
     * 
     * @param userId the user identifier
     * @param email the user email (nullable for refresh tokens)
     * @param name the user name (nullable for refresh tokens)
     * @param issuedAtMillis the token issuance time in milliseconds
     * @param expirationMillis the token expiration time in milliseconds
     * @param tokenType the token type ("access" or "refresh")
     * @return the generated JWT token
     */
    private String generateToken(String userId, String email, String name, 
                                 long issuedAtMillis, long expirationMillis,
                                 String tokenType) {
        try {
            // Create JWT header
            String header = createBase64UrlEncodedJson(Map.of(
                "alg", "HS256",
                "typ", "JWT"
            ));
            
            // Create JWT payload
            var claimsBuilder = new java.util.HashMap<String, Object>();
            claimsBuilder.put("sub", userId);
            claimsBuilder.put("iss", issuer);
            claimsBuilder.put("iat", issuedAtMillis / 1000);
            claimsBuilder.put("exp", expirationMillis / 1000);
            claimsBuilder.put("token_type", tokenType);
            
            if (email != null) {
                claimsBuilder.put("email", email);
            }
            if (name != null) {
                claimsBuilder.put("name", name);
            }
            
            String payload = createBase64UrlEncodedJson(claimsBuilder);
            
            // Create signature
            String headerAndPayload = header + "." + payload;
            String signature = signHmacSha256(headerAndPayload);
            
            return headerAndPayload + "." + signature;
            
        } catch (Exception e) {
            logger.error("Error generating JWT token", e);
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }
    
    /**
     * Creates a Base64 URL-encoded JSON string from a map.
     * 
     * @param data the data to encode
     * @return the Base64 URL-encoded JSON string
     */
    private String createBase64UrlEncodedJson(Map<String, Object> data) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(value).append("\"");
            } else {
                json.append(value);
            }
        }
        json.append("}");
        
        return base64UrlEncode(json.toString().getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Signs data using HMAC-SHA256.
     * 
     * @param data the data to sign
     * @return the Base64 URL-encoded signature
     */
    private String signHmacSha256(String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec secretKeySpec = new SecretKeySpec(
            jwtSecret.getBytes(StandardCharsets.UTF_8), 
            HMAC_SHA256
        );
        mac.init(secretKeySpec);
        byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(signature);
    }
    
    /**
     * Base64 URL-encodes the given bytes.
     * 
     * @param bytes the bytes to encode
     * @return the Base64 URL-encoded string
     */
    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes);
    }
}
