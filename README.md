# Akces Framework

A robust, Kafka-based CQRS and Event Sourcing framework for building scalable distributed applications.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

## Overview

Akces Framework is a comprehensive Java framework that facilitates building distributed systems using Command Query Responsibility Segregation (CQRS) and Event Sourcing patterns with Apache Kafka as the underlying messaging and storage platform. Designed to be scalable, resilient, and extensible, Akces Framework provides a structured approach to developing event-driven applications.

## Core Features

- **Command-Query Separation**: Distinct paths for commands (writes) and queries (reads), optimizing for different performance characteristics
- **Event Sourcing**: Store domain events as the source of truth for application state
- **Distributed Processing**: Scale horizontally across multiple nodes leveraging Kafka partitioning
- **Schema Evolution**: Built-in schema registry integration for versioning and compatibility management
- **GDPR Compliance**: Includes encryption and data protection features for personal identifiable information (PII)
- **Modular Architecture**: Clean separation of API, runtime, and client components
- **Aggregate Patterns**: Support for Domain-Driven Design (DDD) aggregates and process managers
- **Query Models**: Efficient read models through query projections

## Architecture

Akces Framework consists of the following modules:

- **api**: Core interfaces and annotations defining the programming model
- **runtime**: Implementation of the runtime engine that processes commands and events
- **client**: Client library for sending commands and receiving events
- **shared**: Common utilities and shared components
- **query-support**: Support for building and managing query models

## Setup Instructions

### Prerequisites

- Java 17 or higher
- Apache Kafka 3.x
- Schema Registry (Confluent)
- Maven 3.x

### Dependencies

Add the following to your Maven `pom.xml`:

```xml
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-api</artifactId>
    <version>0.8.0</version>
</dependency>
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-runtime</artifactId>
    <version>0.8.0</version>
</dependency>
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-client</artifactId>
    <version>0.8.0</version>
</dependency>
```

For query support, add:

```xml
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-query-support</artifactId>
    <version>0.8.0</version>
</dependency>
```

### Configuration

Create an `application.yaml` file with the following basic configuration:

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      enable-auto-commit: false
      isolation-level: read_committed
      auto-offset-reset: latest
    producer:
      acks: all
      retries: 2147483647
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1

akces:
  schemaregistry:
    url: http://localhost:8081
  rocksdb:
    baseDir: /tmp/akces
```

## Usage Examples

### Defining an Aggregate

```java
@AggregateInfo(value = "Account", version = 1, generateGDPRKeyOnCreate = true, indexed = true, indexName = "Users")
public class Account implements Aggregate<AccountState> {
    
    @Override
    public Class<AccountState> getStateClass() {
        return AccountState.class;
    }
    
    @CommandHandler(create = true, produces = {AccountCreatedEvent.class})
    public Stream<AccountCreatedEvent> create(CreateAccountCommand cmd, AccountState isNull) {
        return Stream.of(new AccountCreatedEvent(cmd.getUserId(), cmd.getCountry(), cmd.getFirstName(), 
                cmd.getLastName(), cmd.getEmail()));
    }
    
    @EventSourcingHandler(create = true)
    public AccountState create(AccountCreatedEvent event, AccountState isNull) {
        return new AccountState(event.getUserId(), event.getCountry(), event.getFirstName(), 
                event.getLastName(), event.getEmail());
    }
}
```

### Defining a Command

```java
@CommandInfo(type = "CreateAccount", version = 1)
public record CreateAccountCommand(
    @AggregateIdentifier @NotNull String userId,
    @NotNull String country,
    @NotNull @PIIData String firstName,
    @NotNull @PIIData String lastName,
    @NotNull @PIIData String email
) implements Command {
    
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

### Defining an Event

```java
@DomainEventInfo(type = "AccountCreated", version = 1)
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

### Defining Aggregate State

```java
@AggregateStateInfo(type = "AccountState", version = 1)
public record AccountState(
    @AggregateIdentifier @NotNull String userId,
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

### Sending Commands

```java
@Autowired
private AkcesClient akcesClient;

public void createAccount() {
    String userId = UUID.randomUUID().toString();
    CreateAccountCommand command = new CreateAccountCommand(userId, "US", "John", "Doe", "john.doe@example.com");
    
    // Synchronous execution
    List<DomainEvent> events = akcesClient.send("DEFAULT_TENANT", command)
        .toCompletableFuture().get(5, TimeUnit.SECONDS);
        
    // Asynchronous execution
    akcesClient.send("DEFAULT_TENANT", command)
        .thenAccept(events -> {
            // Process events here
        });
        
    // Fire and forget
    akcesClient.sendAndForget("DEFAULT_TENANT", command);
}
```

### Creating a Query Model

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
        return new AccountQueryModelState(event.userId(), event.country(), event.firstName(), 
                event.lastName(), event.email());
    }
}
```

### Querying a Model

```java
@Autowired
private QueryModels queryModels;

