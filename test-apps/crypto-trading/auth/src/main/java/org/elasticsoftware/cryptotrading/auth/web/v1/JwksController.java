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

package org.elasticsoftware.cryptotrading.auth.web.v1;

import com.nimbusds.jose.jwk.JWKSet;
import org.elasticsoftware.cryptotrading.auth.security.jwt.RsaKeyProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Controller for exposing JSON Web Key Set (JWKS) endpoint.
 * 
 * <p>This endpoint provides the public keys used for JWT signature verification.
 * Resource servers (Commands and Queries services) fetch these keys to validate
 * JWT tokens issued by this Auth service.
 * 
 * <p>Standard JWKS endpoint: {@code /.well-known/jwks.json}
 */
@RestController
public class JwksController {
    
    private final RsaKeyProvider rsaKeyProvider;
    
    /**
     * Constructs the JWKS controller.
     * 
     * @param rsaKeyProvider the RSA key provider
     */
    public JwksController(RsaKeyProvider rsaKeyProvider) {
        this.rsaKeyProvider = rsaKeyProvider;
    }
    
    /**
     * Exposes the JWKS endpoint with public keys for JWT verification.
     * 
     * @return the JSON Web Key Set
     */
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> jwks() {
        JWKSet jwkSet = new JWKSet(rsaKeyProvider.getPublicKey());
        return Mono.just(jwkSet.toJSONObject());
    }
}
