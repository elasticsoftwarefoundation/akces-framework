package org.elasticsoftware.authapi.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.UUID;

/**
 * Custom UserDetails implementation for JWT authentication.
 */
public class JwtUserDetails implements UserDetails {
    
    private final String username;
    private final UUID uuid;
    private final Collection<? extends GrantedAuthority> authorities;
    
    public JwtUserDetails(String username, UUID uuid, Collection<? extends GrantedAuthority> authorities) {
        this.username = username;
        this.uuid = uuid;
        this.authorities = authorities;
    }
    
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }
    
    @Override
    public String getPassword() {
        return null; // Not needed for JWT authentication
    }
    
    @Override
    public String getUsername() {
        return username;
    }
    
    public UUID getUuid() {
        return uuid;
    }
    
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }
    
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }
    
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
    
    @Override
    public boolean isEnabled() {
        return true;
    }
}