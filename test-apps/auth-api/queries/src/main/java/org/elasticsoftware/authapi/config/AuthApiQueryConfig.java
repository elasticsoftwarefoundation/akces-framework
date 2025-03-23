package org.elasticsoftware.authapi.config;

import org.elasticsoftware.authapi.query.models.AuthSessionQueryModel;
import org.elasticsoftware.authapi.query.models.SecurityAuditQueryModel;
import org.elasticsoftware.authapi.query.models.UserProfileQueryModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuration for Auth API query service.
 */
@Configuration
@EnableWebSecurity
public class AuthApiQueryConfig {
    
    /**
     * Create UserProfileQueryModel bean.
     */
    @Bean
    public UserProfileQueryModel userProfileQueryModel() {
        return new UserProfileQueryModel();
    }
    
    /**
     * Create AuthSessionQueryModel bean.
     */
    @Bean
    public AuthSessionQueryModel authSessionQueryModel() {
        return new AuthSessionQueryModel();
    }
    
    /**
     * Create SecurityAuditQueryModel bean.
     */
    @Bean
    public SecurityAuditQueryModel securityAuditQueryModel() {
        return new SecurityAuditQueryModel();
    }
    
    /**
     * Configure security for the query service.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().permitAll()
                );
        
        // Enable h2-console
        http.headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable()));
        
        return http.build();
    }
}