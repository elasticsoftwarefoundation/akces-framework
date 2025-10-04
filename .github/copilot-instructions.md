# Copilot Instructions for Akces Framework

## Project Overview

The Akces Framework is a sophisticated CQRS (Command Query Responsibility Segregation) and Event Sourcing framework built on Apache Kafka. It provides a comprehensive infrastructure for building distributed, event-driven applications with a clear separation between write operations (commands) and read operations (queries).

**Key Technologies:**
- Java 21+
- Apache Kafka 3.x (KRaft mode)
- Spring Boot 3.x
- Maven for build management
- RocksDB for state management
- Confluent Schema Registry for schema management

## Repository Structure

```
akces-framework/
├── main/                          # Core framework modules
│   ├── api/                      # Core interfaces and annotations
│   ├── runtime/                  # Event sourcing runtime implementation
│   ├── shared/                   # Common utilities and GDPR support
│   ├── client/                   # Client library for command submission
│   ├── query-support/            # Query model and database model support
│   └── eventcatalog/             # API documentation generator
├── services/                      # Kubernetes operator for deployment
│   └── operator/                 # Akces Operator CRDs
├── test-apps/                     # Example applications
│   └── crypto-trading/           # Crypto trading example app
├── bom/                          # Bill of Materials for dependencies
└── prompts/                      # LLM prompt scripts for documentation
```

## Core Concepts

### Aggregates
- Domain entities that encapsulate business logic
- Process commands and emit events
- Maintain consistency boundaries
- Annotated with `@AggregateInfo`
- Must implement `Aggregate<T>` interface

### Commands
- Requests to perform actions that change state
- Annotated with `@CommandInfo`
- Must implement `Command` interface
- Validated using JSON Schema
- Routed to appropriate aggregate partitions

### Events
- Immutable records of facts that have occurred
- Annotated with `@DomainEventInfo`
- Must implement `DomainEvent` interface
- Stored in Kafka event store
- Used to reconstruct aggregate state

### Query Models
- Read-optimized projections of aggregate state
- Annotated with `@QueryModelInfo`
- Must implement `QueryModel<T>` interface
- Updated asynchronously from events
- Support caching for performance

### Database Models
- Persistent storage layer for query data
- Support JDBC and JPA
- Updated via event handlers
- Used for complex queries and reporting

### Process Managers
- Coordinate workflows across multiple aggregates
- Implement `ProcessManager<T, P>` interface
- React to events and send commands
- Maintain process state through events

## Coding Guidelines

### 1. Aggregate Design

When creating or modifying aggregates:

```java
@AggregateInfo(value = "Wallet", version = 1, indexed = true, indexName = "Wallets")
public final class Wallet implements Aggregate<WalletState> {
    
    @Override
    public Class<WalletState> getStateClass() {
        return WalletState.class;
    }
    
    // Use create = true for creation commands
    @CommandHandler(create = true, produces = {WalletCreatedEvent.class})
    public Stream<DomainEvent> create(CreateWalletCommand cmd, WalletState isNull) {
        // Validate and emit events
        return Stream.of(new WalletCreatedEvent(cmd.id()));
    }
    
    // Use create = true for creation event handlers
    @EventSourcingHandler(create = true)
    public WalletState create(WalletCreatedEvent event, WalletState isNull) {
        return new WalletState(event.id(), new ArrayList<>());
    }
    
    // Regular command handlers
    @CommandHandler(produces = {WalletCreditedEvent.class}, errors = {InvalidAmountErrorEvent.class})
    public Stream<DomainEvent> credit(CreditWalletCommand cmd, WalletState currentState) {
        // Business logic and validation
        if (cmd.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return Stream.of(new InvalidAmountErrorEvent(cmd.id()));
        }
        return Stream.of(new WalletCreditedEvent(cmd.id(), cmd.amount()));
    }
}
```

**Best Practices:**
- Keep aggregates focused on a single consistency boundary
- Always validate commands before emitting events
- Use error events instead of throwing exceptions
- Return `Stream<DomainEvent>` from command handlers
- State should be immutable (use Java records)

### 2. State Design

```java
@AggregateStateInfo(type = "WalletState", version = 1)
public record WalletState(
    String id, 
    List<Balance> balances
) implements AggregateState {
    
    @Override
    public String getAggregateId() {
        return id();
    }
    
    public record Balance(String currency, BigDecimal amount) {}
}
```

**Best Practices:**
- Use Java records for immutable state
- Implement `getAggregateId()` method
- Keep state serialization-friendly
- Version state definitions for evolution

### 3. Command and Event Design

```java
@CommandInfo(type = "CreateWallet", version = 1)
public record CreateWalletCommand(
    @AggregateIdentifier @NotNull String id,
    @NotNull String currency
) implements Command {
    @Override
    public String getAggregateId() {
        return id();
    }
}

@DomainEventInfo(type = "WalletCreated", version = 1)
public record WalletCreatedEvent(
    @AggregateIdentifier @NotNull String id
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}
```

