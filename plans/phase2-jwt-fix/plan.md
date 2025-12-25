# Phase 2 JWT Fix: RS256 with JWKS Support

## Problem Statement
The current Phase 2 implementation incorrectly uses HS256 (HMAC with shared secret) for JWT signing and custom JWT validation logic. This violates the plan specification which requires:
- RS256 (RSA asymmetric signing) instead of HS256 (HMAC symmetric signing)
- JWKS endpoint for public key distribution
- Spring Security OAuth2 Resource Server for validation (not custom code)
- Nimbus JWT library for signing/validation

## Plan Overview

### 1. Auth Service Changes

#### 1.1 Update JwtTokenProvider
**File**: `/test-apps/crypto-trading/auth/src/main/java/org/elasticsoftware/cryptotrading/auth/security/jwt/JwtTokenProvider.java`

**Changes**:
- Replace manual JWT creation with Nimbus JWT library
- Use RS256 signing algorithm (RSA)
- For development/testing: Generate RSA key pair programmatically
- For production: Support loading private key from configuration
- Sign JWTs with private key using `JWSSigner`
- Remove HMAC-SHA256 code completely

**Configuration Properties**:
- `app.jwt.private-key` (optional): PEM-encoded RSA private key for production
- If not provided: Generate RSA key pair for dev/test

#### 1.2 Add JWKS Endpoint
**New File**: `/test-apps/crypto-trading/auth/src/main/java/org/elasticsoftware/cryptotrading/auth/security/controller/JwksController.java`

**Purpose**:
- Expose `/.well-known/jwks.json` endpoint
- Return public key in JWKS format
- Allow Commands/Queries services to validate JWT signatures

#### 1.3 RSA Key Pair Management
**New File**: `/test-apps/crypto-trading/auth/src/main/java/org/elasticsoftware/cryptotrading/auth/security/jwt/RsaKeyProvider.java`

**Purpose**:
- Generate or load RSA key pair
- Provide private key for signing
- Provide public key for JWKS endpoint
- Support configuration-based key loading

### 2. Commands Service Changes

#### 2.1 Remove Custom JWT Validation
**Delete File**: `/test-apps/crypto-trading/commands/src/main/java/org/elasticsoftware/cryptotrading/security/jwt/JwtAuthenticationManager.java`

#### 2.2 Update SecurityConfig
**File**: `/test-apps/crypto-trading/commands/src/main/java/org/elasticsoftware/cryptotrading/security/config/SecurityConfig.java`

**Changes**:
- Remove custom `JwtAuthenticationManager`
- Remove custom `AuthenticationWebFilter`
- Use Spring Security OAuth2 Resource Server configuration:
  ```java
  .oauth2ResourceServer(oauth2 -> oauth2
      .jwt(jwt -> jwt
          .jwkSetUri("http://auth-service/.well-known/jwks.json")
      )
  )
  ```
- Configure `ReactiveJwtAuthenticationConverter` for claims mapping

**Configuration Properties**:
- `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`: JWKS endpoint URL

### 3. Queries Service Changes

#### 3.1 Remove Custom JWT Validation
**Delete File**: `/test-apps/crypto-trading/queries/src/main/java/org/elasticsoftware/cryptotrading/security/jwt/JwtAuthenticationManager.java`

#### 3.2 Update SecurityConfig
**File**: `/test-apps/crypto-trading/queries/src/main/java/org/elasticsoftware/cryptotrading/security/config/SecurityConfig.java`

**Changes**: Same as Commands service (see 2.2)

### 4. Configuration Updates

#### 4.1 Auth Service application.yml
Add:
```yaml
app:
  jwt:
    # Optional: For production, provide PEM-encoded RSA private key
    # private-key: |
    #   -----BEGIN PRIVATE KEY-----
    #   ...
    #   -----END PRIVATE KEY-----
```

#### 4.2 Commands Service application.yml
Update:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${AUTH_SERVICE_URL:http://localhost:8080}/.well-known/jwks.json
```

#### 4.3 Queries Service application.yml
Update: Same as Commands service (see 4.2)

### 5. Test Updates

Update test configurations to:
- Use generated RSA keys for testing
- Configure test JWKS endpoint
- Update test security configurations

## Implementation Steps

1. **Auth Service**:
   - Create `RsaKeyProvider` component
   - Rewrite `JwtTokenProvider` with Nimbus JWT
   - Create `JwksController`
   - Update application.yml

2. **Commands Service**:
   - Delete `JwtAuthenticationManager`
   - Rewrite `SecurityConfig` to use OAuth2 Resource Server
   - Update application.yml
   - Update `TestSecurityConfig`

3. **Queries Service**:
   - Delete `JwtAuthenticationManager`
   - Rewrite `SecurityConfig` to use OAuth2 Resource Server
   - Update application.yml
   - Update `TestSecurityConfig`

4. **Testing**:
   - Compile all modules
   - Run unit tests
   - Verify JWKS endpoint works
   - Verify JWT validation works

## Key Dependencies

All required dependencies are already present:
- `spring-security-oauth2-jose` (contains Nimbus JWT)
- `spring-security-oauth2-resource-server`
- `spring-boot-starter-security`

## Security Benefits

1. **Asymmetric Signing**: Auth service keeps private key, resource servers only have public key
2. **No Shared Secrets**: Eliminates risk of secret leakage across services
3. **Standard JWKS**: Industry-standard key distribution mechanism
4. **Spring Security Integration**: Leverages battle-tested validation logic
5. **Production Ready**: Can easily integrate with GCP Service Account keys

## Backward Compatibility

This is a breaking change:
- Existing HS256 tokens will NOT work
- All services must be updated together
- Users must re-authenticate after deployment
