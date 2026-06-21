# Auth Service

Authentication service for the Crypto Trading Akces Test Application.

## Overview

This module handles OAuth 2.0 authentication and JWT token generation for the Crypto Trading application. It is deployed separately from the Commands and Queries services to provide security isolation.

## Phase 1 Implementation Status

**Completed:**
- ✅ Basic module structure created
- ✅ Spring Boot application class (`AuthServiceApplication`)
- ✅ Spring Security OAuth2 Client dependencies added
- ✅ Spring Security OAuth2 Resource Server dependencies added
- ✅ Spring Security OAuth2 JOSE dependencies added (includes Nimbus JWT library)
- ✅ Database support (Spring Data JDBC, PostgreSQL, Liquibase)
- ✅ Kafka integration
- ✅ Akces Framework client library for command submission
- ✅ Basic application.yml configuration
- ✅ Package structure for security components

**Pending (Phase 2+):**
- OAuth2 security configuration
- JWT token provider with GCP Service Account
- OAuth2 user service
- Success/failure handlers
- Account database model and query service
- REST controllers for auth endpoints (/v1/auth/*)
- Liquibase database schema migrations
- Integration tests

## Architecture

The Auth Service is responsible for:

1. **OAuth 2.0 Authentication Flow**: Handling OAuth login with Google (and other providers in the future)
2. **JWT Token Generation**: Issuing JWT access and refresh tokens using GCP Service Account (RS256)
3. **User Account Management**: Creating new accounts via Akces command bus
4. **Account Query Service**: Looking up existing accounts by OAuth provider and email

## Package Structure

```
org.elasticsoftware.cryptotrading.auth/
├── AuthServiceApplication.java      # Spring Boot main class
├── security/
│   ├── config/                      # Security configuration classes
│   ├── jwt/                         # JWT token provider
│   ├── handler/                     # OAuth success/failure handlers
│   ├── service/                     # OAuth user service
│   ├── query/                       # Account query service
│   └── model/                       # OAuth user info models
└── web/
    ├── v1/                          # Versioned REST controllers
    └── dto/                         # Data transfer objects
```

## Configuration

### Environment Variables

The following environment variables are required (configured in `application.yml`):

- `GOOGLE_CLIENT_ID`: Google OAuth 2.0 client ID
- `GOOGLE_CLIENT_SECRET`: Google OAuth 2.0 client secret
- `DATABASE_URL`: PostgreSQL database URL
- `DATABASE_USERNAME`: Database username
- `DATABASE_PASSWORD`: Database password
- `KAFKA_BOOTSTRAP_SERVERS`: Kafka bootstrap servers
- `SCHEMA_REGISTRY_URL`: Confluent Schema Registry URL
- `GCP_SERVICE_ACCOUNT_KEY`: GCP Service Account JSON key (Phase 2+)
- `GCP_SERVICE_ACCOUNT_EMAIL`: GCP Service Account email (Phase 2+)

## Dependencies

### Key Dependencies Added

- **Spring Security OAuth2 Client**: OAuth 2.0 authentication flow
- **Spring Security OAuth2 Resource Server**: JWT validation (Nimbus-based)
- **Spring Security OAuth2 JOSE**: JWT/JWS/JWK support (includes Nimbus JOSE+JWT)
- **Spring Data JDBC**: Database access for account queries
- **PostgreSQL**: Database driver
- **Liquibase**: Database schema management
- **Akces Client**: Command submission to Commands service
- **Akces Query Support**: Query model support

### GCP Dependencies (Phase 2+)

The following dependencies will be added in Phase 2 for JWT signing:

- `com.google.auth:google-auth-library-oauth2-http` - GCP Service Account credentials
- `com.google.cloud:google-cloud-secretmanager` - Secret Manager for key storage (optional)

These are currently commented out in Phase 1 to avoid dependency convergence issues.

## Building

Build the auth module:

```bash
cd test-apps/crypto-trading/auth
mvn clean install
```

Build all modules:

```bash
cd test-apps/crypto-trading
mvn clean install
```

## Running

**Note**: The auth service cannot be fully run yet as Phase 2 implementation is required. Phase 1 only sets up the foundation.

Once Phase 2 is complete, run with:

```bash
cd test-apps/crypto-trading/auth
mvn spring-boot:run
```

The service will start on port 8080 (configurable via `server.port` in application.yml).

## API Endpoints (Planned)

The following endpoints will be implemented in Phase 2+:

- `GET /v1/auth/login` - Initiate OAuth flow
- `GET /v1/auth/callback` - OAuth callback endpoint (returns JWT tokens)
- `POST /v1/auth/refresh` - Refresh access token
- `GET /v1/auth/user` - Get authenticated user profile
- `POST /v1/auth/logout` - Logout (optional token revocation)

## Database Schema (Planned)

Liquibase migration will create the following table in Phase 3:

```sql
CREATE TABLE accounts (
    user_id VARCHAR(255) PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    first_name VARCHAR(255),
    oauth_provider VARCHAR(50) NOT NULL,
    oauth_provider_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (oauth_provider, oauth_provider_id)
);

CREATE INDEX idx_email ON accounts(email);
```

## Testing

Tests will be added in Phase 2+:

- Unit tests for JWT token provider
- Unit tests for OAuth user service
- Unit tests for database models
- Integration tests with mock OAuth provider
- End-to-end tests for complete OAuth flow

## Next Steps

See the [Gmail OAuth Authentication Plan](../../../../plans/gmail-oauth-authentication.md) for complete implementation details.

**Phase 2** will include:
- JWT token provider implementation
- OAuth2 security configuration
- OAuth2 user service
- Success/failure handlers

**Phase 3** will include:
- Account database model
- Account query service
- Liquibase migrations

**Phase 4** will include:
- REST API controllers
- Response DTOs
- Error handling

## License

Copyright 2022 - 2025 The Original Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
