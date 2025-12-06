# Plan: Add Gmail OAuth Authentication to Crypto Trading Test Application

## Executive Summary

This plan outlines the implementation of OAuth 2.0 authentication using Gmail (Google) as the identity provider for the Crypto Trading test application. The solution will use Spring Boot 4.0 and Spring Security OAuth2 Client, allowing users to sign up and log in using their existing Gmail accounts. The architecture is designed to be extensible to support additional OAuth providers (GitHub, Microsoft, etc.) in the future.

## Current State Analysis

### Existing Architecture
- **Framework Version**: Spring Boot 4.0.0, Spring 7.0.1, Java 25
- **Application Structure**:
  - **Commands Module**: REST API for write operations (webflux-based)
  - **Queries Module**: REST API for read operations (webflux-based)
  - **Aggregates Module**: Domain logic and event sourcing
- **Account Management**:
  - `Account` aggregate with state containing userId, country, firstName, lastName, email
  - `CreateAccountCommand` to create new accounts
  - `AccountCommandController` exposes POST `/v1/accounts` endpoint
  - Currently generates random UUID for userId
  - No authentication or authorization in place

### Current Account Creation Flow
1. Client sends POST request to `/v1/accounts` with AccountInput (country, firstName, lastName, email)
2. Controller generates random UUID for userId
3. Controller sends CreateAccountCommand to Akces framework
4. Account aggregate processes command and emits AccountCreatedEvent
5. Response returns AccountOutput with generated userId

## Goals and Objectives

### Primary Goals
1. Enable users to sign up using their Gmail accounts via OAuth 2.0
2. Replace manual account creation with OAuth-based authentication
3. Store OAuth user information in the existing Account aggregate
4. Maintain backward compatibility with existing API structure

### Secondary Goals
1. Design extensible architecture for adding other OAuth providers (GitHub, Microsoft, etc.)
2. Implement proper session management
3. Secure REST API endpoints with authentication
4. Handle OAuth errors and edge cases gracefully

## Proposed Solution

### Architecture Overview

The solution will implement the OAuth 2.0 Authorization Code flow with Spring Security:

```
User Browser → Frontend → Spring Security OAuth2 Client → Google OAuth → Account Creation
```

### Key Components

#### 1. Authentication Module Structure
```
commands/
├── src/main/java/org/elasticsoftware/cryptotrading/
│   ├── security/
│   │   ├── config/
│   │   │   ├── SecurityConfig.java              # Main security configuration
│   │   │   └── OAuth2ClientConfig.java          # OAuth2 client configuration
│   │   ├── handler/
│   │   │   ├── OAuth2LoginSuccessHandler.java   # Post-login handler
│   │   │   └── OAuth2LoginFailureHandler.java   # Error handler
│   │   ├── service/
│   │   │   ├── OAuth2UserService.java           # Load/create user from OAuth
│   │   │   └── UserAccountService.java          # Bridge to Akces commands
│   │   └── model/
│   │       ├── OAuth2UserInfo.java              # OAuth user info interface
│   │       └── GoogleOAuth2UserInfo.java        # Google-specific impl
│   └── web/
│       ├── AuthController.java                   # Auth endpoints
│       └── dto/
│           └── UserProfile.java                  # User profile DTO
```

#### 2. Modified Account Aggregate

Update `AccountState` and related classes to include OAuth provider information:

```java
@AggregateStateInfo(type = "Account", version = 2)
public record AccountState(
    @NotNull String userId,
    @NotNull String country,
    @NotNull @PIIData String firstName,
    @NotNull @PIIData String lastName,
    @NotNull @PIIData String email,
    String oauthProvider,      // NEW: "google", "github", etc.
    String oauthProviderId     // NEW: provider-specific user ID
) implements AggregateState {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

**Schema Evolution Strategy**: Use upcasting handler to migrate v1 to v2 (add null values for new fields).

#### 3. New/Modified Commands and Events

**New Command:**
```java
@CommandInfo(type = "CreateAccountWithOAuth", version = 1)
public record CreateAccountWithOAuthCommand(
    @AggregateIdentifier @NotNull String userId,
    @NotNull String country,
    @NotNull String firstName,
    @NotNull String lastName,
    @NotNull String email,
    @NotNull String oauthProvider,
    @NotNull String oauthProviderId
) implements Command
```

**New Event:**
```java
@DomainEventInfo(type = "AccountCreatedWithOAuth", version = 1)
public record AccountCreatedWithOAuthEvent(
    @AggregateIdentifier @NotNull String userId,
    @NotNull String country,
    @NotNull String firstName,
    @NotNull String lastName,
    @NotNull String email,
    @NotNull String oauthProvider,
    @NotNull String oauthProviderId
) implements DomainEvent
```

#### 4. Security Configuration

**Key Configuration Properties** (application.yml):
```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope:
              - openid
              - profile
              - email
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
        provider:
          google:
            authorization-uri: https://accounts.google.com/o/oauth2/v2/auth
            token-uri: https://oauth2.googleapis.com/token
            user-info-uri: https://www.googleapis.com/oauth2/v3/userinfo
            user-name-attribute: sub
