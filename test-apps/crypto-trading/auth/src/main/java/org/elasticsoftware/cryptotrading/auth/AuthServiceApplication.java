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

package org.elasticsoftware.cryptotrading.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Auth Service Application - Handles OAuth authentication and JWT token generation.
 * 
 * <p>This service is responsible for:
 * <ul>
 *   <li>OAuth 2.0 authentication flow with Google (and other providers)</li>
 *   <li>JWT token generation using GCP Service Account</li>
 *   <li>User account creation via Akces command bus</li>
 *   <li>Account query services for OAuth user lookup</li>
 * </ul>
 * 
 * <p>This is a separate module from Commands and Queries services to provide
 * security isolation and independent deployment.
 */
@SpringBootApplication
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
