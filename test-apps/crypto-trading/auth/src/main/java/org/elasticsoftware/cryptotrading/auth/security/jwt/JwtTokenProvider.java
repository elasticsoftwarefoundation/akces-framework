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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

/**
 * JWT Token Provider for generating and signing JWT tokens using RS256.
 * 
 * <p>This implementation uses Nimbus JOSE+JWT library with RS256 (RSA Signature with SHA-256)
 * for asymmetric token signing. The private key is used for signing, and the public key
 * is distributed via JWKS endpoint for validation by resource servers.
 * 
 * <p>In production, the private key should come from GCP Service Account credentials.
 * For development/testing, a generated RSA key pair is used.
 */
@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);
    
    private final RsaKeyProvider rsaKeyProvider;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private final String issuer;
    private final JWSSigner signer;
    
    /**
     * Constructs a JWT Token Provider with configuration from application properties.
     *
     * @param rsaKeyProvider the RSA key provider for signing
     * @param accessTokenExpiration access token expiration time in milliseconds
     * @param refreshTokenExpiration refresh token expiration time in milliseconds
     * @param issuer the issuer claim for JWT tokens
     */
    public JwtTokenProvider(
            RsaKeyProvider rsaKeyProvider,
            @Value("${app.jwt.access-token-expiration:900000}") long accessTokenExpiration,
            @Value("${app.jwt.refresh-token-expiration:604800000}") long refreshTokenExpiration,
            @Value("${app.jwt.issuer:akces-crypto-trading}") String issuer) {
        this.rsaKeyProvider = rsaKeyProvider;
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        this.issuer = issuer;
        
        try {
            this.signer = new RSASSASigner(rsaKeyProvider.getRsaKey());
            logger.info("JWT Token Provider initialized with RS256 signing");
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to initialize JWT signer", e);
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
        
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");
        String userId = oauth2User.getAttribute("sub");
        
        Instant now = Instant.now();
        Instant expiration = now.plusMillis(accessTokenExpiration);
        
        return generateToken(userId, email, name, now, expiration, "access");
    }
    
    /**
     * Generates a refresh token for the authenticated user.
     * 
     * @param authentication the authentication object containing user details
     * @return the generated JWT refresh token
     */
    public String generateRefreshToken(Authentication authentication) {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        
        String userId = oauth2User.getAttribute("sub");
        
        Instant now = Instant.now();
        Instant expiration = now.plusMillis(refreshTokenExpiration);
        
        return generateToken(userId, null, null, now, expiration, "refresh");
    }
    
    /**
     * Generates a JWT token with the specified claims using Nimbus JOSE+JWT.
     * 
     * @param userId the user identifier
     * @param email the user email (nullable for refresh tokens)
     * @param name the user name (nullable for refresh tokens)
     * @param issuedAt the token issuance time
     * @param expiration the token expiration time
     * @param tokenType the token type ("access" or "refresh")
     * @return the generated JWT token
     */
    private String generateToken(String userId, String email, String name, 
                                 Instant issuedAt, Instant expiration,
                                 String tokenType) {
        try {
            // Create JWT claims
            JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .subject(userId)
                .issuer(issuer)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiration))
                .claim("token_type", tokenType);
            
            if (email != null) {
                claimsBuilder.claim("email", email);
            }
            if (name != null) {
                claimsBuilder.claim("name", name);
            }
            
            JWTClaimsSet claims = claimsBuilder.build();
            
            // Create JWT header with key ID
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(rsaKeyProvider.getRsaKey().getKeyID())
                .build();
            
            // Create signed JWT
            SignedJWT signedJWT = new SignedJWT(header, claims);
            signedJWT.sign(signer);
            
            return signedJWT.serialize();
            
        } catch (JOSEException e) {
            logger.error("Error generating JWT token", e);
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }
}