**Best Practices:**
- Use descriptive, past-tense names for events
- Use imperative verbs for commands
- Include `@AggregateIdentifier` annotation
- Use Jakarta validation annotations
- Version all commands and events
- Keep events focused and minimal

### 4. GDPR and PII Handling

```java
@AggregateStateInfo(type = "UserState", version = 1)
public record UserState(
    String userId,
    String country,
    @PIIData String firstName,      // Automatically encrypted
    @PIIData String lastName,       // Automatically encrypted
    @PIIData String email            // Automatically encrypted
) implements AggregateState {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

**Best Practices:**
- Mark all PII fields with `@PIIData` annotation
- Framework handles encryption/decryption transparently
- Encryption keys managed in dedicated Kafka topic
- Consider data retention policies

### 5. Query Model Design

```java
@QueryModelInfo(value = "WalletQuery", version = 1, indexName = "Wallets")
public class WalletQueryModel implements QueryModel<WalletQueryModelState> {
    
    @Override
    public Class<WalletQueryModelState> getStateClass() {
        return WalletQueryModelState.class;
    }
    
    @QueryModelEventHandler(create = true)
    public WalletQueryModelState create(WalletCreatedEvent event, WalletQueryModelState isNull) {
        return new WalletQueryModelState(event.id(), List.of());
    }
    
    @QueryModelEventHandler
    public WalletQueryModelState update(WalletCreditedEvent event, WalletQueryModelState state) {
        // Update read model state
        return state.withUpdatedBalance(event.currency(), event.newBalance());
    }
}
```

**Best Practices:**
- Keep query models denormalized for read performance
- Use `@QueryModelEventHandler` for event processing
- Implement `QueryModelState` with efficient lookup keys
- Consider caching strategies for hot data

### 6. Database Model Design

```java
@DatabaseModelInfo(value = "WalletDB", version = 1)
public class WalletDatabaseModel extends JdbcDatabaseModel {
    
    @Autowired
    public WalletDatabaseModel(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        super(jdbcTemplate, transactionManager);
    }
    
    @DatabaseModelEventHandler
    public void handle(WalletCreatedEvent event) {
        jdbcTemplate.update(
            "INSERT INTO wallets (wallet_id, created_at) VALUES (?, NOW())",
            event.id()
        );
    }
    
    @DatabaseModelEventHandler
    public void handle(WalletCreditedEvent event) {
        jdbcTemplate.update(
            "UPDATE wallet_balances SET amount = ? WHERE wallet_id = ? AND currency = ?",
            event.newBalance(), event.id(), event.currency()
        );
    }
}
```

**Best Practices:**
- Use database models for complex queries and reporting
- Handle events transactionally
- Consider idempotency for event handlers
- Use appropriate indexing strategies

### 7. Schema Evolution

When evolving schemas, use upcasting handlers:

```java
// Version 2 of an event with additional field
@DomainEventInfo(type = "AccountCreated", version = 2)
public record AccountCreatedEventV2(
    @AggregateIdentifier String userId,
    String country,
    String firstName,
    String lastName,
    String email,
    Boolean twoFactorEnabled  // New field
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}

// Upcasting handler to convert V1 to V2
@UpcastingHandler
public AccountCreatedEventV2 cast(AccountCreatedEvent event) {
    return new AccountCreatedEventV2(
        event.userId(), 
        event.country(), 
        event.firstName(), 
        event.lastName(), 
        event.email(), 
        false  // Default value for new field
    );
}
```

**Best Practices:**
- Always maintain backward compatibility
- Use upcasting for schema evolution
- Test schema migrations thoroughly
- Document breaking changes clearly

## Testing Guidelines

### 1. What NOT to Test Directly
**Do not create dedicated unit tests for:**
- **Java Annotations** - Test their effects through the classes that use them
- **Java Records** - Test their usage in context, not record functionality itself
- **Java Interfaces** - Test implementations, not interface definitions
- **Exception Classes** - Test exception handling in the code that throws them, not the exceptions themselves

These language constructs should only be tested indirectly as part of testing other components that use them.

### 2. Unit Tests
- Test aggregate logic in isolation
- Mock external dependencies
- Test command validation
- Verify correct events are emitted
- Test state transitions

### 3. Integration Tests
- Use embedded Kafka for testing
- Test complete command/event flows
- Verify query model updates
- Test error handling scenarios
- Use Spring Boot test framework

### 4. Test Structure
```java
@SpringBootTest
@DirtiesContext
class WalletAggregateTest {
    
    @Autowired
    private AkcesClient client;
    
    @Test
    void testCreateWallet() {
        // Arrange
        CreateWalletCommand command = new CreateWalletCommand(UUID.randomUUID().toString(), "EUR");
        
        // Act
        List<DomainEvent> events = client.send("DEFAULT_TENANT", command)
            .toCompletableFuture()
            .join();
        
        // Assert
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(WalletCreatedEvent.class);
    }
}
```

## Configuration Best Practices

### Application Configuration

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      enable-auto-commit: false
      isolation-level: read_committed
      max-poll-records: 500
    producer:
      acks: all
      retries: 2147483647
      properties:
        enable.idempotence: true

akces:
  schemaregistry:
    url: http://localhost:8081
  rocksdb:
    baseDir: /tmp/akces
  aggregate:
    schemas:
      forceRegister: false
```

