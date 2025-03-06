# Akces Framework

## Overview

Akces is an event sourcing and CQRS (Command Query Responsibility Segregation) framework built on Apache Kafka. It provides a comprehensive solution for building event-driven applications with a clear separation between command and query responsibilities.

The framework follows domain-driven design principles and facilitates the implementation of aggregate roots, commands, events, and projections (query models). It offers robust support for distributed processing, schema evolution, and provides built-in privacy features for handling personally identifiable information (PII).

## Main Features

### Event Sourcing & CQRS Implementation

- **Command Processing**: Define commands that represent intentions to change the system state
- **Event Sourcing**: Store all changes as a sequence of events that can be replayed to reconstruct state
- **Aggregate Support**: Work with domain aggregates that enforce business rules and consistency
- **Query Models**: Build specialized read models optimized for querying data

### Kafka Integration

- **Event Storage**: Uses Kafka as the event store, leveraging its durability and scalability
- **Partitioning Support**: Efficiently distributes processing across multiple partitions
- **Transaction Support**: Ensures atomicity of operations across multiple Kafka topics

### Schema Management

- **Schema Registry Integration**: Support for schema validation and evolution
- **JSON Schema Generation**: Automatic generation of JSON schemas from command and event classes
- **Backward Compatibility**: Enforces schema compatibility rules to ensure event processing resilience

### Privacy and Security

- **GDPR Features**: Built-in support for handling PII data through annotation-driven encryption
- **Encryption**: Transparent encryption/decryption of sensitive data

### Database Support

- **Query Model Persistence**: Ready-to-use support for JDBC and JPA for query model persistence
- **RocksDB Integration**: Efficient local state storage via RocksDB

### Process Management

- **Process Manager Support**: Built-in support for coordinating activities across aggregates
- **Event Handlers**: Define handlers for events to coordinate cross-aggregate actions

## Architecture Components

The framework consists of several modules:

- **api**: Core interfaces and annotations for defining aggregates, commands, and events
- **runtime**: Runtime implementation for processing commands and events
- **client**: Client library for sending commands to the system
- **shared**: Common utilities and classes shared across modules
- **query-support**: Components for building and managing query models

## Setup Instructions

### Prerequisites

- Java 17 or higher
- Apache Kafka (3.x recommended)
- Confluent Schema Registry
- Maven 3.6+

### Maven Dependencies

Add the Akces Framework to your project using Maven:

```xml
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-api</artifactId>
    <version>0.8.1</version>
</dependency>

<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-runtime</artifactId>
    <version>0.8.1</version>
</dependency>

<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-client</artifactId>
    <version>0.8.1</version>
</dependency>
```

### Configuration

Create an application configuration with the following Kafka and Schema Registry properties:

```properties
# Kafka configuration
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.isolation-level=read_committed
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true

# Schema Registry
akces.schemaregistry.url=http://localhost:8081
```

## Usage Examples

### Defining an Aggregate

```java
@AggregateInfo(value = "Wallet", version = 1, indexed = true, indexName = "Wallets")
public final class Wallet implements Aggregate<WalletState> {
    
    @Override
    public Class<WalletState> getStateClass() {
        return WalletState.class;
    }
    
    @CommandHandler(create = true)
    public Stream<DomainEvent> create(CreateWalletCommand cmd, WalletState isNull) {
        return Stream.of(new WalletCreatedEvent(cmd.id()), 
                         new BalanceCreatedEvent(cmd.id(), cmd.currency()));
    }
    
    @EventSourcingHandler(create = true)
    public WalletState create(WalletCreatedEvent event, WalletState isNull) {
        return new WalletState(event.id(), new ArrayList<>());
    }
    
    @CommandHandler
    public Stream<DomainEvent> credit(CreditWalletCommand cmd, WalletState state) {
        WalletState.Balance balance = state.balances().stream()
                .filter(b -> b.currency().equals(cmd.currency()))
                .findFirst().orElse(null);
        
        if (balance == null) {
            return Stream.of(new InvalidCurrencyErrorEvent(cmd.id(), cmd.currency()));
        }
        
        if (cmd.amount().compareTo(BigDecimal.ZERO) < 0) {
            return Stream.of(new InvalidAmountErrorEvent(cmd.id(), cmd.currency()));
        }
        
        return Stream.of(new WalletCreditedEvent(state.id(), cmd.currency(), 
                         cmd.amount(), balance.amount().add(cmd.amount())));
    }
    
    @EventSourcingHandler
    public WalletState credit(WalletCreditedEvent event, WalletState state) {
        return new WalletState(state.id(), 
                state.balances().stream().map(b -> {
                    if (b.currency().equals(event.currency())) {
                        return new WalletState.Balance(b.currency(), b.amount().add(event.amount()));
                    }
                    return b;
                }).toList());
    }
}
```

### Defining Commands and Events

```java
@CommandInfo(type = "CreateWallet", version = 1)
public record CreateWalletCommand(
    @NotNull @AggregateIdentifier String id,
    @NotNull String currency) implements Command {
    
    @Override
    public String getAggregateId() {
        return id();
    }
}

@DomainEventInfo(type = "WalletCreated", version = 1)
public record WalletCreatedEvent(
    @NotNull @AggregateIdentifier String id) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return id();
    }
}
```

### Defining States