```

**SecurityConfig.java** (key aspects):
- Enable OAuth2 login
- Configure authorized redirect URIs
- Define public vs. protected endpoints
- Session management configuration
- CORS configuration for frontend

#### 5. OAuth2 User Service

Custom `OAuth2UserService` implementation:
1. Receives OAuth2 user info from provider
2. Extracts user details (email, name, provider ID)
3. Checks if account exists (by email or provider ID)
4. If new user: sends `CreateAccountWithOAuthCommand`
5. If existing user: updates last login timestamp
6. Returns Spring Security OAuth2User object

#### 6. API Endpoints

**New Authentication Endpoints:**
- `GET /auth/login` - Initiates OAuth flow (redirect to Google)
- `GET /auth/callback` - OAuth callback endpoint (handled by Spring Security)
- `GET /auth/user` - Get current authenticated user profile
- `POST /auth/logout` - Logout endpoint
- `GET /auth/status` - Check authentication status

**Modified Account Endpoints:**
- `POST /v1/accounts` - Keep for backward compatibility (optional manual creation)
- Add authentication requirement to existing endpoints

#### 7. Frontend Integration

The frontend will:
1. Redirect users to `/auth/login` to start OAuth flow
2. Handle callback and session establishment
3. Store session cookie for subsequent requests
4. Call `/auth/user` to get user profile
5. Include session cookie in all API requests

### Extensibility for Other Providers

The architecture is designed for easy extension:

1. **Provider-Specific User Info Classes**: Implement `OAuth2UserInfo` interface
2. **Provider Configuration**: Add new registration in application.yml
3. **Provider Detection**: Use registration ID to select appropriate user info extractor
4. **Common Service Layer**: `OAuth2UserService` handles all providers uniformly

**Example for GitHub:**
```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          github:
            client-id: ${GITHUB_CLIENT_ID}
            client-secret: ${GITHUB_CLIENT_SECRET}
            scope:
              - user:email
              - read:user
```

```java
public class GitHubOAuth2UserInfo implements OAuth2UserInfo {
    // GitHub-specific attribute mapping
}
```

## Implementation Steps

### Phase 1: Foundation Setup
1. **Add Maven Dependencies** to `commands/pom.xml`:
   - `spring-boot-starter-security`
   - `spring-boot-starter-oauth2-client`
   - `spring-security-oauth2-jose` (for JWT handling)

2. **Create Security Package Structure**:
   - Create all directories listed in component structure
   - Add placeholder classes with proper package declarations

3. **Update application.properties**:
   - Add OAuth2 configuration placeholders
   - Add security configuration (session timeout, cookie settings)

### Phase 2: Core Security Implementation
4. **Implement OAuth2UserInfo Interface**:
   - Create base interface for provider-agnostic user info
   - Implement GoogleOAuth2UserInfo with Google attribute mapping

5. **Implement SecurityConfig**:
   - Configure OAuth2 login
   - Define URL patterns (public vs. protected)
   - Configure session management
   - Set up CORS for development

6. **Implement OAuth2UserService**:
   - Custom user details service
   - Integration with Akces command bus
   - User lookup and creation logic

7. **Implement Success/Failure Handlers**:
   - OAuth2LoginSuccessHandler for post-login processing
   - OAuth2LoginFailureHandler for error handling and logging

### Phase 3: Domain Model Updates
8. **Update Account Aggregate**:
   - Add new command handler for OAuth account creation
   - Add event sourcing handler for OAuth account creation event
   - Keep existing handlers for backward compatibility

9. **Create New Command and Event Classes**:
   - `CreateAccountWithOAuthCommand`
   - `AccountCreatedWithOAuthEvent`

10. **Update AccountState (Schema Version 2)**:
    - Add oauthProvider and oauthProviderId fields
    - Create upcasting handler from v1 to v2

11. **Create UserAccountService**:
    - Bridge service between OAuth and Akces
    - Handle command submission and response processing
    - Implement user lookup by email/provider ID

### Phase 4: REST API Layer
12. **Create AuthController**:
    - Implement authentication status endpoints
    - User profile endpoint
    - Logout endpoint

13. **Update AccountCommandController**:
    - Add authentication to existing endpoints (optional)
    - Keep manual account creation for testing/admin purposes

14. **Create UserProfile DTO**:
    - Response object for authenticated user information

### Phase 5: Configuration and Testing
15. **Create Application Configuration**:
    - Document required environment variables
    - Create example application-local.yml for development
    - Add OAuth2 client credentials setup instructions

16. **Testing**:
    - Unit tests for OAuth2UserService
    - Unit tests for success/failure handlers
    - Integration tests with mock OAuth provider (Testcontainers or WireMock)
    - E2E test for complete OAuth flow (optional, manual testing acceptable)

17. **Documentation**:
    - Update README with OAuth setup instructions
    - Document environment variable requirements
    - Add troubleshooting guide
    - Document how to add new OAuth providers

### Phase 6: Deployment Considerations
18. **Kubernetes Configuration**:
    - Add OAuth client credentials as Kubernetes secrets
    - Update deployment manifests with environment variables
    - Configure ingress for OAuth callbacks

19. **Security Considerations**:
    - HTTPS requirement for OAuth (document)
    - Secure cookie settings (httpOnly, secure, sameSite)
    - CSRF protection configuration
    - Rate limiting for auth endpoints (future enhancement)

## Dependencies and Version Compatibility

### Required Dependencies
```xml
<!-- Spring Security OAuth2 Client -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

