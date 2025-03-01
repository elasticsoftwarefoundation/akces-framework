# AKCES - Event Sourcing Framework for Java

AKCES is an Event Sourcing and CQRS (Command Query Responsibility Segregation) framework built on top of Apache Kafka. The framework provides a structured approach to building event-sourced applications with support for aggregates, commands, domain events, and query models.

## Overview

AKCES is a robust, production-ready event sourcing framework designed to simplify the development of event-driven applications. It leverages Apache Kafka as the event store and provides a comprehensive set of abstractions and tools for implementing event sourcing patterns in Java applications.

The framework is organized into several modules:

- **api**: Core interfaces and annotations
- **runtime**: Runtime implementation for aggregate processing
- **client**: Client library for sending commands
- **query-support**: Support for query models (CQRS read side)
- **shared**: Shared utilities and serialization tools

## Key Features

- **Event Sourcing**: Store all state changes as a sequence of events
- **CQRS Pattern**: Separate command and query responsibilities
- **Aggregate Pattern**: Group related entities and maintain consistency boundaries
- **Schema Evolution**: Backward-compatible schema validation using Confluent Schema Registry
- **GDPR Compliance**: Built-in support for handling PII (Personally Identifiable Information) data
- **Command Validation**: Automatic validation of commands using JSON Schema
- **Query Models**: Support for materialized views optimized for queries
- **Transactional Processing**: Ensures consistency across event processing
- **Scalable Architecture**: Built on Kafka for high throughput and horizontal scalability

## Architecture

AKCES follows these core principles:

1. **Commands** trigger state changes
2. **Domain Events** represent state changes that have occurred
3. **Aggregates** enforce business rules and generate events
4. **Event Handlers** react to events and may trigger additional commands
5. **Query Models** maintain read-optimized views of the data

