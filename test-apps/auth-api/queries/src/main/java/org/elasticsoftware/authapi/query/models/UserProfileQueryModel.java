package org.elasticsoftware.authapi.query.models;

import org.elasticsoftware.akces.annotations.PIIData;
import org.elasticsoftware.akces.annotations.QueryModelEventHandler;
import org.elasticsoftware.akces.annotations.QueryModelInfo;
import org.elasticsoftware.akces.annotations.QueryModelStateInfo;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akces.query.QueryModelState;
import org.elasticsoftware.authapi.domain.events.PasswordChangedEvent;
import org.elasticsoftware.authapi.domain.events.UserProfileUpdatedEvent;
import org.elasticsoftware.authapi.domain.events.UserRegisteredEvent;
import org.elasticsoftware.authapi.domain.aggregates.UserStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Query model for user profiles.
 */
@QueryModelInfo(value = "UserProfileQueryModel", indexName = "Users")
public class UserProfileQueryModel implements QueryModel<UserProfileQueryModel.UserProfileState> {
    
    @Override
    public String getName() {
        return "UserProfileQueryModel";
    }
    
    @Override
    public Class<UserProfileState> getStateClass() {
        return UserProfileState.class;
    }
    
    @Override
    public String getIndexName() {
        return "Users";
    }
    
    /**
     * State for user profile query model.
     */
    @QueryModelStateInfo(type = "UserProfileState")
    public record UserProfileState(
            UUID userId,
            @PIIData String email,
            String username,
            UserStatus status,
            Map<String, String> profileData,
            boolean mfaEnabled,
            Instant createdAt,
            Instant lastModifiedAt
    ) implements QueryModelState {
        
        public UserProfileState {
            if (profileData == null) {
                profileData = Collections.emptyMap();
            } else {
                profileData = Collections.unmodifiableMap(new HashMap<>(profileData));
            }
        }
        
        @Override
        public String getAggregateId() {
            return userId.toString();
        }
    }
    
    /**
     * Handles UserRegisteredEvent to create a new user profile.
     */
    @QueryModelEventHandler(create = true)
    public UserProfileState handle(UserRegisteredEvent event, UserProfileState isNull) {
        return new UserProfileState(
                event.userId(),
                event.email(),
                event.username(),
                event.status(),
                event.profileData(),
                false,
                event.createdAt(),
                event.createdAt()
        );
    }
    
    /**
     * Updates user profile when profile information is updated.
     */
    @QueryModelEventHandler
    public UserProfileState handle(UserProfileUpdatedEvent event, UserProfileState state) {
        return new UserProfileState(
                state.userId(),
                state.email(),
                state.username(),
                state.status(),
                event.profileData(),
                state.mfaEnabled(),
                state.createdAt(),
                event.updatedAt()
        );
    }
    
    /**
     * Updates last modified timestamp when password is changed.
     */
    @QueryModelEventHandler
    public UserProfileState handle(PasswordChangedEvent event, UserProfileState state) {
        return new UserProfileState(
                state.userId(),
                state.email(),
                state.username(),
                state.status(),
                state.profileData(),
                state.mfaEnabled(),
                state.createdAt(),
                event.changedAt()
        );
    }
}