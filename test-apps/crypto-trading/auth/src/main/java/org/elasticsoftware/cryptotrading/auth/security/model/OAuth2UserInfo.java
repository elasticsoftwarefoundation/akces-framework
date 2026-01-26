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

package org.elasticsoftware.cryptotrading.auth.security.model;

import java.util.Map;

/**
 * Provider-agnostic interface for OAuth2 user information.
 * 
 * <p>This interface abstracts OAuth2 user attributes across different providers
 * (Google, GitHub, Facebook, etc.), allowing the application to work with
 * user information in a consistent way regardless of the OAuth2 provider.
 * 
 * <p>Implementations of this interface map provider-specific attribute names
 * to standard fields like ID, email, and name.
 */
public interface OAuth2UserInfo {
    
    /**
     * Returns the unique user identifier from the OAuth2 provider.
     * 
     * @return the user ID
     */
    String getId();
    
    /**
     * Returns the user's email address.
     * 
     * @return the email address
     */
    String getEmail();
    
    /**
     * Returns the user's display name.
     * 
     * @return the display name
     */
    String getName();
    
    /**
     * Returns the user's profile image URL.
     * 
     * @return the image URL, or null if not available
     */
    String getImageUrl();
    
    /**
     * Returns all raw attributes from the OAuth2 provider.
     * 
     * @return the attributes map
     */
    Map<String, Object> getAttributes();
}
