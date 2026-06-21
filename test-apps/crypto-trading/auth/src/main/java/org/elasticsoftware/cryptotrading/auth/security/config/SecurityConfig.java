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

package org.elasticsoftware.cryptotrading.auth.security.config;

import org.elasticsoftware.cryptotrading.auth.security.handler.OAuth2LoginFailureHandler;
import org.elasticsoftware.cryptotrading.auth.security.handler.OAuth2LoginSuccessHandler;
import org.elasticsoftware.cryptotrading.auth.security.service.OAuth2UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Security configuration for Auth Service.
 * 
 * <p>This configuration sets up OAuth2 login with Google (and potentially other providers)
 * and configures JWT token generation upon successful authentication.
 * 
 * <p>Key features:
 * <ul>
 *   <li>OAuth2 login with custom success/failure handlers</li>
 *   <li>Custom user service for Akces integration</li>
 *   <li>CORS configuration for frontend integration</li>
 *   <li>Public actuator endpoints for health checks</li>
 * </ul>
 * 
 * <p><strong>CSRF Protection:</strong> Disabled because this service generates JWT tokens
 * for stateless authentication. CSRF protection is not needed for stateless APIs.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    
    private final OAuth2UserService oauth2UserService;
    private final OAuth2LoginSuccessHandler successHandler;
    private final OAuth2LoginFailureHandler failureHandler;
    
    /**
     * Constructs the security configuration with required dependencies.
     * 
     * @param oauth2UserService the custom OAuth2 user service
     * @param successHandler the OAuth2 login success handler
     * @param failureHandler the OAuth2 login failure handler
     */
    public SecurityConfig(
            OAuth2UserService oauth2UserService,
            OAuth2LoginSuccessHandler successHandler,
            OAuth2LoginFailureHandler failureHandler) {
        this.oauth2UserService = oauth2UserService;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
    }
    
    /**
     * Configures the security filter chain for OAuth2 authentication.
     * 
     * @param http the server HTTP security configuration
     * @return the configured security web filter chain
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            // CORS configuration for frontend integration
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // Authorization rules
            .authorizeExchange(exchanges -> exchanges
                // Public endpoints
                .pathMatchers(
                    "/actuator/health",
                    "/actuator/info",
                    "/.well-known/jwks.json",  // JWKS endpoint for JWT validation
                    "/v1/auth/login/**",       // OAuth login initiation endpoints
                    "/v1/auth/callback-info",  // OAuth callback information endpoint
                    "/v1/auth/login/oauth2/**",        // Spring Security OAuth2 login endpoints
                    "/v1/auth/oauth2/**"               // Spring Security OAuth2 authorization endpoints
                ).permitAll()
                // All other endpoints require authentication
                .anyExchange().authenticated()
            )
            
            // OAuth2 login configuration
            .oauth2Login(oauth2 -> oauth2
                // Custom user service for Akces integration
                .authenticationSuccessHandler(successHandler)
                .authenticationFailureHandler(failureHandler)
            );
        
        return http.build();
    }
    
    /**
     * Configures CORS for development and production environments.
     * 
     * <p><strong>Development Configuration:</strong> Allows all origins, methods, and headers.
     * This is acceptable for local development but should be restricted in production.
     * 
     * <p><strong>Production Configuration:</strong> Should restrict:
     * <ul>
     *   <li>Allowed origins to specific frontend domains</li>
     *   <li>Allowed methods to only required HTTP methods</li>
     *   <li>Allowed headers to only required headers</li>
     *   <li>Credentials based on authentication requirements</li>
     * </ul>
     * 
     * @return the CORS configuration source
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // TODO: Restrict origins in production
        configuration.setAllowedOrigins(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false); // Set to true if using cookies
        configuration.setMaxAge(3600L); // Cache preflight response for 1 hour
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }
}
