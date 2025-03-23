# Authentication API using Akces Framework

This is a demonstration authentication service built using the Akces Framework. It provides user registration, authentication, session management, and multi-factor authentication for mobile or web applications.

## Architecture

The application follows the CQRS (Command Query Responsibility Segregation) and Event Sourcing patterns, implemented using the Akces Framework.

### Components

- **Commands Service**: Handles write operations like user registration, login, and profile updates
- **Queries Service**: Provides optimized read models for API endpoints
- **Aggregate Services**: Maintains state using event sourcing

### Key Aggregates

1. **UserAggregate**: Manages user account lifecycle
2. **AuthSessionAggregate**: Handles authentication sessions and tokens
3. **MFADeviceAggregate**: Manages multi-factor authentication devices

## Getting Started

### Prerequisites

- Java 17 or higher
- Apache Kafka
- Schema Registry
- Maven

### Running the Application

1. Start Kafka and Schema Registry
2. Build the application:
   ```bash
   mvn clean package
   ```
3. Start the Command Service:
   ```bash
   java -jar commands/target/akces-auth-api-commands-0.9.2-SNAPSHOT.jar
   ```
4. Start the Query Service:
   ```bash
   java -jar queries/target/akces-auth-api-queries-0.9.2-SNAPSHOT.jar
   ```

## API Endpoints

### Authentication

- `POST /api/auth/register` - Register a new user
- `POST /api/auth/login` - Authenticate user
- `POST /api/auth/refresh` - Refresh access token
- `POST /api/auth/logout` - End user session

### User Management

- `GET /api/users/profile/{userId}` - Get user profile
- `PUT /api/users/profile/{userId}` - Update user profile
- `PUT /api/users/password/{userId}` - Change password

### Security Audit

- `GET /api/audit/events/{userId}` - Get security audit events for a user

## Technology Stack

- **Backend**: Spring Boot, Akces Framework
- **Database**: H2 (for query models)
- **Messaging**: Apache Kafka
- **Schema Registry**: Confluent Schema Registry
- **Security**: JWT, BCrypt for password hashing

## Development

### Project Structure

```
auth-api/
├── aggregates/          # Domain model and aggregate definitions
├── commands/            # Command service
└── queries/             # Query service
```

### Building and Testing

Run tests with:
```bash
mvn test
```

## Security Features

- Secure password storage with bcrypt hashing
- JWT-based authentication with short-lived access tokens
- Token revocation mechanism
- PII data protection
- Comprehensive security audit trail

## License

[MIT License](LICENSE)