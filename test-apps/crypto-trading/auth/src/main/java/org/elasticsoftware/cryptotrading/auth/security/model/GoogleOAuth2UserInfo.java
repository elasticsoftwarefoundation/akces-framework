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
 * Google OAuth2 user information implementation.
 * 
 * <p>This class maps Google-specific attribute names to the standard
 * {@link OAuth2UserInfo} interface. Google returns user information with
 * the following attributes:
 * <ul>
 *   <li>sub - The unique Google user ID</li>
 *   <li>email - The user's email address</li>
 *   <li>name - The user's full name</li>
 *   <li>picture - The user's profile picture URL</li>
 *   <li>email_verified - Whether the email has been verified</li>
 * </ul>
 * 
 * @see <a href="https://developers.google.com/identity/protocols/oauth2/openid-connect#obtainuserinfo">Google OpenID Connect</a>
 */
public record GoogleOAuth2UserInfo(Map<String, Object> attributes) implements OAuth2UserInfo {
    
    @Override
    public String getId() {
        return (String) attributes.get("sub");
    }
    
    @Override
    public String getEmail() {
        return (String) attributes.get("email");
    }
    
    @Override
    public String getName() {
        return (String) attributes.get("name");
    }
    
    @Override
    public String getImageUrl() {
        return (String) attributes.get("picture");
    }
    
    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }
    
    /**
     * Returns whether the user's email has been verified by Google.
     * 
     * @return true if the email is verified, false otherwise
     */
    public Boolean isEmailVerified() {
        return (Boolean) attributes.get("email_verified");
    }
}
