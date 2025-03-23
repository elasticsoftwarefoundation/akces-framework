package org.elasticsoftware.authapi.query.models;

import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.annotations.QueryModelEventHandler;
import org.elasticsoftware.akces.annotations.QueryModelInfo;
import org.elasticsoftware.akces.annotations.QueryModelStateInfo;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelState;
import org.elasticsoftware.authapi.domain.aggregates.AuthSessionState.DeviceInfo;
import org.elasticsoftware.authapi.domain.events.AuthTokenIssuedEvent;
import org.elasticsoftware.authapi.domain.events.AuthTokenRefreshedEvent;
import org.elasticsoftware.authapi.domain.events.AuthTokenRevokedEvent;

import java.time.Instant;
import java.util.*;

/**
 * Query model for authentication sessions.
 */
@QueryModelInfo(value = "AuthSessionQueryModel", indexName = "AuthSessions")
public class AuthSessionQueryModel implements QueryModel<AuthSessionQueryModel.AuthSessionState> {
    
    @Override
    public String getName() {
        return "AuthSessionQueryModel";
    }
    
    @Override
    public Class<AuthSessionState> getStateClass() {
        return AuthSessionState.class;
    }
    
    @Override
    public String getIndexName() {
        return "AuthSessions";
    }
    
    /**
     * State for authentication session query model.
     */
    @QueryModelStateInfo(type = "AuthSessionState")
    public record AuthSessionState(
            UUID sessionId,
            UUID userId,
            Set<UUID> activeTokenIds,
            Map<UUID, TokenInfo> tokenInfo,
            Instant lastActivity,
            DeviceInfo deviceInfo,
            @PIIData String ipAddress,
            Instant expiresAt
    ) implements QueryModelState {
        
        public AuthSessionState {
            if (activeTokenIds == null) {
                activeTokenIds = Collections.emptySet();
            } else {
                activeTokenIds = Collections.unmodifiableSet(new HashSet<>(activeTokenIds));
            }
            
            if (tokenInfo == null) {
                tokenInfo = Collections.emptyMap();
            } else {
                tokenInfo = Collections.unmodifiableMap(new HashMap<>(tokenInfo));
            }
        }
        
        /**
         * Checks if a token is active.
         */
        public boolean isTokenActive(UUID tokenId) {
            return activeTokenIds.contains(tokenId);
        }
        
        /**
         * Checks if the session is expired.
         */
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
        
        @Override
        public String getAggregateId() {
            return sessionId.toString();
        }
    }
    
    /**
     * Information about a token.
     */
    public record TokenInfo(
            UUID tokenId,
            String tokenType,
            Instant issuedAt,
            Instant expiresAt
    ) {
        /**
         * Checks if the token is expired.
         */
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
    
    /**
     * Creates a new session state when a token is issued.
     */
    @QueryModelEventHandler(create = true)
    public AuthSessionState handle(AuthTokenIssuedEvent event, AuthSessionState isNull) {
        UUID tokenId = UUID.randomUUID();
        
        var tokenInfo = Map.of(tokenId, new TokenInfo(
                tokenId,
                "REFRESH",
                event.issuedAt(),
                event.refreshTokenExpiresAt()
        ));
        
        return new AuthSessionState(
                event.sessionId(),
                event.userId(),
                Set.of(tokenId),
                tokenInfo,
                event.issuedAt(),
                event.deviceInfo(),
                event.ipAddress(),
                event.refreshTokenExpiresAt()
        );
    }
    
    /**
     * Updates session state when a token is refreshed.
     */
    @QueryModelEventHandler
    public AuthSessionState handle(AuthTokenRefreshedEvent event, AuthSessionState state) {
        // Add new token
        Map<UUID, TokenInfo> updatedTokenInfo = new HashMap<>(state.tokenInfo());
        updatedTokenInfo.put(event.newTokenId(), new TokenInfo(
                event.newTokenId(),
                "REFRESH",
                event.refreshedAt(),
                event.refreshTokenExpiresAt()
        ));
        
        // Update active tokens
        Set<UUID> updatedActiveTokens = new HashSet<>(state.activeTokenIds());
        updatedActiveTokens.remove(event.previousTokenId());
        updatedActiveTokens.add(event.newTokenId());
        
        return new AuthSessionState(
                state.sessionId(),
                state.userId(),
                updatedActiveTokens,
                updatedTokenInfo,
                event.refreshedAt(),
                state.deviceInfo(),
                state.ipAddress(),
                state.expiresAt()
        );
    }
    
    /**
     * Updates session state when a token is revoked.
     */
    @QueryModelEventHandler
    public AuthSessionState handle(AuthTokenRevokedEvent event, AuthSessionState state) {
        // Remove token from active tokens
        Set<UUID> updatedActiveTokens = new HashSet<>(state.activeTokenIds());
        updatedActiveTokens.remove(event.tokenId());
        
        return new AuthSessionState(
                state.sessionId(),
                state.userId(),
                updatedActiveTokens,
                state.tokenInfo(),
                Instant.now(),
                state.deviceInfo(),
                state.ipAddress(),
                state.expiresAt()
        );
    }
}