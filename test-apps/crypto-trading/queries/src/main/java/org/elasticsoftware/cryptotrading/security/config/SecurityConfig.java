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

import org.elasticsoftware.cryptotrading.security.jwt.JwtAuthenticationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import reactor.core.publisher.Mono;

/**
 * Security configuration for Queries Service.
 * 
 * <p>This configuration validates JWT tokens from the Authorization header
 * and requires authentication for all endpoints except actuator health checks.
 * 
 * <p><strong>CSRF Protection:</strong> Disabled because this API uses JWT tokens
 * for stateless authentication. CSRF protection is not needed for stateless APIs
 * where authentication is via bearer tokens rather than cookies.
 * 
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html">Spring Security CSRF</a>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final JwtAuthenticationManager jwtAuthenticationManager;
    
    /**
     * Constructs the security configuration with JWT authentication manager.
     * 
     * @param jwtAuthenticationManager the JWT authentication manager
     */
    public SecurityConfig(JwtAuthenticationManager jwtAuthenticationManager) {
        this.jwtAuthenticationManager = jwtAuthenticationManager;
    }

    /**
     * Configures the security filter chain with JWT validation.
     * 
     * @param http the server HTTP security configuration
     * @return the configured security web filter chain
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        // Create JWT authentication filter
        AuthenticationWebFilter jwtFilter = new AuthenticationWebFilter(jwtAuthenticationManager);
        jwtFilter.setServerAuthenticationConverter(jwtAuthenticationConverter());
        
        http
            // CSRF disabled for stateless JWT-based API (no session cookies)
            .csrf(csrf -> csrf.disable())
            
            // Add JWT authentication filter
            .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
            
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
     * Converts the JWT token from the Authorization header to a BearerTokenAuthenticationToken.
     * 
     * @return the server authentication converter
     */
    private ServerAuthenticationConverter jwtAuthenticationConverter() {
        return exchange -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                return Mono.just(new BearerTokenAuthenticationToken(token));
            }
            
            return Mono.empty();
        };
    }
}