### Compatibility Notes
- Spring Boot 4.0.0 includes Spring Security 7.0+
- OAuth2 client is fully compatible with WebFlux (reactive stack)
- Java 25 language features can be used throughout
- No conflicts with existing Akces framework dependencies

## Security Considerations

### Authentication Security
1. **OAuth State Parameter**: Prevent CSRF attacks during OAuth flow
2. **Session Management**: Use secure, httpOnly cookies
3. **HTTPS Enforcement**: OAuth requires HTTPS in production
4. **Token Storage**: Never expose OAuth tokens to client

### Authorization (Future)
- Current implementation: Authenticated vs. anonymous
- Future: Role-based access control (RBAC)
- Future: Resource-based permissions

### Data Privacy
- OAuth provider emails are PII (already marked with @PIIData)
- Provider IDs should be treated as sensitive
- Comply with GDPR requirements (already supported by framework)

## Testing Strategy

### Unit Tests
- OAuth2UserInfo implementations
- UserAccountService command creation logic
- Success/failure handler logic

### Integration Tests
- Mock OAuth provider with WireMock
- Test complete authentication flow
- Test error scenarios (invalid token, network failure)

### Manual Testing
- End-to-end OAuth flow with real Google account
- Test multiple login/logout cycles
- Test session expiration
- Test concurrent sessions

## Risks and Mitigations

### Risk 1: OAuth Provider Downtime
**Mitigation**: Implement graceful degradation, clear error messages, retry logic

### Risk 2: User Email Changes
**Mitigation**: Use provider ID as primary identifier, not email

### Risk 3: Breaking Changes to Existing API
**Mitigation**: Keep backward compatibility, make authentication optional initially

### Risk 4: Session Management in Distributed Environment
**Mitigation**: Use Redis for distributed sessions (future enhancement) or JWT tokens

### Risk 5: Multiple Accounts with Same Email
**Mitigation**: Implement account linking mechanism (future enhancement)

## Future Enhancements

1. **Additional OAuth Providers**:
   - GitHub
   - Microsoft Azure AD
   - Facebook
   - Apple

2. **Account Linking**:
   - Link multiple OAuth providers to single account
   - Merge accounts with same email

3. **Multi-Factor Authentication**:
   - TOTP (Time-based One-Time Password)
   - SMS verification

4. **User Profile Management**:
   - Update user information
   - Change preferences
   - Delete account (GDPR compliance)

5. **Session Management**:
   - Redis-based distributed sessions
   - JWT token-based authentication
   - Refresh token mechanism

6. **Admin Features**:
   - User management dashboard
   - OAuth provider statistics
   - Security audit logs

## Open Questions for Architect Review

1. **Account ID Strategy**: Should we use OAuth provider ID as userId or continue generating UUIDs?
   - **Recommendation**: Keep UUID for userId, use provider ID as secondary identifier

2. **Multi-Provider Support**: How should we handle users signing in with different providers using the same email?
   - **Recommendation**: First provider wins, implement account linking in future

3. **Backward Compatibility**: Should we keep the manual account creation endpoint?
   - **Recommendation**: Yes, for testing and admin purposes

4. **Session vs. JWT**: Should we use session-based or JWT-based authentication?
   - **Recommendation**: Start with sessions (simpler), migrate to JWT later if needed

5. **Required Scopes**: What additional OAuth scopes should we request?
   - **Recommendation**: Start with basic profile and email, add more as needed

6. **Database Schema**: Do we need a separate users table or rely on event sourcing?
   - **Recommendation**: Rely on event sourcing and query models, add database model if query performance becomes an issue

## Approval and Next Steps

**Approval Required From**: Architect

**Next Steps After Approval**:
1. Begin Phase 1 implementation (Foundation Setup)
2. Create feature branch: `feature/gmail-oauth-authentication`
3. Implement in incremental commits with testing at each phase
4. Request code review after Phase 3 completion
5. Final testing and documentation before merge

## References

- [Spring Security OAuth2 Client Documentation](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html)
- [Google OAuth 2.0 Documentation](https://developers.google.com/identity/protocols/oauth2)
- [RFC 6749 - OAuth 2.0 Authorization Framework](https://tools.ietf.org/html/rfc6749)
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)

---

**Document Version**: 1.0  
**Created**: 2025-12-06  
**Author**: Framework Developer  
**Status**: Pending Architect Review
