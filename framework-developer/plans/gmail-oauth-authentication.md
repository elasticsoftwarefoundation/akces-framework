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

### Google Cloud Platform Setup

Before implementing the OAuth flow, you must configure OAuth 2.0 credentials in Google Cloud Platform (GCP):

#### Step 1: Create a GCP Project
1. Navigate to [Google Cloud Console](https://console.cloud.google.com/)
2. Click "Select a Project" → "New Project"
3. Enter project name (e.g., "Akces Crypto Trading")
4. Click "Create"

#### Step 2: Configure OAuth Consent Screen
1. Navigate to "APIs & Services" → "OAuth consent screen"
2. Click "Get Started" if prompted
3. Select "External" user type (allows any Gmail account to authenticate)
   - **Note**: Choose "Internal" only if using Google Workspace and restricting to organization users
4. Click "Create"
5. Fill in the application information:
   - **App name**: "Akces Crypto Trading" (shown to users on consent screen)
   - **User support email**: Your email address
   - **App logo**: Optional (for branding)
   - **Application home page**: Optional (e.g., `https://your-domain.com`)
   - **Application privacy policy link**: Optional (required for production/verification)
   - **Application terms of service link**: Optional (required for production/verification)
   - **Authorized domains**: Add your domain(s) if applicable
   - **Developer contact information**: Your email address (for Google notifications)
6. Click "Save and Continue"
7. On "Scopes" page, click "Add or Remove Scopes"
8. Select these OAuth 2.0 scopes (non-sensitive, no verification required):
   - `openid` - Required for OpenID Connect authentication
   - `.../auth/userinfo.profile` - Access to user's basic profile (name, picture)
   - `.../auth/userinfo.email` - Access to user's email address and verification status
   
   **Scope Details:**
   - These scopes return standard OpenID Connect claims in the ID token
   - Claims include: `sub` (user ID), `name`, `email`, `email_verified`, `picture`, etc.
   - No sensitive scopes means no Google verification process needed for testing
9. Click "Update" and then "Save and Continue"
10. On "Test users" page (required for unpublished apps):
    - Add test user email addresses (Gmail accounts that can authenticate during development)
    - Click "Add Users" and enter email addresses
    - Click "Add" for each email
    - Click "Save and Continue"
    - **Important**: Only listed test users can authenticate until app is published
11. Review summary and click "Back to Dashboard"

**Reference**: [Configure OAuth Consent Screen - Google Developers](https://developers.google.com/workspace/guides/configure-oauth-consent)

#### Step 3: Create OAuth 2.0 Credentials
1. Navigate to "APIs & Services" → "Credentials"
2. Click "Create Credentials" → "OAuth client ID"
3. If prompted to configure consent screen, complete Step 2 first
4. Select "Application type": "Web application"
5. Enter name: "Akces Crypto Trading Web Client"
6. Under "Authorized JavaScript origins" (optional for server-side OAuth):
   - `http://localhost:8080` (for local development)
   - `https://your-production-domain.com` (for production)
   - **Note**: Only needed if using client-side JavaScript OAuth flows
7. Under "Authorized redirect URIs" (required):
   - `http://localhost:8080/login/oauth2/code/google` (for local development)
   - `https://your-production-domain.com/login/oauth2/code/google` (for production)
   - **Important**: URIs must exactly match what Spring Security sends (including path)
   - **Security**: Production URIs must use HTTPS; only localhost can use HTTP
8. Click "Create"
9. A dialog will appear with your credentials:
   - **Client ID**: Copy this value (format: `123456789-abc...xyz.apps.googleusercontent.com`)
   - **Client Secret**: Copy this value (format: `GOCSPX-...`)
   - **Important**: Client secret is only shown once; store it securely immediately
10. Click "OK"

**Reference**: [Setting up OAuth 2.0 - Google API Console Help](https://support.google.com/googleapi/answer/6158849)

#### Step 4: Store Credentials Securely

**For Local Development:**
Create a `.env` file in the project root (add to `.gitignore`):
```bash
GOOGLE_CLIENT_ID=123456789-abc...xyz.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-...
```

**For Kubernetes Deployment:**
Create a Kubernetes secret:
```bash
kubectl create secret generic oauth2-credentials \
  --from-literal=google-client-id='123456789-abc...xyz.apps.googleusercontent.com' \
  --from-literal=google-client-secret='GOCSPX-...' \
  -n crypto-trading
```

Then reference in deployment:
```yaml
env:
  - name: GOOGLE_CLIENT_ID
    valueFrom:
      secretKeyRef:
        name: oauth2-credentials
        key: google-client-id
  - name: GOOGLE_CLIENT_SECRET
    valueFrom:
      secretKeyRef:
        name: oauth2-credentials
        key: google-client-secret
```

#### Publishing Your App (Production)

For production use with unlimited users:

1. Navigate to "APIs & Services" → "OAuth consent screen"
2. Click "Publish App"
3. For apps using only non-sensitive scopes (like `openid`, `profile`, `email`):
   - Publishing is instant, no Google verification required
   - Any Google account user can authenticate
4. For apps using sensitive or restricted scopes:
   - Must submit for Google verification
   - Provide detailed justification for each scope
   - Verification can take several weeks

**Reference**: [Google OAuth 2.0 Policies](https://support.google.com/cloud/answer/9110914)

#### Important Security Notes:
- **Never commit credentials to source control** - Always use environment variables or secrets
- **Rotate client secrets periodically** - Good security practice
- **Use HTTPS in production** - OAuth 2.0 requires encrypted transport
- **Monitor usage** - Check "APIs & Services" → "Dashboard" in GCP Console for quotas and usage
- **Keep test users updated** - Manage list as team changes during development
- **Review authorized domains** - Ensure only your domains are listed

**Additional References:**
- [Using OAuth 2.0 for Web Server Applications - Google Developers](https://developers.google.com/identity/protocols/oauth2/web-server)
- [OAuth 2.0 Scopes for Google APIs](https://developers.google.com/identity/protocols/oauth2/scopes)
- [OpenID Connect - Google for Developers](https://developers.google.com/identity/openid-connect/openid-connect)

### Architecture Overview

The solution will implement the OAuth 2.0 Authorization Code flow with Spring Security:

```
User Browser → Frontend → Spring Security OAuth2 Client → Google OAuth → Account Creation
```

### Key Components

#### 1. Authentication Module Structure

**Commands Service** (OAuth + JWT generation):
```
commands/
├── src/main/java/org/elasticsoftware/cryptotrading/
│   ├── security/
│   │   ├── config/
│   │   │   ├── SecurityConfig.java              # Main security configuration
│   │   │   ├── OAuth2ClientConfig.java          # OAuth2 client configuration
│   │   │   └── JwtConfig.java                   # JWT configuration properties
│   │   ├── jwt/
│   │   │   ├── JwtTokenProvider.java            # JWT generation and validation
│   │   │   └── JwtAuthenticationFilter.java     # JWT filter for requests
│   │   ├── handler/
│   │   │   ├── OAuth2LoginSuccessHandler.java   # Post-login (generates JWT)
│   │   │   └── OAuth2LoginFailureHandler.java   # Error handler
│   │   ├── service/
│   │   │   ├── OAuth2UserService.java           # Load/create user from OAuth
│   │   │   └── UserAccountService.java          # Bridge to Akces commands
│   │   └── model/
│   │       ├── OAuth2UserInfo.java              # OAuth user info interface
│   │       └── GoogleOAuth2UserInfo.java        # Google-specific impl
│   └── web/
│       ├── AuthController.java                   # Auth endpoints (login, refresh)
│       └── dto/
│           ├── TokenResponse.java                # JWT token response
│           ├── RefreshTokenRequest.java          # Token refresh request
│           └── UserProfile.java                  # User profile DTO
```

**Queries Service** (JWT validation only):
```
queries/
├── src/main/java/org/elasticsoftware/cryptotrading/
│   ├── security/
│   │   ├── config/
│   │   │   ├── SecurityConfig.java              # Security configuration
│   │   │   └── JwtConfig.java                   # JWT configuration properties
│   │   └── jwt/
│   │       ├── JwtTokenProvider.java            # JWT validation (shared code)
│   │       └── JwtAuthenticationFilter.java     # JWT filter for requests
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

**Existing Command (v1):**
```java
@CommandInfo(type = "CreateAccount", version = 1)
public record CreateAccountCommand(
    @AggregateIdentifier @NotNull String userId,
    @NotNull String country,
    @NotNull String firstName,
    @NotNull String lastName,
    @NotNull String email
) implements Command
```

**New Command (v2 - with OAuth support):**
```java
@CommandInfo(type = "CreateAccount", version = 2)
public record CreateAccountCommandV2(
    @AggregateIdentifier @NotNull String userId,
    @NotNull String country,
    @NotNull String firstName,
    @NotNull String lastName,
    @NotNull String email,
    String oauthProvider,      // Optional: "google", "github", etc.
    String oauthProviderId     // Optional: provider-specific user ID
) implements Command {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

**New Event (v2 - with OAuth support):**
```java
@DomainEventInfo(type = "AccountCreated", version = 2)
public record AccountCreatedEventV2(
    @AggregateIdentifier @NotNull String userId,
    @NotNull String country,
    @NotNull String firstName,
    @NotNull String lastName,
    @NotNull String email,
    String oauthProvider,
    String oauthProviderId
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

**Command Upcasting Handler:**
```java
@UpcastingHandler
public CreateAccountCommandV2 upcast(CreateAccountCommand cmd) {
    return new CreateAccountCommandV2(
        cmd.userId(),
        cmd.country(),
        cmd.firstName(),
        cmd.lastName(),
        cmd.email(),
        null,  // No OAuth provider for v1 commands
        null   // No OAuth provider ID for v1 commands
    );
}
```

**Event Upcasting Handler:**
```java
@UpcastingHandler
public AccountCreatedEventV2 upcast(AccountCreatedEvent event) {
    return new AccountCreatedEventV2(
        event.userId(),
        event.country(),
        event.firstName(),
        event.lastName(),
        event.email(),
        null,  // No OAuth provider for v1 events
        null   // No OAuth provider ID for v1 events
    );
}
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
- JWT token generation and validation
- CORS configuration for frontend

#### 5. JWT-Based API Security

After successful OAuth authentication, the system will issue JWT tokens to secure REST API endpoints.

**JWT Token Flow:**
```
1. User authenticates via OAuth → Google validates credentials
2. OAuth2LoginSuccessHandler generates JWT access token (short-lived, 15 minutes)
3. OAuth2LoginSuccessHandler generates JWT refresh token (long-lived, 7 days)
4. Tokens returned to client in response body (NOT cookies for API-first approach)
5. Client includes access token in Authorization header: `Bearer <token>`
6. Spring Security validates JWT signature and claims on each request
7. Client uses refresh token to obtain new access token when expired
```

**JWT Token Structure:**

*Access Token Claims:*
```json
{
  "sub": "user-id-uuid",
  "email": "user@gmail.com",
  "name": "John Doe",
  "oauth_provider": "google",
  "oauth_provider_id": "google-user-id",
  "iat": 1234567890,
  "exp": 1234568790,
  "type": "access"
}
```

*Refresh Token Claims:*
```json
{
  "sub": "user-id-uuid",
  "iat": 1234567890,
  "exp": 1235172690,
  "type": "refresh"
}
```

**GCP Service Account JWT Signing with JWKS:**

Instead of using a shared secret (HS256), we'll use **GCP Service Account with RS256** for JWT signing:

1. **Create GCP Service Account:**
   ```bash
   gcloud iam service-accounts create akces-jwt-signer \
     --display-name="Akces JWT Signing Service Account"
   ```

2. **Generate and Download Service Account Key:**
   ```bash
   gcloud iam service-accounts keys create service-account-key.json \
     --iam-account=akces-jwt-signer@PROJECT_ID.iam.gserviceaccount.com
   ```

3. **Service Account JWKS Endpoint:**
   - Google automatically exposes public keys for service accounts at:
   ```
   https://www.googleapis.com/service_accounts/v1/jwk/akces-jwt-signer@PROJECT_ID.iam.gserviceaccount.com
   ```
   - This JWKS endpoint is used by clients to validate JWT signatures
   - No need to manually manage public key distribution
   - Key rotation is handled automatically by Google

4. **Configuration (Commands Service - JWT Generation):**
```yaml
app:
  jwt:
    gcp-service-account-key: ${GCP_SERVICE_ACCOUNT_KEY}  # Service account JSON key
    access-token-expiration: 900000      # 15 minutes in milliseconds
    refresh-token-expiration: 604800000  # 7 days in milliseconds
    issuer: akces-crypto-trading
    service-account-email: akces-jwt-signer@PROJECT_ID.iam.gserviceaccount.com
```

5. **Configuration (Queries Service - JWT Validation):**
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # Nimbus automatically validates using JWKS
          jwk-set-uri: https://www.googleapis.com/service_accounts/v1/jwk/akces-jwt-signer@PROJECT_ID.iam.gserviceaccount.com
          issuer-uri: akces-crypto-trading
```

**Key Classes for JWT Implementation:**

*JwtTokenProvider.java (Commands Service - uses GCP Service Account for signing):*
```java
@Component
public class JwtTokenProvider {
    private final ServiceAccountCredentials credentials;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;
    private final String issuer;
    private final String serviceAccountEmail;
    
    public JwtTokenProvider(@Value("${app.jwt.gcp-service-account-key}") String keyJson,
                           @Value("${app.jwt.access-token-expiration}") long accessTokenExpiration,
                           @Value("${app.jwt.refresh-token-expiration}") long refreshTokenExpiration,
                           @Value("${app.jwt.issuer}") String issuer,
                           @Value("${app.jwt.service-account-email}") String serviceAccountEmail) throws IOException {
        this.credentials = ServiceAccountCredentials
            .fromStream(new ByteArrayInputStream(keyJson.getBytes(StandardCharsets.UTF_8)));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
        this.issuer = issuer;
        this.serviceAccountEmail = serviceAccountEmail;
    }
    
    public String generateAccessToken(OAuth2User oauth2User, String userId) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(accessTokenExpiration);
        
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
            .subject(userId)
            .issuer(issuer)
            .audience(List.of("akces-api"))
            .expirationTime(Date.from(expiry))
            .issueTime(Date.from(now))
            .claim("email", oauth2User.getAttribute("email"))
            .claim("name", oauth2User.getAttribute("name"))
            .claim("oauth_provider", oauth2User.getAttribute("oauth_provider"))
            .claim("oauth_provider_id", oauth2User.getAttribute("oauth_provider_id"))
            .claim("type", "access")
            .build();
        
        try {
            // Sign with GCP Service Account private key (RS256)
            JWSSigner signer = new RSASSASigner(credentials.getPrivateKey());
            SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(credentials.getPrivateKeyId())
                    .build(),
                claimsSet
            );
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (JOSEException e) {
            throw new RuntimeException("Failed to sign JWT", e);
        }
    }
    
    public String generateRefreshToken(String userId) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(refreshTokenExpiration);
        
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
            .subject(userId)
            .issuer(issuer)
            .audience(List.of("akces-api"))
            .expirationTime(Date.from(expiry))
            .issueTime(Date.from(now))
            .claim("type", "refresh")
            .build();
        
        try {
            JWSSigner signer = new RSASSASigner(credentials.getPrivateKey());
            SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(credentials.getPrivateKeyId())
                    .build(),
                claimsSet
            );
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (JOSEException e) {
            throw new RuntimeException("Failed to sign refresh token", e);
        }
    }
}
```

*JWT Validation (Queries Service - Spring Security auto-configures NimbusJwtDecoder):*
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    // Spring Security + Nimbus automatically validates using JWKS URI
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );
        
        return http.build();
    }
    
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        // Map JWT claims to granted authorities if needed
        return converter;
    }
}
```

**Benefits of GCP Service Account with JWKS:**
- **No Shared Secret Management**: Private key never leaves Commands service
- **Automatic Key Rotation**: Google manages key lifecycle
- **Public Key Distribution**: JWKS endpoint provides public keys automatically
- **Industry Standard**: RS256 is more secure than HS256 for distributed systems
- **Scalability**: Queries service validates locally using cached public keys from JWKS
- **Zero Downtime Key Rotation**: JWKS supports multiple active keys simultaneously

*JwtAuthenticationFilter.java:*
```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider jwtTokenProvider;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) {
        String token = extractTokenFromHeader(request);
        if (token != null && jwtTokenProvider.validateToken(token)) {
            Authentication auth = getAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}
```

**API Endpoint Security Configuration:**

*SecurityConfig.java (HTTP Security):*
```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())  // Disable for stateless JWT
        .sessionManagement(session -> 
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            // Public endpoints
            .requestMatchers("/auth/login", "/auth/callback", "/auth/refresh").permitAll()
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            
            // Protected endpoints - require authentication
            .requestMatchers("/v1/accounts/**").authenticated()
            .requestMatchers("/v1/wallets/**").authenticated()
            .requestMatchers("/v1/orders/**").authenticated()
            
            // All other requests require authentication
            .anyRequest().authenticated()
        )
        .oauth2Login(oauth2 -> oauth2
            .successHandler(oAuth2LoginSuccessHandler)
            .failureHandler(oAuth2LoginFailureHandler)
        )
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    
    return http.build();
}
```

**Securing Command Service Endpoints:**

The Commands service REST controllers will be updated to require authentication:

```java
@RestController
@RequestMapping("/v{version:1}/accounts")
public class AccountCommandController {
    private final AkcesClient akcesClient;
    
    @PostMapping
    @PreAuthorize("isAuthenticated()")  // Require authentication
    public Mono<ResponseEntity<AccountOutput>> createAccount(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AccountInput input) {
        
        String userId = jwt.getSubject();  // Extract userId from JWT
        
        return Mono.fromCompletionStage(akcesClient.send("TEST", input.toCommand(userId)))
            .map(List::getFirst)
            .map(domainEvent -> {
                AccountCreatedEventV2 event = (AccountCreatedEventV2) domainEvent;
                AccountOutput output = new AccountOutput(
                    event.userId(), 
                    input.country(), 
                    input.firstName(), 
                    input.lastName(), 
                    input.email()
                );
                return ResponseEntity.ok(output);
            });
    }
}
```

**Securing Query Service Endpoints:**

The Queries service REST controllers will also require JWT authentication:

```java
@RestController
@RequestMapping("/v{version:1}/accounts")
public class AccountQueryController {
    private final QueryModelCache<AccountQueryModelState> cache;
    
    @GetMapping("/{userId}")
    @PreAuthorize("isAuthenticated()")  // Require authentication
    public Mono<ResponseEntity<AccountQueryModelState>> getAccount(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId) {
        
        // Verify user can only access their own account
        if (!jwt.getSubject().equals(userId)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }
        
        return Mono.fromCallable(() -> cache.get("Users", userId))
            .map(state -> ResponseEntity.ok(state))
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }
}
```

**Token Refresh Endpoint:**

*AuthController.java:*
```java
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final JwtTokenProvider jwtTokenProvider;
    private final QueryModelCache<AccountQueryModelState> accountCache;
    
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refreshToken(
            @RequestBody RefreshTokenRequest request) {
        
        String refreshToken = request.refreshToken();
        
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        Claims claims = jwtTokenProvider.extractClaims(refreshToken);
        if (!"refresh".equals(claims.get("type"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        String userId = claims.getSubject();
        
        // Load user details from query model
        AccountQueryModelState account = accountCache.get("Users", userId);
        
        // Generate new access token
        String newAccessToken = jwtTokenProvider.generateAccessToken(
            createOAuth2User(account), 
            userId
        );
        
        return ResponseEntity.ok(new TokenResponse(newAccessToken, refreshToken));
    }
}
```

**Response DTOs:**

```java
public record TokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn
) {
    public TokenResponse(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, "Bearer", 900);  // 15 minutes
    }
}

public record RefreshTokenRequest(String refreshToken) {}
```

**Client Usage Example (Java with Spring WebClient):**

```java
@Service
public class AkcesApiClient {
    private final WebClient webClient;
    private String accessToken;
    private String refreshToken;
    
    public AkcesApiClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
            .baseUrl("http://localhost:8080")
            .build();
    }
    
    /**
     * Authenticate using OAuth callback and store tokens
     */
    public void authenticate(String authCode) {
        TokenResponse response = webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/auth/callback")
                .queryParam("code", authCode)
                .build())
            .retrieve()
            .bodyToMono(TokenResponse.class)
            .block();
        
        this.accessToken = response.accessToken();
        this.refreshToken = response.refreshToken();
    }
    
    /**
     * Make authenticated API call with automatic token refresh
     */
    public Mono<AccountQueryModelState> getAccount(String userId) {
        return webClient.get()
            .uri("/v1/accounts/{userId}", userId)
            .headers(headers -> headers.setBearerAuth(accessToken))
            .retrieve()
            .onStatus(HttpStatusCode::is4xxClientError, clientResponse -> {
                if (clientResponse.statusCode() == HttpStatus.UNAUTHORIZED) {
                    // Token expired, refresh and retry
                    return Mono.error(new TokenExpiredException("Access token expired"));
                }
                return Mono.error(new RuntimeException("API call failed"));
            })
            .bodyToMono(AccountQueryModelState.class)
            .onErrorResume(TokenExpiredException.class, ex -> {
                // Refresh token and retry
                return refreshAccessToken()
                    .flatMap(newToken -> {
                        this.accessToken = newToken;
                        return getAccount(userId); // Retry with new token
                    });
            });
    }
    
    /**
     * Refresh access token using refresh token
     */
    private Mono<String> refreshAccessToken() {
        return webClient.post()
            .uri("/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RefreshTokenRequest(refreshToken))
            .retrieve()
            .bodyToMono(TokenResponse.class)
            .map(TokenResponse::accessToken);
    }
    
    /**
     * Create account (authenticated endpoint)
     */
    public Mono<AccountOutput> createAccount(AccountInput input) {
        return webClient.post()
            .uri("/v1/accounts")
            .headers(headers -> headers.setBearerAuth(accessToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(input)
            .retrieve()
            .bodyToMono(AccountOutput.class)
            .onErrorResume(TokenExpiredException.class, ex -> 
                refreshAccessToken()
                    .flatMap(newToken -> {
                        this.accessToken = newToken;
                        return createAccount(input);
                    })
            );
    }
    
    // Custom exception for token expiration
    private static class TokenExpiredException extends RuntimeException {
        public TokenExpiredException(String message) {
            super(message);
        }
    }
}
```

**Usage in Application:**

```java
@SpringBootApplication
public class CryptoTradingClientApp {
    
    public static void main(String[] args) {
        SpringApplication.run(CryptoTradingClientApp.class, args);
    }
    
    @Bean
    public CommandLineRunner demo(AkcesApiClient apiClient) {
        return args -> {
            // Simulate OAuth callback with authorization code
            String authCode = "4/0AY0e-g7..."; // From OAuth redirect
            apiClient.authenticate(authCode);
            
            // Make authenticated API calls
            AccountQueryModelState account = apiClient
                .getAccount("user-123")
                .block();
            
            System.out.println("Account: " + account);
            
            // Create new account
            AccountInput input = new AccountInput("US", "John", "Doe", "john@example.com");
            AccountOutput output = apiClient
                .createAccount(input)
                .block();
            
            System.out.println("Created account: " + output);
        };
    }
}
```

**Alternative: Using RestTemplate (Synchronous):**

```java
@Service
public class AkcesApiClientSync {
    private final RestTemplate restTemplate;
    private String accessToken;
    private String refreshToken;
    
    public AkcesApiClientSync(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
            .rootUri("http://localhost:8080")
            .build();
    }
    
    public void authenticate(String authCode) {
        ResponseEntity<TokenResponse> response = restTemplate.getForEntity(
            "/auth/callback?code={code}",
            TokenResponse.class,
            authCode
        );
        
        TokenResponse tokens = response.getBody();
        this.accessToken = tokens.accessToken();
        this.refreshToken = tokens.refreshToken();
    }
    
    public AccountQueryModelState getAccount(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        
        try {
            ResponseEntity<AccountQueryModelState> response = restTemplate.exchange(
                "/v1/accounts/{userId}",
                HttpMethod.GET,
                entity,
                AccountQueryModelState.class,
                userId
            );
            return response.getBody();
        } catch (HttpClientErrorException.Unauthorized ex) {
            // Refresh token and retry
            refreshAccessToken();
            return getAccount(userId);
        }
    }
    
    private void refreshAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<RefreshTokenRequest> entity = new HttpEntity<>(
            new RefreshTokenRequest(refreshToken),
            headers
        );
        
        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
            "/auth/refresh",
            entity,
            TokenResponse.class
        );
        
        this.accessToken = response.getBody().accessToken();
    }
}
```

**Security Considerations for JWT:**

1. **GCP Service Account Key Management:**
   - Store service account JSON key in Kubernetes secret, never in code or repository
   - Restrict service account permissions (only needs JWT signing capability)
   - Monitor service account usage via GCP Cloud Audit Logs
   - Rotate service account keys periodically (Google recommends every 90 days)
   - Use Workload Identity in GKE for key-less authentication (advanced)

2. **JWKS Endpoint Security:**
   - JWKS endpoint is public (only contains public keys, safe to expose)
   - Queries service caches JWKS keys to minimize network calls
   - Spring Security automatically refreshes keys when new `kid` (Key ID) is encountered
   - Google handles key rotation automatically, supports multiple active keys

3. **Token Storage:**
   - Client should store tokens in memory or secure storage
   - Avoid localStorage for browser apps (XSS risk)
   - For server-to-server communication, store in environment or secret manager
   - Tokens are self-contained, no server-side session required

4. **Token Validation:**
   - Validate RS256 signature using JWKS public keys on every request
   - Check expiration time (`exp` claim)
   - Verify issuer (`iss` claim) matches expected value
   - Verify audience (`aud` claim) matches your API identifier
   - Implement token revocation list for logout (optional, requires Redis or database)

5. **HTTPS Only:**
   - All token transmission must use HTTPS in production
   - GCP Service Account keys must never be transmitted over HTTP
   - OAuth redirect URIs must use HTTPS (except localhost for development)

#### 6. OAuth2 User Service

Custom `OAuth2UserService` implementation:
1. Receives OAuth2 user info from provider
2. Extracts user details (email, name, provider ID)
3. Checks if account exists (by email or provider ID)
4. If new user: sends `CreateAccountCommandV2` with OAuth details
5. If existing user: updates last login timestamp
6. Returns Spring Security OAuth2User object with JWT tokens

#### 7. API Endpoints

**New Authentication Endpoints:**
- `GET /auth/login` - Initiates OAuth flow (redirect to Google)
- `GET /auth/callback` - OAuth callback endpoint (returns JWT tokens)
- `POST /auth/refresh` - Refresh access token using refresh token
- `GET /auth/user` - Get current authenticated user profile (requires JWT)
- `POST /auth/logout` - Logout endpoint (optional token revocation)

**Protected Account Endpoints (require JWT):**
- `POST /v1/accounts` - Create account (authenticated only, uses JWT subject as userId)
- `GET /v1/accounts/{userId}` - Get account details (owner only)

**Protected Wallet Endpoints (require JWT):**
- `POST /v1/wallets` - Create wallet (authenticated users)
- `POST /v1/wallets/{walletId}/credit` - Credit wallet (owner only)
- `GET /v1/wallets/{walletId}` - Get wallet details (owner only)

**Protected Order Endpoints (require JWT):**
- `POST /v1/orders` - Place order (authenticated users)
- `GET /v1/orders/{orderId}` - Get order details (owner only)
- `GET /v1/orders` - List user's orders (authenticated users)

#### 8. Frontend Integration

The frontend will:
1. Redirect users to `/auth/login` to start OAuth flow
2. Receive JWT tokens from `/auth/callback` after successful authentication
3. Store access token and refresh token securely (memory or secure storage)
4. Include access token in `Authorization: Bearer <token>` header for all API requests
5. Implement token refresh logic when access token expires (401 response)
6. Call `/auth/user` with JWT to get user profile
7. Handle authentication errors and redirect to login when refresh token expires

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
1. **Add Maven Dependencies** to `commands/pom.xml` and `queries/pom.xml`:
   - `spring-boot-starter-security`
   - `spring-boot-starter-oauth2-client`
   - `spring-security-oauth2-resource-server` (for JWT validation via Nimbus)
   - `spring-security-oauth2-jose` (includes Nimbus JOSE+JWT library)
   - `google-auth-library-oauth2-http` (for GCP Service Account JWT signing)

2. **Create GCP Service Account**:
   - Create service account in GCP Console for JWT signing
   - Download service account JSON key
   - Note the JWKS endpoint URL for public key distribution
   - Store service account key in Kubernetes secret

3. **Create Security Package Structure**:
   - Create all directories listed in component structure
   - Add placeholder classes with proper package declarations
   - Add JWT-related classes: `JwtTokenProvider` (Commands), `SecurityConfig` (both services)

4. **Update application.properties**:
   - Commands service: Add GCP service account key path, expiration times
   - Queries service: Add JWKS URI for JWT validation
   - Both: Add OAuth2 configuration with Google credentials
   - Configure stateless session management

### Phase 2: Core Security Implementation
5. **Implement JWT Token Provider (Commands Service)**:
   - `JwtTokenProvider` using GCP Service Account for RS256 signing
   - Access token generation with user claims using Nimbus JWT
   - Refresh token generation with minimal claims
   - Use `ServiceAccountCredentials` from google-auth-library

6. **Configure JWT Validation (Queries Service)**:
   - Configure Spring Security OAuth2 Resource Server with JWKS URI
   - Spring Security automatically uses NimbusJwtDecoder to validate tokens
   - Configure JWT authentication converter for claims mapping
   - No custom validation code needed - handled by Nimbus

7. **Implement OAuth2UserInfo Interface**:
   - Create base interface for provider-agnostic user info
   - Implement GoogleOAuth2UserInfo with Google attribute mapping

8. **Implement SecurityConfig (Commands Service)**:
   - Configure OAuth2 login
   - Define URL patterns (public vs. protected)
   - Configure stateless session management
   - Set up CORS for development

9. **Implement SecurityConfig (Queries Service)**:
   - Configure OAuth2 Resource Server with JWKS URI
   - Define URL patterns (public vs. protected)
   - Stateless session management
   - Nimbus automatically validates JWT signatures

10. **Implement OAuth2UserService**:
    - Custom user details service
    - Integration with Akces command bus
    - User lookup and creation logic

11. **Implement Success/Failure Handlers**:
    - OAuth2LoginSuccessHandler generates JWT tokens using GCP Service Account
    - Return tokens in response body (JSON format)
    - OAuth2LoginFailureHandler for error handling and logging

### Phase 3: Domain Model Updates
10. **Update Account Aggregate**:
    - Add new command handler for CreateAccountCommandV2
    - Add event sourcing handler for AccountCreatedEventV2
    - Keep existing handlers for backward compatibility

11. **Create Command and Event V2 Classes**:
    - `CreateAccountCommandV2` with OAuth fields
    - `AccountCreatedEventV2` with OAuth fields
    - Upcasting handlers from v1 to v2

12. **Update AccountState (Schema Version 2)**:
    - Add oauthProvider and oauthProviderId fields
    - Create upcasting handler from v1 to v2

13. **Create UserAccountService**:
    - Bridge service between OAuth and Akces
    - Handle command submission and response processing
    - Implement user lookup by email/provider ID in query models

### Phase 4: REST API Layer
14. **Create AuthController**:
    - OAuth callback endpoint (returns JWT tokens)
    - Token refresh endpoint
    - User profile endpoint (JWT protected)
    - Logout endpoint (optional token revocation)

15. **Update AccountCommandController**:
    - Add JWT authentication requirement with `@PreAuthorize`
    - Extract userId from JWT claims
    - Implement authorization checks (user can only access own resources)

16. **Update Query Controllers**:
    - Add JWT authentication to AccountQueryController
    - Add JWT authentication to WalletQueryController
    - Add JWT authentication to OrdersQueryController
    - Implement resource-level authorization

17. **Create Response DTOs**:
    - `TokenResponse` for JWT tokens
    - `RefreshTokenRequest` for token refresh
    - `UserProfile` for authenticated user information

### Phase 5: Configuration and Testing
18. **Create Application Configuration**:
    - Document required environment variables (Google credentials, JWT secret)
    - Create example application-local.yml for development
    - Add OAuth2 client credentials setup instructions (GCP Console steps)

19. **Testing**:
    - Unit tests for JwtTokenProvider
    - Unit tests for OAuth2UserService
    - Unit tests for success/failure handlers with JWT generation
    - Integration tests with mock OAuth provider (WireMock)
    - Integration tests for JWT authentication filter
    - E2E test for complete OAuth + JWT flow (optional, manual acceptable)

20. **Documentation**:
    - Update README with complete OAuth setup (including GCP steps)
    - Document JWT token flow and usage
    - Document environment variable requirements
    - Add API documentation with JWT authentication examples
    - Add troubleshooting guide
    - Document how to add new OAuth providers

### Phase 6: Deployment Considerations
21. **Kubernetes Configuration**:
    - Add OAuth client credentials and JWT secret as Kubernetes secrets
    - Update deployment manifests with environment variables
    - Configure ingress for OAuth callbacks
    - Ensure both Commands and Queries services have JWT configuration

22. **Security Considerations**:
    - HTTPS requirement for OAuth and JWT transmission (document)
    - JWT secret key management and rotation
    - Token expiration and refresh strategy
    - CSRF protection not needed for JWT (stateless)
    - Rate limiting for auth and refresh endpoints (future enhancement)
    - Token revocation list for logout (optional, future enhancement)

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

<!-- JWT Token Support with Nimbus (included via spring-security-oauth2-jose) -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-oauth2-jose</artifactId>
    <!-- This dependency includes Nimbus JOSE+JWT library for JWT handling -->
</dependency>

<!-- GCP Service Account for JWT Signing (Commands Service only) -->
<dependency>
    <groupId>com.google.auth</groupId>
    <artifactId>google-auth-library-oauth2-http</artifactId>
    <version>1.23.0</version>
</dependency>
```

**Note on JWT Library Choice:**
- Spring Security uses **Nimbus JOSE+JWT** library internally via `spring-security-oauth2-jose`
- No need for separate JJWT dependencies - Nimbus is the recommended and integrated solution
- `NimbusJwtDecoder` validates JWTs using JWKS URI (Queries Service)
- Nimbus `JWSSigner` and `JWTClaimsSet` for token generation (Commands Service)
- GCP Service Account provides private key for RS256 signing
- Nimbus supports RS256, ES256, HS256, and other signing algorithms out of the box

### Compatibility Notes
- Spring Boot 4.0.0 includes Spring Security 7.0+
- **Nimbus JOSE+JWT library** is included automatically via `spring-security-oauth2-jose`
- OAuth2 client is fully compatible with WebFlux (reactive stack)
- Java 25 language features can be used throughout
- No conflicts with existing Akces framework dependencies
- Nimbus supports JWKS URI-based validation for GCP Service Account tokens

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
   - **Decision**: Use JWT-based authentication for stateless, scalable API access
   - Access tokens: 15 minutes, Refresh tokens: 7 days
   - Tokens returned in response body, not cookies (API-first design)

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

### Google OAuth 2.0 Documentation
- [Using OAuth 2.0 to Access Google APIs](https://developers.google.com/identity/protocols/oauth2) - Main OAuth 2.0 guide
- [Using OAuth 2.0 for Web Server Applications](https://developers.google.com/identity/protocols/oauth2/web-server) - Server-side OAuth flow
- [OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect) - Authentication with Google
- [OAuth 2.0 Scopes for Google APIs](https://developers.google.com/identity/protocols/oauth2/scopes) - Available scopes reference
- [Configure OAuth Consent Screen](https://developers.google.com/workspace/guides/configure-oauth-consent) - Consent screen setup
- [Setting up OAuth 2.0 - API Console Help](https://support.google.com/googleapi/answer/6158849) - Creating credentials
- [Create Access Credentials - Google Workspace](https://developers.google.com/workspace/guides/create-credentials) - Credential types
- [Manage OAuth Clients - GCP Console Help](https://support.google.com/cloud/answer/15549257) - Managing OAuth clients
- [Google OAuth 2.0 Policies](https://support.google.com/cloud/answer/9110914) - Publishing and verification

### Google Cloud Platform (GCP) Documentation
- [Service Accounts Overview](https://cloud.google.com/iam/docs/service-account-overview) - Understanding service accounts
- [Creating and Managing Service Account Keys](https://cloud.google.com/iam/docs/keys-create-delete) - Key management
- [Authenticating as a Service Account](https://cloud.google.com/docs/authentication/production) - Service account authentication
- [Service Account JWKS Endpoint](https://cloud.google.com/api-gateway/docs/authenticating-users-jwt) - JWT validation with JWKS
- [Google Auth Library for Java](https://github.com/googleapis/google-auth-library-java) - Service account credentials library
- [Workload Identity for GKE](https://cloud.google.com/kubernetes-engine/docs/how-to/workload-identity) - Key-less authentication

### Spring Security Documentation
- [Spring Security OAuth2 Client Documentation](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html) - OAuth2 client integration
- [Spring Security OAuth2 Resource Server - JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) - JWT resource server with Nimbus
- [OAuth 2.0 Resource Server With Spring Security - Baeldung](https://www.baeldung.com/spring-security-oauth-resource-server) - Practical guide
- [JWS + JWK in Spring Security OAuth2 - Baeldung](https://www.baeldung.com/spring-security-oauth2-jws-jwk) - JWKS integration
- [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) - Upgrade guide

### OAuth 2.0 & OpenID Connect Standards
- [RFC 6749 - OAuth 2.0 Authorization Framework](https://tools.ietf.org/html/rfc6749) - OAuth 2.0 specification
- [RFC 6750 - OAuth 2.0 Bearer Token Usage](https://tools.ietf.org/html/rfc6750) - Bearer token standard
- [RFC 7517 - JSON Web Key (JWK)](https://tools.ietf.org/html/rfc7517) - JWK specification
- [RFC 7519 - JSON Web Token (JWT)](https://tools.ietf.org/html/rfc7519) - JWT specification
- [OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html) - OIDC specification
- [OpenID Connect Scopes - Auth0 Docs](https://auth0.com/docs/get-started/apis/scopes/openid-connect-scopes) - Scope reference

### JWT Libraries and Tools
- [Nimbus JOSE+JWT Library](https://connect2id.com/products/nimbus-jose-jwt) - Official Nimbus documentation
- [Nimbus JOSE+JWT on GitHub](https://github.com/connect2id/nimbus-jose-jwt) - Source code and examples
- [JWT.io](https://jwt.io/) - JWT debugger and documentation

---

**Document Version**: 2.0  
**Created**: 2025-12-06  
**Last Updated**: 2025-12-06  
**Author**: Framework Developer  
**Status**: Updated with Architect Feedback

**Changelog:**
- v2.0: Updated to use Nimbus JWT library (via Spring Security), GCP Service Account with JWKS for JWT signing/validation, converted client examples to Java
- v1.0: Initial plan with Google OAuth setup, JWT architecture, and implementation phases
