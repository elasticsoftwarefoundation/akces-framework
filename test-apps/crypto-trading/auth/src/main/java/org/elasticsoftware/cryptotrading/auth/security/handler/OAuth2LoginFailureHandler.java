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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles OAuth2 authentication failures.
 * 
 * <p>This handler is invoked when OAuth2 authentication fails for any reason, such as:
 * <ul>
 *   <li>User denies authorization</li>
 *   <li>OAuth2 provider returns an error</li>
 *   <li>Network errors during token exchange</li>
 *   <li>Invalid OAuth2 configuration</li>
 * </ul>
 * 
 * <p>The handler logs the error details and returns a JSON error response to the client.
 * 
 * <p>The response format is:
 * <pre>
 * {
 *   "error": "authentication_failed",
 *   "error_description": "OAuth2 authentication failed: User denied access",
 *   "status": 401
 * }
 * </pre>
 */
@Component
public class OAuth2LoginFailureHandler implements ServerAuthenticationFailureHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(OAuth2LoginFailureHandler.class);
    
    private final ObjectMapper objectMapper;
    
    /**
     * Constructs the failure handler with required dependencies.
     * 
     * @param objectMapper the Jackson object mapper for JSON serialization
     */
    public OAuth2LoginFailureHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * Handles authentication failure by logging the error and returning an error response.
     * 
     * @param webFilterExchange the web filter exchange containing the request and response
     * @param exception the authentication exception that caused the failure
     * @return a Mono that completes when the response is written
     */
    @Override
    public Mono<Void> onAuthenticationFailure(
            WebFilterExchange webFilterExchange,
            AuthenticationException exception) {
        
        logger.error("OAuth2 authentication failed", exception);
        
        try {
            // Create error response
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "authentication_failed");
            errorResponse.put("error_description", 
                "OAuth2 authentication failed: " + exception.getMessage());
            errorResponse.put("status", HttpStatus.UNAUTHORIZED.value());
            
            // Serialize to JSON
            byte[] responseBytes = objectMapper.writeValueAsBytes(errorResponse);
            
            // Write response
            var response = webFilterExchange.getExchange().getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            
            DataBuffer buffer = response.bufferFactory().wrap(responseBytes);
            return response.writeWith(Mono.just(buffer));
            
        } catch (Exception e) {
            logger.error("Error writing authentication failure response", e);
            return Mono.error(e);
        }
    }
}
