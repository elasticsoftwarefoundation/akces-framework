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

import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Provides RSA key pair for JWT signing and verification.
 * 
 * <p>In development/test environments, generates a new RSA key pair on startup.
 * In production, this should load the GCP Service Account private key from Secret Manager.
 * 
 * <p>The public key is exposed via JWKS endpoint for JWT validation by resource servers.
 */
@Component
public class RsaKeyProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(RsaKeyProvider.class);
    
    private final RSAKey rsaKey;
    
    /**
     * Constructs the RSA key provider.
     * Generates a new 2048-bit RSA key pair for development/testing.
     * 
     * <p>In production, replace this with loading from GCP Service Account:
     * <pre>
     * ServiceAccountCredentials credentials = ServiceAccountCredentials.fromStream(
     *     new ByteArrayInputStream(serviceAccountJson.getBytes())
     * );
     * PrivateKey privateKey = credentials.getPrivateKey();
     * </pre>
     */
    public RsaKeyProvider() {
        try {
            // Generate RSA key pair for development/testing
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            
            // Create Nimbus RSAKey with key ID
            this.rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
            
            logger.info("Generated RSA key pair for JWT signing (kid: {})", rsaKey.getKeyID());
            logger.warn("Using generated RSA key pair. In production, load from GCP Service Account.");
            
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }
    
    /**
     * Gets the RSA key (includes both public and private key).
     * 
     * @return the RSA key
     */
    public RSAKey getRsaKey() {
        return rsaKey;
    }
    
    /**
     * Gets the public RSA key for JWKS endpoint.
     * 
     * @return the public RSA key (without private key)
     */
    public RSAKey getPublicKey() {
        return rsaKey.toPublicJWK();
    }
}
