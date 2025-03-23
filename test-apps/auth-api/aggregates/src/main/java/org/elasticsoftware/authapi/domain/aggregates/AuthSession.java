package org.elasticsoftware.authapi.domain.aggregates;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.authapi.domain.aggregates.AuthSessionState.TokenInfo;
import org.elasticsoftware.authapi.domain.events.errors.InvalidTokenErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.SessionExpiredErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.TokenExpiredErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.TokenNotFoundErrorEvent;
import org.elasticsoftware.authapi.domain.commands.LogoutCommand;
import org.elasticsoftware.authapi.domain.commands.RefreshTokenCommand;
import org.elasticsoftware.authapi.domain.events.AuthTokenIssuedEvent;
import org.elasticsoftware.authapi.domain.events.AuthTokenRefreshedEvent;
import org.elasticsoftware.authapi.domain.events.AuthTokenRevokedEvent;
import org.elasticsoftware.authapi.domain.util.JwtUtils;
import org.elasticsoftware.authapi.domain.util.TokenUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Authentication Session aggregate that handles session-related commands.
 */
@AggregateInfo(
        value = "AuthSession",
        stateClass = AuthSessionState.class,
        indexed = true,
        indexName = "AuthSessions")
public class AuthSession implements Aggregate<AuthSessionState> {
    
    private static final Duration ACCESS_TOKEN_EXPIRATION = Duration.ofMinutes(15);
    private static final Duration REFRESH_TOKEN_EXPIRATION = Duration.ofDays(7);
    private static final Duration SESSION_EXPIRATION = Duration.ofDays(30);
    
    @AggregateIdentifier
    private UUID sessionId;
    
    @Override
    public String getName() {
        return "AuthSession";
    }
    
    @Override
    public Class<AuthSessionState> getStateClass() {
        return AuthSessionState.class;
    }
    
    /**
     * Handles token refresh requests.
     */
    @CommandHandler(produces = {AuthTokenRefreshedEvent.class, AuthTokenRevokedEvent.class}, errors = {SessionExpiredErrorEvent.class, InvalidTokenErrorEvent.class, TokenExpiredErrorEvent.class})
    public Stream<DomainEvent> handle(RefreshTokenCommand cmd, AuthSessionState state) {
        // Check if session is expired
        if (state.isExpired()) {
            return Stream.of(new SessionExpiredErrorEvent(cmd.sessionId()));
        }
        
        // Verify the refresh token belongs to this session
        String tokenFingerprint = TokenUtils.getTokenFingerprint(cmd.refreshToken());
        var tokenInfo = state.findTokenByFingerprint(tokenFingerprint);
        
        if (tokenInfo.isEmpty()) {
            return Stream.of(new InvalidTokenErrorEvent(cmd.sessionId()));
        }
        
        TokenInfo token = tokenInfo.get();
        
        // Check if token is expired
        if (token.isExpired()) {
            return Stream.of(new TokenExpiredErrorEvent(cmd.sessionId(), token.tokenId()));
        }
        
        // Generate new tokens
        UUID newTokenId = UUID.randomUUID();
        Instant accessTokenExpiresAt = Instant.now().plus(ACCESS_TOKEN_EXPIRATION);
        Instant refreshTokenExpiresAt = Instant.now().plus(REFRESH_TOKEN_EXPIRATION);
        
        String accessToken = JwtUtils.generateAccessToken(cmd.userId(), newTokenId, accessTokenExpiresAt);
        String refreshToken = JwtUtils.generateRefreshToken(cmd.userId(), newTokenId, refreshTokenExpiresAt);
        
        // First revoke the old token
        var events = new ArrayList<DomainEvent>();
        events.add(new AuthTokenRevokedEvent(
                cmd.sessionId(),
                cmd.userId(),
                token.tokenId(),
                Instant.now()
        ));
        
        // Then issue a new token
        events.add(new AuthTokenRefreshedEvent(
                cmd.userId(),
                cmd.sessionId(),
                token.tokenId(),
                newTokenId,
                accessToken,
                refreshToken,
                accessTokenExpiresAt,
                refreshTokenExpiresAt,
                cmd.deviceInfo(),
                cmd.ipAddress(),
                Instant.now()
        ));
        
        return events.stream();
    }
    
    /**
     * Handles logout requests.
     */
    @CommandHandler(produces = {AuthTokenRevokedEvent.class}, errors = {TokenNotFoundErrorEvent.class})
    public Stream<DomainEvent> handle(LogoutCommand cmd, AuthSessionState state) {
        // Check if the token exists
        if (!state.hasTokenById(cmd.tokenId())) {
            return Stream.of(new TokenNotFoundErrorEvent(cmd.sessionId(), cmd.tokenId()));
        }
        
        return Stream.of(new AuthTokenRevokedEvent(
                cmd.sessionId(),
                cmd.userId(),
                cmd.tokenId(),
                Instant.now()
        ));
    }
    
    /**
     * Creates initial session state from AuthTokenIssuedEvent.
     */
    @EventSourcingHandler(create = true)
    public AuthSessionState handle(AuthTokenIssuedEvent event, AuthSessionState isNull) {
        UUID tokenId = UUID.randomUUID();
        String tokenFingerprint = TokenUtils.getTokenFingerprint(event.refreshToken());
        
        var tokenInfo = new TokenInfo(
                tokenId,
                "REFRESH",
                tokenFingerprint,
                event.issuedAt(),
                event.refreshTokenExpiresAt()
        );
        
        Instant expiresAt = Instant.now().plus(SESSION_EXPIRATION);
        
        return new AuthSessionState(
                event.sessionId(),
                event.userId(),
                java.util.List.of(tokenInfo),
                event.issuedAt(),
                event.deviceInfo(),
                event.ipAddress(),
                expiresAt
        );
    }
    
    /**
     * Updates session state when a token is refreshed.
     */
    @EventSourcingHandler
    public AuthSessionState handle(AuthTokenRefreshedEvent event, AuthSessionState state) {
        String tokenFingerprint = TokenUtils.getTokenFingerprint(event.refreshToken());
        
        var tokenInfo = new TokenInfo(
                event.newTokenId(),
                "REFRESH",
                tokenFingerprint,
                event.refreshedAt(),
                event.refreshTokenExpiresAt()
        );
        
        return state.withRevokedToken(event.previousTokenId()).withNewToken(tokenInfo);
    }
    
    /**
     * Updates session state when a token is revoked.
     */
    @EventSourcingHandler
    public AuthSessionState handle(AuthTokenRevokedEvent event, AuthSessionState state) {
        return state.withRevokedToken(event.tokenId());
    }
    
    // Error events moved to their own classes
}