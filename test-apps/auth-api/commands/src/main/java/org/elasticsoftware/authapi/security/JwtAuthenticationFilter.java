package org.elasticsoftware.authapi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Key;
import java.util.Collections;
import java.util.UUID;

/**
 * Filter to authenticate requests using JWT tokens.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    // In a real application, this should be retrieved from a secure configuration
    private static final Key SIGNING_KEY = Keys.hmacShaKeyFor(
            "ThisIsAVeryLongSecretKeyForHS512SignatureAlgorithmWhichNeedsToBeReplaced".getBytes());
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // Get authorization header
        String header = request.getHeader("Authorization");
        
        // Check if the Authorization header is present and has the correct format
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // Extract and validate the token
        try {
            String token = header.substring(7);
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(SIGNING_KEY)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            
            // Check if the token is an access token
            if (!"access".equals(claims.get("type"))) {
                filterChain.doFilter(request, response);
                return;
            }
            
            // Get user ID from subject claim
            String userId = claims.getSubject();
            
            // Create user details
            JwtUserDetails userDetails = new JwtUserDetails(
                    userId,
                    UUID.fromString(userId),
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
            );
            
            // Create authentication token
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );
            
            // Set authentication in security context
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception e) {
            // Token is invalid, continue with unauthenticated user
        }
        
        filterChain.doFilter(request, response);
    }
}