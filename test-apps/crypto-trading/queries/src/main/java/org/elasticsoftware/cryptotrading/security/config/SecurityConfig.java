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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Security configuration for Queries Service.
 * 
 * <p><strong>WARNING: This is a Phase 1 foundation setup configuration.</strong>
 * <p>Current configuration permits all requests without authentication to allow tests to pass
 * during the foundation setup phase. This is intentionally insecure and must be replaced in Phase 2.
 * 
 * <p><strong>Phase 2 TODO:</strong>
 * <ul>
 *   <li>Enable JWT validation using Spring Security OAuth2 Resource Server</li>
 *   <li>Configure proper authorization rules for endpoints</li>
 *   <li>Keep CSRF disabled (appropriate for stateless JWT-based APIs)</li>
 *   <li>Add security headers (X-Frame-Options, X-Content-Type-Options, etc.)</li>
 * </ul>
 * 
 * <p><strong>CSRF Protection:</strong> Disabled because this API will use JWT tokens
 * for stateless authentication. CSRF protection is not needed for stateless APIs
 * where authentication is via bearer tokens rather than cookies.
 * 
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html">Spring Security CSRF</a>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Configures the security filter chain.
     * 
     * <p><strong>SECURITY WARNING:</strong> This configuration permits all requests without authentication.
     * This is only acceptable for Phase 1 foundation setup and must be replaced with proper
     * JWT validation in Phase 2.
     * 
     * @param http the server HTTP security configuration
     * @return the configured security web filter chain
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            // CSRF disabled for stateless JWT-based API (no session cookies)
            .csrf(csrf -> csrf.disable())
            // TODO Phase 2: Replace with proper JWT validation
            // Example Phase 2 configuration:
            // .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            // .authorizeExchange(exchanges -> exchanges
            //     .pathMatchers("/actuator/health", "/actuator/info").permitAll()
            //     .anyExchange().authenticated()
            // )
            .authorizeExchange(exchanges -> exchanges
                .anyExchange().permitAll()  // WARNING: Permits all requests without authentication
            );
        
        return http.build();
    }
}