**Important Settings:**
- Use `read_committed` isolation level for consistency
- Enable idempotence for producers
- Configure appropriate retry policies
- Set reasonable timeout values

## Common Patterns

### 1. Event Bridging
Connect events from one aggregate to commands on another:

```java
@EventHandler
public Stream<DomainEvent> handle(OrderPlacedEvent event, ProcessManagerState state) {
    // Send command to another aggregate
    getCommandBus().send(new ReserveAmountCommand(
        event.userId(),
        event.currency(),
        event.amount(),
        event.orderId()
    ));
    return Stream.of(new FundsReservedEvent(event.orderId()));
}
```

### 2. Process Managers
Coordinate multi-step workflows:

```java
@AggregateInfo(value = "OrderProcessManager", version = 1)
public class OrderProcessManager implements ProcessManager<OrderProcessManagerState, OrderProcess> {
    
    @EventHandler(create = true)
    public Stream<DomainEvent> create(AccountCreatedEvent event, OrderProcessManagerState isNull) {
        return Stream.of(new UserOrderProcessesCreatedEvent(event.userId()));
    }
    
    @EventHandler
    public Stream<DomainEvent> handle(FundsReservedEvent event, OrderProcessManagerState state) {
        if (state.hasAkcesProcess(event.orderId())) {
            return Stream.of(new OrderConfirmedEvent(event.orderId()));
        }
        return Stream.empty();
    }
}
```

### 3. Error Handling
Use error events instead of exceptions:

```java
@CommandHandler(produces = {BalanceDebitedEvent.class}, errors = {InsufficientFundsErrorEvent.class})
public Stream<DomainEvent> debit(DebitBalanceCommand cmd, WalletState currentState) {
    if (currentState.getAvailableBalance(cmd.currency()).compareTo(cmd.amount()) < 0) {
        return Stream.of(new InsufficientFundsErrorEvent(cmd.id(), cmd.currency()));
    }
    return Stream.of(new BalanceDebitedEvent(cmd.id(), cmd.currency(), cmd.amount()));
}
```

## Deployment

### Kubernetes Deployment
The framework includes a Kubernetes operator that manages three types of services:

1. **AggregateService**: Processes commands and maintains aggregate state
2. **CommandService**: Validates and routes commands
3. **QueryService**: Maintains read models and serves queries

```yaml
apiVersion: akces.elasticsoftware.org/v1
kind: AggregateService
metadata:
  name: wallet-aggregate
spec:
  replicas: 3
  image: ghcr.io/elasticsoftwarefoundation/akces-aggregate-service:0.9.0
  applicationName: "Wallet Aggregate Service"
```

## Build and Release

### Building the Project
```bash
mvn clean install
```

### Running Tests
```bash
mvn test
```

### Creating a Release
```bash
mvn release:prepare release:perform && git push
```

The project uses GitHub Actions for CI/CD. Releases are automatically published to GitHub Packages.

## Documentation Generation

The framework includes annotation processors for generating EventCatalog-compatible documentation:

```xml
<annotationProcessorPaths>
    <path>
        <groupId>org.elasticsoftwarefoundation.akces</groupId>
        <artifactId>akces-eventcatalog</artifactId>
        <version>0.9.1</version>
    </path>
</annotationProcessorPaths>
```

## Common Mistakes to Avoid

1. **Don't throw exceptions in command handlers** - Use error events instead
2. **Don't modify state directly** - Always return new state instances
3. **Don't forget @AggregateIdentifier** - Required for routing and partitioning
4. **Don't make blocking calls in handlers** - Keep handlers fast and deterministic
5. **Don't skip schema versioning** - Always version commands, events, and state
6. **Don't ignore PII data** - Mark sensitive fields with @PIIData
7. **Don't create large aggregates** - Keep aggregates focused and bounded
8. **Don't forget idempotency** - Event handlers should be idempotent

## Performance Considerations

1. **Partitioning**: Kafka partitions determine parallelism - ensure good key distribution
2. **State Snapshots**: Use RocksDB for efficient state storage and retrieval
3. **Query Model Caching**: Implement caching for frequently accessed data
4. **Batch Processing**: Consider batch sizes for event processing
5. **Resource Allocation**: Size CPU and memory based on workload

## Useful Resources

- README.md - Project overview and getting started guide
- FRAMEWORK_OVERVIEW.md - Detailed architecture documentation
- SERVICES.md - Kubernetes services and deployment
- TEST-APPS.md - Example applications and usage patterns
- /prompts - LLM-assisted documentation generation scripts

## License

This project is licensed under Apache License 2.0. All contributed code must include the appropriate license header.

## Support and Contributions

When contributing:
- Follow existing code patterns and conventions
- Add tests for new functionality
- Update documentation as needed
- Ensure backward compatibility
- Run the full test suite before submitting

For questions or issues, refer to the project's GitHub repository at:
https://github.com/elasticsoftwarefoundation/akces-framework
