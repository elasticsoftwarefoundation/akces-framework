package org.elasticsoftware.authapi.domain.aggregates;

import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.annotations.PIIData;

import java.time.Instant;
import java.util.*;

/**
 * Represents the state of an authentication session.
 */
@AggregateStateInfo(type = "AuthSession")
public record AuthSessionState(
        UUID sessionId,
        UUID userId,
        List<TokenInfo> issuedTokens,
        Instant lastActivity,
        DeviceInfo deviceInfo,
        @PIIData String ipAddress,
        Instant expiresAt
) implements AggregateState {

    public AuthSessionState {
        if (issuedTokens == null) {
            issuedTokens = Collections.emptyList();
        } else {
            // Create a defensive copy
            issuedTokens = Collections.unmodifiableList(new ArrayList<>(issuedTokens));
        }
    }
    
    public AuthSessionState withNewToken(TokenInfo tokenInfo) {
        List<TokenInfo> updatedTokens = new ArrayList<>(issuedTokens);
        updatedTokens.add(tokenInfo);
        
        return new AuthSessionState(
                sessionId,
                userId,
                updatedTokens,
                Instant.now(),
                deviceInfo,
                ipAddress,
                expiresAt
        );
    }
    
    public AuthSessionState withRevokedToken(UUID tokenId) {
        List<TokenInfo> updatedTokens = new ArrayList<>();
        
        for (TokenInfo token : issuedTokens) {
            if (token.tokenId().equals(tokenId)) {
                updatedTokens.add(token.revoke());
            } else {
                updatedTokens.add(token);
            }
        }
        
        return new AuthSessionState(
                sessionId,
                userId,
                updatedTokens,
                Instant.now(),
                deviceInfo,
                ipAddress,
                expiresAt
        );
    }
    
    public AuthSessionState withExtendedExpiration(Instant newExpiresAt) {
        return new AuthSessionState(
                sessionId,
                userId,
                issuedTokens,
                Instant.now(),
                deviceInfo,
                ipAddress,
                newExpiresAt
        );
    }
    
    public boolean hasTokenById(UUID tokenId) {
        return issuedTokens.stream()
                .anyMatch(token -> token.tokenId().equals(tokenId) && !token.revoked());
    }
    
    public Optional<TokenInfo> findTokenByFingerprint(String fingerprint) {
        return issuedTokens.stream()
                .filter(token -> token.fingerprint().equals(fingerprint) && !token.revoked())
                .findFirst();
    }
    
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
    
    /**
     * Represents information about an issued token.
     */
    public record TokenInfo(
            UUID tokenId,
            String tokenType,
            String fingerprint,
            Instant issuedAt,
            Instant expiresAt,
            boolean revoked,
            Instant revokedAt
    ) {
        public TokenInfo(UUID tokenId, String tokenType, String fingerprint, Instant issuedAt, Instant expiresAt) {
            this(tokenId, tokenType, fingerprint, issuedAt, expiresAt, false, null);
        }
        
        public TokenInfo revoke() {
            return new TokenInfo(
                    tokenId,
                    tokenType,
                    fingerprint,
                    issuedAt,
                    expiresAt,
                    true,
                    Instant.now()
            );
        }
        
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
    
    /**
     * Represents information about a device used for authentication.
     */
    public record DeviceInfo(
            String deviceType,
            String userAgent,
            String deviceName
    ) {
    }
    
    @Override
    public String getAggregateId() {
        return sessionId.toString();
    }
}