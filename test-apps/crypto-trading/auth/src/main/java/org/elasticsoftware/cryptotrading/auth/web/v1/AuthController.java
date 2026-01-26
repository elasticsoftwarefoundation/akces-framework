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

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Controller for OAuth2 authentication endpoints.
 * 
 * <p>This controller provides endpoints to initiate OAuth2 authentication flows
 * with various providers (e.g., Google, GitHub, etc.).
 * 
 * <p>The OAuth2 flow works as follows:
 * <ol>
 *   <li>User calls {@code /v1/auth/login/{provider}} to initiate OAuth flow</li>
 *   <li>User is redirected to the OAuth provider's authorization page</li>
 *   <li>After user grants permission, provider redirects to {@code /login/oauth2/code/{provider}}</li>
 *   <li>Spring Security processes the authorization code and calls OAuth2LoginSuccessHandler</li>
 *   <li>Success handler generates JWT tokens and returns them to the client</li>
 * </ol>
 * 
 * <p><strong>Base URL:</strong> https://api.dev2.casual-trading.com/auth
 * 
 * <p><strong>Redirect URI:</strong> https://api.dev2.casual-trading.com/auth/login/oauth2/code/{registrationId}
 * 
 * <p>The redirect URI must be registered in the OAuth provider's configuration
 * (e.g., Google Cloud Console for Google OAuth).
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {
    
    /**
     * Initiates OAuth2 login flow with the specified provider.
     * 
     * <p>This endpoint redirects the user to the OAuth2 provider's authorization page.
     * After successful authentication, the provider will redirect back to the
     * application's callback URL: {@code {baseUrl}/login/oauth2/code/{provider}}
     * 
     * <p><strong>Example:</strong>
     * <pre>
     * GET /v1/auth/login/google
     * 
     * Response: 302 Redirect to https://accounts.google.com/o/oauth2/v2/auth?...
     * </pre>
     * 
     * <p><strong>Supported Providers:</strong>
     * <ul>
     *   <li>google - Google OAuth2</li>
     *   <li>Additional providers can be configured in application.yml</li>
     * </ul>
     * 
     * @param provider the OAuth2 provider name (e.g., "google")
     * @param response the server HTTP response for setting redirect
     * @return a Mono that completes when the redirect is set
     */
    @GetMapping("/login/{provider}")
    public Mono<Void> login(
            @PathVariable String provider,
            ServerHttpResponse response) {
        
        // Redirect to Spring Security's OAuth2 authorization endpoint
        // Spring Security will handle the OAuth2 flow and redirect to the provider
        String redirectUrl = "/oauth2/authorization/" + provider;
        
        response.setStatusCode(HttpStatus.FOUND);
        response.getHeaders().setLocation(URI.create(redirectUrl));
        
        return response.setComplete();
    }
    
    /**
     * Information endpoint about the OAuth2 callback.
     * 
     * <p>This endpoint provides information about the OAuth2 redirect URI that must be
     * configured in the OAuth provider's settings.
     * 
     * <p><strong>Note:</strong> The actual callback at {@code /login/oauth2/code/{registrationId}}
     * is handled automatically by Spring Security OAuth2 Client. You don't need to implement
     * a controller method for it. Spring Security will:
     * <ol>
     *   <li>Receive the authorization code from the OAuth provider</li>
     *   <li>Exchange it for an access token</li>
     *   <li>Fetch user information from the provider</li>
     *   <li>Call the configured success handler (OAuth2LoginSuccessHandler)</li>
     * </ol>
     * 
     * <p><strong>Redirect URI Configuration:</strong>
     * <ul>
     *   <li>Development: http://localhost:8080/login/oauth2/code/google</li>
     *   <li>Production: https://api.dev2.casual-trading.com/auth/login/oauth2/code/google</li>
     * </ul>
     * 
     * <p>This redirect URI must be registered in the OAuth provider's configuration:
     * <ul>
     *   <li>Google: Google Cloud Console → APIs & Services → Credentials → OAuth 2.0 Client IDs</li>
     *   <li>Add the redirect URI to "Authorized redirect URIs"</li>
     * </ul>
     * 
     * @return OAuth callback information
     */
    @GetMapping("/callback-info")
    public Mono<CallbackInfo> callbackInfo() {
        CallbackInfo info = new CallbackInfo(
            "The OAuth2 callback is handled automatically by Spring Security",
            "/login/oauth2/code/{registrationId}",
            "Register this redirect URI in your OAuth provider's configuration",
            "https://api.dev2.casual-trading.com/auth/login/oauth2/code/google"
        );
        return Mono.just(info);
    }
    
    /**
     * Response DTO for callback information endpoint.
     */
    public record CallbackInfo(
        String message,
        String callbackPath,
        String note,
        String productionRedirectUri
    ) {}
}