```java
public record WalletState(
    String id, 
    List<Balance> balances) implements AggregateState {
    
    @Override
    public String getAggregateId() {
        return id();
    }
    
    public record Balance(
        String currency, 
        BigDecimal amount, 
        BigDecimal reservedAmount) {
        
        public Balance(String currency, BigDecimal amount) {
            this(currency, amount, BigDecimal.ZERO);
        }
        
        public BigDecimal getAvailableAmount() {
            return amount.subtract(reservedAmount);
        }
    }
}
```

### Setting up a Query Model

```java
@QueryModelInfo(value = "WalletQueryModel", version = 1, indexName = "Wallets")
public class WalletQueryModel implements QueryModel<WalletQueryModelState> {
    
    @Override
    public Class<WalletQueryModelState> getStateClass() {
        return WalletQueryModelState.class;
    }
    
    @Override
    public String getIndexName() {
        return "Wallets";
    }
    
    @QueryModelEventHandler(create = true)
    public WalletQueryModelState create(WalletCreatedEvent event, WalletQueryModelState isNull) {
        return new WalletQueryModelState(event.id(), List.of());
    }
    
    @QueryModelEventHandler
    public WalletQueryModelState createBalance(BalanceCreatedEvent event, WalletQueryModelState currentState) {
        WalletQueryModelState.Balance balance = new WalletQueryModelState.Balance(event.currency(), BigDecimal.ZERO);
        List<WalletQueryModelState.Balance> balances = new ArrayList<>(currentState.balances());
        balances.add(balance);
        return new WalletQueryModelState(currentState.walletId(), balances);
    }
    
    @QueryModelEventHandler
    public WalletQueryModelState creditWallet(WalletCreditedEvent event, WalletQueryModelState currentState) {
        return new WalletQueryModelState(
                currentState.walletId(),
                currentState.balances().stream().map(balance -> {
                    if (balance.currency().equals(event.currency())) {
                        return new WalletQueryModelState.Balance(
                                balance.currency(),
                                balance.amount().add(event.amount()),
                                balance.reservedAmount()
                        );
                    }
                    return balance;
                }).toList());
    }
}
```

### Using the Akces Client to Send Commands

```java
@Service
public class WalletService {
    
    private final AkcesClient akcesClient;
    
    public WalletService(AkcesClient akcesClient) {
        this.akcesClient = akcesClient;
    }
    
    public CompletableFuture<List<DomainEvent>> createWallet(String id, String currency) {
        CreateWalletCommand command = new CreateWalletCommand(id, currency);
        return akcesClient.send(command).toCompletableFuture();
    }
    
    public CompletableFuture<List<DomainEvent>> creditWallet(String walletId, String currency, BigDecimal amount) {
        CreditWalletCommand command = new CreditWalletCommand(walletId, currency, amount);
        return akcesClient.send(command).toCompletableFuture();
    }
}
```

### Querying Data with the Query Model

```java
@Service
public class WalletQueryService {
    
    private final QueryModels queryModels;
    
    public WalletQueryService(QueryModels queryModels) {
        this.queryModels = queryModels;
    }
    
    public CompletableFuture<WalletQueryModelState> getWallet(String walletId) {
        return queryModels.getHydratedState(WalletQueryModel.class, walletId)
                .toCompletableFuture();
    }
}
```

### Handling PII Data

```java
@DomainEventInfo(type = "AccountCreated", version = 1)
public record AccountCreatedEvent(
    @NotNull @AggregateIdentifier String userId,
    @NotNull String country,
    @NotNull @PIIData String firstName,
    @NotNull @PIIData String lastName,
    @NotNull @PIIData String email) implements DomainEvent {
    
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

## Running the Framework

1. Start Apache Kafka and Schema Registry
2. Configure your application properties
3. Create your aggregate, command, and event classes
4. Start your application with:

```java
@SpringBootApplication
@Import(AkcesConfiguration.class)
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

## Advanced Features

### Process Managers

Process managers help coordinate activities across multiple aggregates. Here's a simplified example:

```java
@AggregateInfo(value = "OrderProcessManager", version = 1)
public class OrderProcessManager implements ProcessManager<OrderProcessManagerState, OrderProcess> {
    
    @EventHandler(create = true)
    public Stream<UserOrderProcessesCreatedEvent> create(AccountCreatedEvent event, OrderProcessManagerState isNull) {
        return Stream.of(new UserOrderProcessesCreatedEvent(event.userId()));
    }
    
    @CommandHandler
    public Stream<BuyOrderCreatedEvent> placeBuyOrder(PlaceBuyOrderCommand command, OrderProcessManagerState state) {
        String orderId = UUID.randomUUID().toString();
        // Send a command to another aggregate
        getCommandBus().send(new ReserveAmountCommand(
                state.userId(),
                command.market().quoteCurrency(),
                command.quantity().multiply(command.limitPrice()),
                orderId));
        
        return Stream.of(new BuyOrderCreatedEvent(
                command.userId(),
                orderId,
                command.market(),
                command.quantity(),
                command.limitPrice(),
                command.clientReference()));
    }
}
```

### Database Model for Direct Database Integration

```java
@DatabaseModelInfo(value = "Account", version = 1)
public class DefaultJdbcModel extends JdbcDatabaseModel {
    
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

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

Apache License 2.0

---

*This README provides an overview of the Akces Framework. For detailed API documentation, please refer to the JavaDocs and reference documentation.*
