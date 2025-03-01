Here's a comprehensive README.md for the Akces Framework:

# Akces Framework

Akces is a powerful CQRS (Command Query Responsibility Segregation) and Event Sourcing framework built on Apache Kafka. It provides a robust foundation for building scalable, distributed applications with clear separation of concerns and reliable event-driven architecture.

## Overview

Akces Framework is designed to simplify the implementation of event-sourced applications by providing a comprehensive set of tools and patterns that integrate seamlessly with Apache Kafka. The framework follows the CQRS pattern, separating write operations (commands) from read operations (queries), and implements event sourcing to maintain a complete history of all state changes.

### Key Modules

The Akces Framework consists of several modules, each serving a specific purpose:

1. **API** (`akces-api`): Core interfaces and annotations defining the CQRS and Event Sourcing patterns.
2. **Runtime** (`akces-runtime`): Implementation of the aggregate lifecycle management, command handling, and event sourcing.
3. **Client** (`akces-client`): Client library for sending commands to aggregates.
4. **Query Support** (`akces-query-support`): Support for building and managing query models.
5. **Shared** (`akces-shared`): Common utilities and components shared across modules.

## Features

### 1. Command Handling

- Type-safe command processing
- Automatic command validation via JSON Schema
- Command routing to the appropriate aggregate instance
- Transactional command execution

### 2. Event Sourcing

- Immutable event records for each state change
- Event-driven state reconstruction
- Event schema evolution support with backwards compatibility validation
- Efficient event storage and retrieval using Kafka

### 3. GDPR Compliance

- Built-in PII (Personally Identifiable Information) data encryption
- Annotation-based marking of sensitive data
- Transparent encryption/decryption during serialization/deserialization

### 4. Query Models

- Separate read models optimized for specific queries
- Event-based projection updates
- Scalable query processing

### 5. Schema Evolution

- JSON Schema validation
- Schema versioning support
- Backwards compatibility checking

### 6. Distributed Architecture

- Kafka-based communication
- Partition-aware processing
- Scalable across multiple nodes

## Getting Started

### Prerequisites

- Java 17 or later
- Apache Kafka cluster (and Zookeeper if using Kafka versions before 3.x)
- Confluent Schema Registry

### Installation

Add the following dependencies to your Maven project:

```xml
<dependencies>
    <!-- Akces API - Core interfaces and annotations -->
    <dependency>
        <groupId>org.elasticsoftwarefoundation.akces</groupId>
        <artifactId>akces-api</artifactId>
        <version>0.8.0</version>
    </dependency>
    
    <!-- Akces Runtime - For implementing aggregates -->
    <dependency>
        <groupId>org.elasticsoftwarefoundation.akces</groupId>
        <artifactId>akces-runtime</artifactId>
        <version>0.8.0</version>
    </dependency>
    
    <!-- Akces Client - For sending commands -->
    <dependency>
        <groupId>org.elasticsoftwarefoundation.akces</groupId>
        <artifactId>akces-client</artifactId>
        <version>0.8.0</version>
    </dependency>
    
    <!-- Akces Query Support - For building query models -->
    <dependency>
        <groupId>org.elasticsoftwarefoundation.akces</groupId>
        <artifactId>akces-query-support</artifactId>
        <version>0.8.0</version>
    </dependency>
</dependencies>
```

### Configuration

Create an application.properties or application.yaml file with the following settings:

```properties
# Kafka configuration
spring.kafka.bootstrap-servers=localhost:9092
akces.schemaregistry.url=http://localhost:8081

# Aggregate service configuration
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.isolation-level=read_committed
spring.kafka.consumer.max-poll-records=500
spring.kafka.consumer.heartbeat-interval=2000
spring.kafka.consumer.auto-offset-reset=latest

# RocksDB configuration (for state storage)
akces.rocksdb.baseDir=/path/to/akces/data
```

## Usage Examples

### 1. Define Commands

Commands are immutable objects that request a change to the system. They must implement the `Command` interface and be annotated with `@CommandInfo`.

```java
@CommandInfo(type = "CreateWallet", version = 1)
public record CreateWalletCommand(
    @AggregateIdentifier @NotNull String id,
    @NotNull String currency
) implements Command {
    @Override
    @NotNull
    public String getAggregateId() {
        return id();
    }
}
```

### 2. Define Events

Events represent state changes that have occurred. They must implement the `DomainEvent` interface and be annotated with `@DomainEventInfo`.

```java
@DomainEventInfo(type = "WalletCreated")
public record WalletCreatedEvent(
    @AggregateIdentifier @NotNull String id
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}
```

### 3. Define Aggregate State

Aggregate state holds the current state of an aggregate. It must implement the `AggregateState` interface.

```java
@AggregateStateInfo(type = "Wallet", version = 1)
public record WalletState(
    @AggregateIdentifier @NotNull String id,
    List<Balance> balances
) implements AggregateState {
    @Override
    public String getAggregateId() {
        return id();
    }
    
    public record Balance(String currency, BigDecimal amount, BigDecimal reservedAmount) {
        public Balance(@NotNull String currency) {
            this(currency, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        
        public BigDecimal getAvailableAmount() {
            return amount.subtract(reservedAmount);
        }
    }
}
```

### 4. Define an Aggregate

Aggregates handle commands and produce events. They must implement the `Aggregate` interface and be annotated with `@AggregateInfo`.

