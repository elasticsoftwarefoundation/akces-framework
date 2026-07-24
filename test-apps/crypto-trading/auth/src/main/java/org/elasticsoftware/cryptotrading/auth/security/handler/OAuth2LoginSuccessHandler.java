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

package org.elasticsoftware.cryptotrading.auth.security.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.cryptotrading.auth.security.jwt.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles successful OAuth2 authentication by generating JWT tokens.
 * 
 * <p>This handler is invoked after a user successfully authenticates with an OAuth2 provider.
 * It generates both access and refresh tokens and returns them to the client in JSON format.
 * 
 * <p>The response format is:
 * <pre>
 * {
 *   "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
 *   "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
 *   "token_type": "Bearer",
 *   "expires_in": 900
 * }
 * </pre>
 * 
 * <p>In production, consider redirecting to a frontend URL with tokens as secure cookies
 * or URL fragments instead of returning them directly in the response body.
 */
@Component
public class OAuth2LoginSuccessHandler implements ServerAuthenticationSuccessHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);
    
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    
    /**
     * Constructs the success handler with required dependencies.
     * 
     * @param jwtTokenProvider the JWT token provider for generating tokens
     * @param objectMapper the Jackson object mapper for JSON serialization
     */
    public OAuth2LoginSuccessHandler(JwtTokenProvider jwtTokenProvider, ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Handles successful authentication by generating and returning JWT tokens.
     * 
     * @param webFilterExchange the web filter exchange containing the request and response
     * @param authentication the authentication object containing user details
     * @return a Mono that completes when the response is written
     */
    @Override
    public Mono<Void> onAuthenticationSuccess(
            WebFilterExchange webFilterExchange,
            Authentication authentication) {
        
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
        String email = oauth2User.getAttribute("email");
        
        logger.info("OAuth2 authentication successful for user: {}", email);
        
        try {
            // Generate JWT tokens
            String accessToken = jwtTokenProvider.generateAccessToken(authentication);
            String refreshToken = jwtTokenProvider.generateRefreshToken(authentication);
            
            // Create response body
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("access_token", accessToken);
            responseBody.put("refresh_token", refreshToken);
            responseBody.put("token_type", "Bearer");
            responseBody.put("expires_in", 900); // 15 minutes
            
            // Serialize to JSON
            byte[] responseBytes = objectMapper.writeValueAsBytes(responseBody);
            
            // Write response
            var response = webFilterExchange.getExchange().getResponse();
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            
            DataBuffer buffer = response.bufferFactory().wrap(responseBytes);
            return response.writeWith(Mono.just(buffer));
            
        } catch (Exception e) {
            logger.error("Error generating JWT tokens", e);
            return Mono.error(e);
        }
    }
}
