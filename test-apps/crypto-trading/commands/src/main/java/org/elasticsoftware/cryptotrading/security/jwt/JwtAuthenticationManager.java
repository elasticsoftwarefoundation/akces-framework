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

package org.elasticsoftware.cryptotrading.security.jwt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

/**
 * JWT Authentication Manager for validating JWT tokens.
 * 
 * <p>This manager validates JWT tokens signed with HS256 (HMAC with SHA-256).
 * It verifies the token signature and checks expiration, then creates an
 * Authentication object for authorized requests.
 * 
 * <p><strong>Security Note:</strong> This implementation uses symmetric key signing (HS256).
 * In production environments with multiple services, consider using asymmetric signing (RS256)
 * where the auth service signs with a private key and resource servers validate with a public key.
 */
@Component
public class JwtAuthenticationManager implements ReactiveAuthenticationManager {
    
    private static final String HMAC_SHA256 = "HmacSHA256";
    
    private final String jwtSecret;
    private final String issuer;
    
    /**
     * Constructs the JWT authentication manager with configuration.
     * 
     * @param jwtSecret the secret key for validating tokens
     * @param issuer the expected issuer claim
     */
    public JwtAuthenticationManager(
            @Value("${app.jwt.secret:default-secret-change-in-production}") String jwtSecret,
            @Value("${app.jwt.issuer:akces-crypto-trading}") String issuer) {
        this.jwtSecret = jwtSecret;
        this.issuer = issuer;
    }
    
    /**
     * Authenticates the JWT token.
     * 
     * @param authentication the authentication object containing the JWT token
     * @return a Mono emitting the authenticated principal
     */
    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String token = authentication.getCredentials().toString();
        
        return Mono.fromCallable(() -> validateToken(token))
            .flatMap(claims -> {
                String userId = (String) claims.get("sub");
                String issuerClaim = (String) claims.get("iss");
                Number iat = (Number) claims.get("iat");
                Number exp = (Number) claims.get("exp");
                
                // Create JWT object
                Jwt jwt = Jwt.withTokenValue(token)
                    .header("alg", "HS256")
                    .header("typ", "JWT")
                    .claims(c -> c.putAll(claims))
                    .subject(userId)
                    .issuer(issuerClaim)
                    .issuedAt(iat != null ? Instant.ofEpochSecond(iat.longValue()) : null)
                    .expiresAt(exp != null ? Instant.ofEpochSecond(exp.longValue()) : null)
                    .build();
                
                // Create JwtAuthenticationToken with user details and authorities
                JwtAuthenticationToken auth = new JwtAuthenticationToken(
                    jwt,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                );
                
                return Mono.<Authentication>just(auth);
            })
            .onErrorResume(e -> Mono.empty());
    }
    
    /**
     * Validates the JWT token and extracts claims.
     * 
     * @param token the JWT token to validate
     * @return the claims map
     * @throws Exception if validation fails
     */
    private Map<String, Object> validateToken(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT token format");
        }
        
        String headerAndPayload = parts[0] + "." + parts[1];
        String signature = parts[2];
        
        // Verify signature
        String expectedSignature = signHmacSha256(headerAndPayload);
        if (!signature.equals(expectedSignature)) {
            throw new SecurityException("Invalid JWT signature");
        }
        
        // Decode and parse payload
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
        
        // Simple JSON parsing for claims
        Map<String, Object> claims = parseJsonClaims(payloadJson);
        
        // Verify issuer
        String tokenIssuer = (String) claims.get("iss");
        if (!issuer.equals(tokenIssuer)) {
            throw new SecurityException("Invalid issuer");
        }
        
        // Verify expiration
        Number exp = (Number) claims.get("exp");
        if (exp != null) {
            long expirationTime = exp.longValue();
            long currentTime = System.currentTimeMillis() / 1000;
            if (currentTime > expirationTime) {
                throw new SecurityException("Token expired");
            }
        }
        
        return claims;
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
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }
    
    /**
     * Simple JSON parser for JWT claims.
     * 
     * @param json the JSON string
     * @return the parsed claims map
     */
    private Map<String, Object> parseJsonClaims(String json) {
        Map<String, Object> claims = new java.util.HashMap<>();
        
        // Remove braces
        json = json.trim().substring(1, json.length() - 1);
        
        // Split by comma (simple parser, assumes no nested objects)
        String[] pairs = json.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
        
        for (String pair : pairs) {
            String[] keyValue = pair.split(":", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim().replaceAll("\"", "");
                String value = keyValue[1].trim();
                
                // Parse value type
                if (value.startsWith("\"")) {
                    // String value
                    claims.put(key, value.replaceAll("\"", ""));
                } else if (value.equals("true") || value.equals("false")) {
                    // Boolean value
                    claims.put(key, Boolean.parseBoolean(value));
                } else {
                    // Number value
                    try {
                        if (value.contains(".")) {
                            claims.put(key, Double.parseDouble(value));
                        } else {
                            claims.put(key, Long.parseLong(value));
                        }
                    } catch (NumberFormatException e) {
                        claims.put(key, value);
                    }
                }
            }
        }
        
        return claims;
    }
}