public void getAccountDetails(String userId) {
    CompletionStage<AccountQueryModelState> stateFuture = 
        queryModels.getHydratedState(AccountQueryModel.class, userId);
        
    stateFuture.thenAccept(state -> {
        // Do something with the state
        System.out.println("Account for: " + state.firstName() + " " + state.lastName());
    }).exceptionally(ex -> {
        if (ex instanceof QueryModelIdNotFoundException) {
            System.out.println("Account not found");
        }
        return null;
    });
}
```

### Creating a Database Model

```java
@DatabaseModelInfo(value = "AccountDatabaseModel", version = 1)
public class AccountDatabaseModel extends JdbcDatabaseModel {
    
    @DatabaseModelEventHandler
    public void handle(AccountCreatedEvent event) {
        jdbcTemplate.update("""
            INSERT INTO account (user_id, country, first_name, last_name, email)
            VALUES (?, ?, ?, ?, ?)
            """,
            event.userId(),
            event.country(),
            event.firstName(),
            event.lastName(),
            event.email());
    }
}
```

## Advanced Features

### Process Managers

Akces Framework supports process managers for coordinating complex workflows across multiple aggregates:

```java
@AggregateInfo(value = "OrderProcessManager", version = 1)
public class OrderProcessManager implements ProcessManager<OrderProcessManagerState, OrderProcess> {
    
    @EventHandler(create = true)
    public Stream<UserOrderProcessesCreatedEvent> create(AccountCreatedEvent event, 
                                                        OrderProcessManagerState isNull) {
        return Stream.of(new UserOrderProcessesCreatedEvent(event.userId()));
    }
    
    @CommandHandler
    public Stream<BuyOrderCreatedEvent> placeBuyOrder(PlaceBuyOrderCommand command, 
                                                    OrderProcessManagerState state) {
        String orderId = UUID.randomUUID().toString();
        // Reserve funds in wallet aggregate
        getCommandBus().send(new ReserveAmountCommand(
                state.userId(),
                command.market().quoteCurrency(),
                command.quantity().multiply(command.limitPrice()),
                orderId));
                
        return Stream.of(new BuyOrderCreatedEvent(
                state.userId(),
                orderId,
                command.market(),
                command.quantity(),
                command.limitPrice(),
                command.clientReference()));
    }
    
    @EventHandler
    public Stream<DomainEvent> handle(AmountReservedEvent event, OrderProcessManagerState state) {
        OrderProcess orderProcess = state.getAkcesProcess(event.referenceId());
        return Stream.of(new BuyOrderPlacedEvent(state.userId(), orderProcess.orderId(), 
                orderProcess.market(), orderProcess.quantity(), orderProcess.limitPrice()));
    }
}
```

### GDPR Support

Akces Framework provides built-in support for GDPR compliance by encrypting sensitive data:

```java
// Fields marked with @PIIData are automatically encrypted/decrypted
@PIIData
private String email;

// The framework automatically manages encryption keys per aggregate ID
// Data is encrypted at rest in Kafka and decrypted when needed
```

## Running the Application

### Aggregate Service

```java
@SpringBootApplication
public class AggregateServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AggregateServiceApplication.class, args);
    }
}
```

### Query Service

```java
@SpringBootApplication
public class QueryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(QueryServiceApplication.class, args);
    }
}
```

## Schema Evolution

Akces Framework supports schema evolution through version management:

1. Create a new version of your event or command
2. Ensure backward compatibility
3. Register the new schema version
4. Both old and new versions will be supported

## Performance Considerations

- Use appropriate Kafka partitioning for scale
- Configure RocksDB properly for local state
- Use separate query models for different read patterns
- Consider GDPR encryption overhead for performance-critical paths

## License

Apache License 2.0

---

## Support and Contributions

For questions, issues, or contributions, please visit the [GitHub repository](https://github.com/elasticsoftwarefoundation/akces-framework).
