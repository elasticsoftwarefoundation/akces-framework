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

package org.elasticsoftware.cryptotrading.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Security configuration for Queries Service.
 * 
 * <p>This configuration validates JWT tokens using Spring Security OAuth2 Resource Server.
 * JWTs are validated using public keys fetched from the Auth service's JWKS endpoint.
 * 
 * <p><strong>CSRF Protection:</strong> Disabled because this API uses JWT tokens
 * for stateless authentication. CSRF protection is not needed for stateless APIs
 * where authentication is via bearer tokens rather than cookies.
 * 
 * <p>JWT validation is handled automatically by Spring Security using Nimbus JWT library.
 * No custom authentication code is needed - Spring Security fetches public keys from JWKS
 * and validates token signatures, expiration, and issuer claims.
 * 
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html">Spring Security CSRF</a>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    /**
     * Configures the security filter chain with OAuth2 Resource Server JWT validation.
     * 
     * @param http the server HTTP security configuration
     * @return the configured security web filter chain
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            // CSRF disabled for stateless JWT-based API (no session cookies)
            .csrf(csrf -> csrf.disable())
            
            // Configure OAuth2 Resource Server with JWT
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(jwtDecoder()))
            )
            
            // Authorization rules
            .authorizeExchange(exchanges -> exchanges
                // Public endpoints
                .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                // All other endpoints require authentication
                .anyExchange().authenticated()
            );
        
        return http.build();
    }
    
    /**
     * Creates a reactive JWT decoder using Nimbus that fetches public keys from JWKS endpoint.
     * 
     * <p>Spring Security automatically:
     * <ul>
     *   <li>Fetches public keys from the JWKS endpoint</li>
     *   <li>Caches keys for performance</li>
     *   <li>Validates JWT signatures using RS256</li>
     *   <li>Validates expiration and other standard claims</li>
     * </ul>
     * 
     * @return the JWT decoder
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }
}
