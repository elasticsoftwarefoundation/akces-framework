package org.elasticsoftware.authapi.config;

import org.elasticsoftware.authapi.domain.aggregates.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Configuration for Auth API command service.
 */
@Configuration
public class AuthApiConfig {
    
    /**
     * Create User aggregate bean.
     */
    @Bean
    public User userAggregate(PasswordEncoder passwordEncoder) {
        return new User(passwordEncoder);
    }
}