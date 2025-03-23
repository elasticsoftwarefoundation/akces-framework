package org.elasticsoftware.authapi.domain.aggregates;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.authapi.domain.commands.ChangePasswordCommand;
import org.elasticsoftware.authapi.domain.commands.RegisterUserCommand;
import org.elasticsoftware.authapi.domain.commands.UpdateUserProfileCommand;
import org.elasticsoftware.authapi.domain.events.PasswordChangedEvent;
import org.elasticsoftware.authapi.domain.events.UserProfileUpdatedEvent;
import org.elasticsoftware.authapi.domain.events.UserRegisteredEvent;
import org.elasticsoftware.authapi.domain.events.errors.AccountNotActiveErrorEvent;
import org.elasticsoftware.authapi.domain.events.errors.InvalidCredentialsErrorEvent;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * User aggregate that handles user-related commands.
 */
@AggregateInfo(
        value = "User",
        stateClass = UserState.class,
        generateGDPRKeyOnCreate = true,
        indexed = true,
        indexName = "Users")
public class User implements Aggregate<UserState> {
    
    private final PasswordEncoder passwordEncoder;
    
    @AggregateIdentifier
    private UUID userId;
    
    @Override
    public String getName() {
        return "User";
    }
    
    @Override
    public Class<UserState> getStateClass() {
        return UserState.class;
    }
    
    public User(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }
    
    /**
     * Handles user registration.
     */
    @CommandHandler(create = true, produces = {UserRegisteredEvent.class}, errors = {})
    public Stream<DomainEvent> handle(RegisterUserCommand cmd, UserState isNull) {
        // Generate salt and hash the password
        String salt = generateSalt();
        String passwordHash = passwordEncoder.encode(cmd.password() + salt);
        
        // Create profile data map
        Map<String, String> profileData = cmd.profileData() != null
                ? new HashMap<>(cmd.profileData())
                : new HashMap<>();
        
        // Emit UserRegisteredEvent
        return Stream.of(new UserRegisteredEvent(
                cmd.userId(),
                cmd.email(),
                cmd.username(),
                passwordHash,
                salt,
                UserStatus.PENDING_VERIFICATION,
                profileData,
                Instant.now()
        ));
    }
    
    /**
     * Handles user profile updates.
     */
    @CommandHandler(produces = {UserProfileUpdatedEvent.class}, errors = {AccountNotActiveErrorEvent.class})
    public Stream<DomainEvent> handle(UpdateUserProfileCommand cmd, UserState state) {
        if (state.status() == UserStatus.DEACTIVATED || state.status() == UserStatus.BLOCKED) {
            return Stream.of(new AccountNotActiveErrorEvent(cmd.userId()));
        }
        
        return Stream.of(new UserProfileUpdatedEvent(
                cmd.userId(),
                cmd.profileData(),
                Instant.now()
        ));
    }
    
    /**
     * Handles password changes.
     */
    @CommandHandler(produces = {PasswordChangedEvent.class}, errors = {AccountNotActiveErrorEvent.class, InvalidCredentialsErrorEvent.class})
    public Stream<DomainEvent> handle(ChangePasswordCommand cmd, UserState state) {
        if (state.status() == UserStatus.DEACTIVATED || state.status() == UserStatus.BLOCKED) {
            return Stream.of(new AccountNotActiveErrorEvent(cmd.userId()));
        }
        
        // Verify current password
        if (!passwordEncoder.matches(cmd.currentPassword() + state.salt(), state.passwordHash())) {
            return Stream.of(new InvalidCredentialsErrorEvent(cmd.userId()));
        }
        
        // Generate new salt and hash the password
        String newSalt = generateSalt();
        String newPasswordHash = passwordEncoder.encode(cmd.newPassword() + newSalt);
        
        return Stream.of(new PasswordChangedEvent(
                cmd.userId(),
                newPasswordHash,
                newSalt,
                Instant.now()
        ));
    }
    
    /**
     * Creates initial user state from UserRegisteredEvent.
     */
    @EventSourcingHandler(create = true)
    public UserState handle(UserRegisteredEvent event, UserState isNull) {
        return new UserState(
                event.userId(),
                event.email(),
                event.username(),
                event.passwordHash(),
                event.salt(),
                event.status(),
                event.profileData(),
                new SecuritySettings(false, false),
                false,
                event.createdAt(),
                event.createdAt()
        );
    }
    
    /**
     * Updates user state when profile is updated.
     */
    @EventSourcingHandler
    public UserState handle(UserProfileUpdatedEvent event, UserState state) {
        return state.withUpdatedProfile(event.profileData());
    }
    
    /**
     * Updates user state when password is changed.
     */
    @EventSourcingHandler
    public UserState handle(PasswordChangedEvent event, UserState state) {
        return state.withUpdatedPassword(event.passwordHash(), event.salt());
    }
    
    /**
     * Generates a random salt for password hashing.
     */
    private String generateSalt() {
        return UUID.randomUUID().toString();
    }
    
    // Error events moved to their own classes
}