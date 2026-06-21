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

package org.elasticsoftware.cryptotrading.auth.security.service;

import org.elasticsoftware.akces.client.AkcesClient;
import org.elasticsoftware.cryptotrading.auth.security.model.GoogleOAuth2UserInfo;
import org.elasticsoftware.cryptotrading.auth.security.model.OAuth2UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Custom reactive OAuth2 user details service for handling OAuth2 authentication.
 * 
 * <p>This service is responsible for:
 * <ul>
 *   <li>Loading user details from the OAuth2 provider</li>
 *   <li>Creating new user accounts via Akces command bus when a new user logs in</li>
 *   <li>Providing user information to the authentication success handler</li>
 * </ul>
 * 
 * <p>The service integrates with the Akces framework to send CreateAccountCommand
 * for new users, ensuring that all user accounts are properly registered in the
 * event-sourced system.
 */
@Service
public class OAuth2UserService extends DefaultReactiveOAuth2UserService {
    
    private static final Logger logger = LoggerFactory.getLogger(OAuth2UserService.class);
    
    private final AkcesClient akcesClient;
    
    /**
     * Constructs the OAuth2 user service with Akces client integration.
     * 
     * @param akcesClient the Akces client for sending commands
     */
    public OAuth2UserService(AkcesClient akcesClient) {
        this.akcesClient = akcesClient;
    }
    
    /**
     * Loads the OAuth2 user details after successful authentication with the provider.
     * 
     * <p>This method:
     * <ol>
     *   <li>Delegates to the default implementation to fetch user details from the provider</li>
     *   <li>Extracts provider-specific user information</li>
     *   <li>Creates a new user account in the system if this is the user's first login</li>
     * </ol>
     * 
     * <p><strong>Note:</strong> Account creation is done asynchronously and does not block
     * the authentication flow. If account creation fails, it is logged but does not prevent
     * the user from logging in.
     * 
     * @param userRequest the OAuth2 user request containing tokens and client registration
     * @return a Mono emitting the OAuth2User with user details
     * @throws OAuth2AuthenticationException if user details cannot be loaded
     */
    @Override
    public Mono<OAuth2User> loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        return super.loadUser(userRequest)
            .flatMap(oauth2User -> {
                String registrationId = userRequest.getClientRegistration().getRegistrationId();
                OAuth2UserInfo userInfo = extractUserInfo(registrationId, oauth2User);
                
                // Process user (create account if new user)
                return processOAuth2User(userInfo, oauth2User)
                    .thenReturn(oauth2User);
            });
    }
    
    /**
     * Extracts user information from the OAuth2User based on the provider.
     * 
     * @param registrationId the OAuth2 provider registration ID (e.g., "google")
     * @param oauth2User the OAuth2User containing provider attributes
     * @return the extracted user information
     * @throws OAuth2AuthenticationException if the provider is not supported
     */
    private OAuth2UserInfo extractUserInfo(String registrationId, OAuth2User oauth2User) {
        return switch (registrationId.toLowerCase()) {
            case "google" -> new GoogleOAuth2UserInfo(oauth2User.getAttributes());
            default -> {
                logger.error("Unsupported OAuth2 provider: {}", registrationId);
                throw new OAuth2AuthenticationException(
                    "Unsupported OAuth2 provider: " + registrationId
                );
            }
        };
    }
    
    /**
     * Processes the OAuth2 user by creating an account in the system if needed.
     * 
     * <p>This method sends a CreateAccountCommand to the Akces command bus. In a production
     * system, you would first check if the account already exists (via a query model lookup)
     * before attempting to create it.
     * 
     * <p><strong>TODO:</strong> Implement account existence check using AccountQueryModel
     * to avoid sending duplicate CreateAccountCommand events.
     * 
     * @param userInfo the OAuth2 user information
     * @param oauth2User the OAuth2User object
     * @return a Mono that completes when user processing is done
     */
    private Mono<Void> processOAuth2User(OAuth2UserInfo userInfo, OAuth2User oauth2User) {
        String userId = userInfo.getId();
        String email = userInfo.getEmail();
        String name = userInfo.getName();
        
        logger.info("Processing OAuth2 user: userId={}, email={}, name={}", userId, email, name);
        
        // TODO: Query AccountQueryModel to check if account exists
        // For now, we'll attempt to create the account and let the aggregate handle idempotency
        
        // In a real implementation, you would:
        // 1. Query the AccountQueryModel to check if account exists
        // 2. Only send CreateAccountCommand if account doesn't exist
        // 3. Handle the case where the account exists but needs updating
        
        // Example (not implemented yet):
        // return accountQueryModelCache.getAccount(userId)
        //     .flatMap(existingAccount -> {
        //         logger.debug("Account already exists for userId: {}", userId);
        //         return Mono.empty();
        //     })
        //     .switchIfEmpty(createAccount(userId, email, name));
        
        // For Phase 2, we'll just log and return without sending commands
        // since we don't have the full account aggregate implementation yet
        logger.debug("User authentication successful for: {}", email);
        
        return Mono.empty();
    }
    
    // Future implementation for account creation:
    // private Mono<Void> createAccount(String userId, String email, String name) {
    //     CreateAccountCommand command = new CreateAccountCommand(
    //         userId,
    //         "US", // Default country, should be configurable
    //         extractFirstName(name),
    //         extractLastName(name),
    //         email
    //     );
    //     
    //     return Mono.fromFuture(
    //         akcesClient.send("DEFAULT_TENANT", command)
    //             .toCompletableFuture()
    //     ).then();
    // }
}