```java
@AggregateInfo(value = "Wallet", version = 1, indexed = true, indexName = "Users")
public final class Wallet implements Aggregate<WalletState> {
    @Override
    public String getName() {
        return "Wallet";
    }

    @Override
    public Class<WalletState> getStateClass() {
        return WalletState.class;
    }

    @CommandHandler(create = true, produces = WalletCreatedEvent.class, errors = {})
    public @NotNull Stream<DomainEvent> create(@NotNull CreateWalletCommand cmd, WalletState isNull) {
        return Stream.of(new WalletCreatedEvent(cmd.id()), new BalanceCreatedEvent(cmd.id(), cmd.currency()));
    }

    @EventSourcingHandler(create = true)
    public @NotNull WalletState create(@NotNull WalletCreatedEvent event, WalletState isNull) {
        return new WalletState(event.id(), new ArrayList<>());
    }

    @EventSourcingHandler
    public @NotNull WalletState createBalance(@NotNull BalanceCreatedEvent event, WalletState state) {
        List<WalletState.Balance> balances = new ArrayList<>(state.balances());
        balances.add(new WalletState.Balance(event.currency(), BigDecimal.ZERO));
        return new WalletState(state.id(), balances);
    }
}
```

### 5. Define a Query Model

Query models are optimized read models updated by events.

```java
@QueryModelInfo(value = "WalletQueryModel", version = 1, indexName = "Users")
public class WalletQueryModel implements QueryModel<WalletQueryModelState> {
    @Override
    public String getName() {
        return "WalletQueryModel";
    }

    @Override
    public Class<WalletQueryModelState> getStateClass() {
        return WalletQueryModelState.class;
    }

    @Override
    public String getIndexName() {
        return "Users";
    }

    @QueryModelEventHandler(create = true)
    public WalletQueryModelState create(WalletCreatedEvent event, WalletQueryModelState isNull) {
        return new WalletQueryModelState(event.id(), List.of());
    }

    @QueryModelEventHandler(create = false)
    public WalletQueryModelState createBalance(BalanceCreatedEvent event, WalletQueryModelState currentState) {
        WalletQueryModelState.Balance balance = new WalletQueryModelState.Balance(event.currency(), BigDecimal.ZERO);
        List<WalletQueryModelState.Balance> balances = new ArrayList<>(currentState.balances());
        balances.add(balance);
        return new WalletQueryModelState(currentState.walletId(), balances);
    }
}
```

### 6. Send Commands

To send commands to aggregates:

```java
@Autowired
private AkcesClient akcesClient;

public void createWallet(String id, String currency) {
    CreateWalletCommand command = new CreateWalletCommand(id, currency);
    
    // Send and wait for events
    List<DomainEvent> events = akcesClient.send("TENANT_ID", command)
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
    
    // Events contain the results of command execution
    events.forEach(event -> {
        if (event instanceof WalletCreatedEvent) {
            // Handle wallet created
        } else if (event instanceof BalanceCreatedEvent) {
            // Handle balance created
        }
    });
    
    // Or send without waiting for a response
    akcesClient.sendAndForget("TENANT_ID", command);
}
```

### 7. Query Models

To query data from a query model:

```java
@Autowired
private QueryModels queryModels;

public WalletQueryModelState getWallet(String walletId) {
    return queryModels.getHydratedState(WalletQueryModel.class, walletId)
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
}
```

## GDPR Compliance

Akces provides built-in support for GDPR compliance through PII data encryption:

```java
@DomainEventInfo(type = "AccountCreated")
public record AccountCreatedEvent(
    @AggregateIdentifier @NotNull String userId,
    @NotNull String country,
    @NotNull @PIIData String firstName,  // Will be automatically encrypted
    @NotNull @PIIData String lastName,   // Will be automatically encrypted
    @NotNull @PIIData String email      // Will be automatically encrypted
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

## Advanced Features

### Process Managers

Process managers (also known as sagas) coordinate across multiple aggregates:

```java
@AggregateInfo(value = "OrderProcessManager", indexed = true, indexName = "Users")
public class OrderProcessManager implements Aggregate<OrderProcessManagerState> {
    // Handle events from external aggregates
    @EventHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<DomainEvent> handle(InsufficientFundsErrorEvent errorEvent, OrderProcessManagerState state) {
        return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
    }
}
```

### Schema Evolution

Akces supports schema evolution with backward compatibility checking:

```java
// Version 1
@DomainEventInfo(type = "AccountCreatedEvent", version = 1)
public record AccountCreatedEvent(
    @NotNull String userId,
    @NotNull String lastName,
    @NotNull AccountTypeV1 type
) implements DomainEvent { ... }

// Version 2 - adding optional fields
@DomainEventInfo(type = "AccountCreatedEvent", version = 2)
public record AccountCreatedEventV2(
    @NotNull String userId,
    @NotNull String lastName,
    @NotNull AccountTypeV2 type,
    String firstName,  // New optional field
    String country     // New optional field
) implements DomainEvent { ... }
```

## Contributing

Contributions to the Akces Framework are welcome. Please follow the standard GitHub flow:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## License

The Akces Framework is licensed under the Apache License 2.0.

---

This README provides a comprehensive overview of the Akces Framework and its capabilities. For more detailed documentation or support, please contact the Elastic Software Foundation.