![AKCES Architecture](https://example.com/akces-architecture.png)

## Setup

### Prerequisites

- Java 17+
- Apache Kafka
- Confluent Schema Registry
- Maven or Gradle

### Dependencies

Add the dependencies to your Maven `pom.xml`:

```xml
<dependencies>
    <!-- AKCES Core -->
    <dependency>
        <groupId>org.elasticsoftware.akces</groupId>
        <artifactId>akces-api</artifactId>
        <version>0.11.0</version>
    </dependency>
    
    <!-- AKCES Runtime -->
    <dependency>
        <groupId>org.elasticsoftware.akces</groupId>
        <artifactId>akces-runtime</artifactId>
        <version>0.11.0</version>
    </dependency>
    
    <!-- AKCES Client -->
    <dependency>
        <groupId>org.elasticsoftware.akces</groupId>
        <artifactId>akces-client</artifactId>
        <version>0.11.0</version>
    </dependency>
    
    <!-- AKCES Query Support -->
    <dependency>
        <groupId>org.elasticsoftware.akces</groupId>
        <artifactId>akces-query-support</artifactId>
        <version>0.11.0</version>
    </dependency>
</dependencies>
```

### Configuration

Create a Spring Boot application and configure AKCES:

```java
@SpringBootApplication
public class MyAkcesApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyAkcesApplication.class, args);
    }
}
```

Configure Kafka and Schema Registry in `application.properties`:

```properties
spring.kafka.bootstrap-servers=localhost:9092
akces.schemaregistry.url=http://localhost:8081
akces.rocksdb.baseDir=/tmp/akces
```

## Usage Examples

### 1. Define an Aggregate

```java
@AggregateInfo(value = "Account", generateGDPRKeyOnCreate = true)
public class Account implements Aggregate<AccountState> {
    
    @Override
    public Class<AccountState> getStateClass() {
        return AccountState.class;
    }
    
    @CommandHandler(create = true, produces = AccountCreatedEvent.class, errors = {})
    public Stream<AccountCreatedEvent> create(CreateAccountCommand cmd, AccountState isNull) {
        return Stream.of(new AccountCreatedEvent(cmd.userId(), cmd.country(), cmd.firstName(), cmd.lastName(), cmd.email()));
    }
    
    @EventSourcingHandler(create = true)
    public AccountState create(AccountCreatedEvent event, AccountState isNull) {
        return new AccountState(event.userId(), event.country(), event.firstName(), event.lastName(), event.email());
    }
}
```

### 2. Define the State

```java
@AggregateStateInfo(type = "Account", version = 1)
public record AccountState(
    @NotNull String userId,
    @NotNull String country,
    @NotNull @PIIData String firstName,
    @NotNull @PIIData String lastName,
    @NotNull @PIIData String email
) implements AggregateState {
    
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

### 3. Define Commands

```java
@CommandInfo(type = "CreateAccount")
public record CreateAccountCommand(
    @AggregateIdentifier @NotNull String userId,
    @NotNull String country,
    @NotNull String firstName,
    @NotNull String lastName,
    @NotNull String email
) implements Command {
    
    @Override
    @NotNull
    public String getAggregateId() {
        return userId();
    }
}
```

### 4. Define Events

```java
@DomainEventInfo(type = "AccountCreated")
public record AccountCreatedEvent(
    @AggregateIdentifier @NotNull String userId,
    @NotNull String country,
    @NotNull @PIIData String firstName,
    @NotNull @PIIData String lastName,
    @NotNull @PIIData String email
) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

### 5. Define a Query Model

```java
@QueryModelInfo(value = "AccountQueryModel", version = 1, indexName = "Users")
public class AccountQueryModel implements QueryModel<AccountQueryModelState> {
    
    @Override
    public Class<AccountQueryModelState> getStateClass() {
        return AccountQueryModelState.class;
    }
    
    @Override
    public String getIndexName() {
        return "Users";
    }
    
    @QueryModelEventHandler(create = true)
    public AccountQueryModelState create(AccountCreatedEvent event, AccountQueryModelState isNull) {
        return new AccountQueryModelState(event.userId(), event.country(), event.firstName(), event.lastName(), event.email());
    }
}
```

### 6. Define Query Model State

```java
@QueryModelStateInfo(type = "Account")
public record AccountQueryModelState(
    String accountId,
    String country,
    String firstName,
    String lastName,
    String email
) implements QueryModelState {
    
    @Override
    public String getIndexKey() {
        return accountId();
    }
}
```

### 7. Send Commands

```java
@Inject
AkcesClient akcesClient;

public void createAccount() {
    String userId = UUID.randomUUID().toString();
    CreateAccountCommand command = new CreateAccountCommand(
        userId, "US", "John", "Doe", "john.doe@example.com"
    );
    
    List<DomainEvent> events = akcesClient.send("TENANT_ID", command)
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
        
    // Handle events
}
```

### 8. Query Data

```java
@Inject
QueryModels queryModels;

public AccountQueryModelState getAccount(String accountId) {
    return queryModels.getHydratedState(AccountQueryModel.class, accountId)
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
}
```

## Advanced Features

### PII Data Handling

AKCES provides built-in support for encrypting and decrypting PII data with the `@PIIData` annotation:

```java
@PIIData
private String firstName;
```

This automatically encrypts the data when stored and decrypts it when loaded, ensuring GDPR compliance.

### Schema Evolution

AKCES supports backward-compatible schema evolution through Confluent Schema Registry. You can evolve your events' schema over time while maintaining compatibility with older versions.

### Process Managers

For complex business processes spanning multiple aggregates, AKCES provides a Process Manager pattern:

```java
@AggregateInfo(value = "OrderProcessManager")
public class OrderProcessManager implements ProcessManager<OrderProcessManagerState, OrderProcess> {
    // Implementation
}
```

## Best Practices

1. **Keep Aggregates Small**: Design aggregates around business consistency boundaries
2. **Immutable Events**: Events should be immutable and represent facts that have occurred
3. **Event Versioning**: Version your events to support schema evolution
4. **Idempotent Event Handlers**: Design event handlers to be idempotent
5. **Transactional Outbox**: Use the built-in transactional processing to ensure consistency

## Contributing

We welcome contributions! Please follow these steps:

1. Fork the repository
2. Create a feature branch
3. Submit a pull request

## Release process

This project uses the Maven Release Plugin and GitHub Actions to create releases.\
Just run `mvn release:prepare release:perform && git push` in the root to select the version to be released and create a
VCS tag.

GitHub Actions will
start [the build process](https://github.com/elasticsoftwarefoundation/akces-framework/actions/workflows/maven-publish.yml).

If successful, the build will be automatically published
to [Github Packages](https://maven.pkg.github.com/elasticsoftwarefoundation/akces-framework/).

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

## Acknowledgments

- Apache Kafka Team
- Confluent Schema Registry Team
- Event Sourcing and CQRS Community

---

For more detailed documentation, examples, and tutorials, please visit our [official documentation](https://elasticsoftware.org/akces).

```
