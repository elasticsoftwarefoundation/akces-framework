# Akces Framework

## Overview

Akces Framework is a robust CQRS (Command Query Responsibility Segregation) and Event Sourcing framework built on Apache Kafka. The framework provides a comprehensive infrastructure for building distributed, event-driven applications with a clean separation between command and query operations.

At its core, Akces implements the full event sourcing pattern, capturing all changes to application state as a sequence of events. These events can be replayed to reconstruct the state at any point in time, providing a complete audit trail and enabling powerful temporal queries.

The framework leverages Kafka's distributed architecture for reliable event storage and processing, making it highly scalable and resilient. It also provides built-in support for personal data protection (GDPR compliance), schema evolution, and efficient state management.

## Core Concepts

- **Aggregates**: Domain entities that encapsulate business logic and maintain state through events
- **Commands**: Requests to change the state of an aggregate
- **Domain Events**: Facts that have occurred, representing state changes
- **Command Handlers**: Process commands and produce events
- **Event Sourcing Handlers**: Apply events to update aggregate state
- **Query Models**: Read-optimized projections of aggregate state
- **Database Models**: Persistent storage of aggregate data

## Main Features

### Command Handling
- **Command Bus**: A distributed command bus for routing commands to appropriate aggregates
- **Command Validation**: Automatic schema validation using JSON Schema
- **Command Handlers**: Annotation-based command handling with automatic event publishing
- **Transaction Support**: Transactional processing of commands

### Event Sourcing
- **Event Store**: Kafka-based event store for persisting all domain events
- **Event Handlers**: Annotation-based event handling for processing domain events
- **Event Sourcing Handlers**: Automatic state reconstruction from domain events
- **Event Bridging**: Bridge events between different aggregates

### Aggregate Management
- **Aggregate Runtimes**: Lifecycle management for aggregates
- **State Management**: Efficient state storage using RocksDB
- **Partitioning**: Automatic partitioning of aggregates across nodes for scalability
- **Event Indexing**: Automatic indexing of events for efficient querying

### Query Support
- **Query Models**: Build specialized read models from domain events
- **Database Models**: Automatically sync data to databases for efficient querying
- **Materialized Views**: Build and maintain materialized views of aggregate state
- **State Hydration**: Efficiently load and cache query model state

### Privacy & GDPR Support
- **PII Data Handling**: Built-in support for Personal Identifiable Information (PII)
- **Data Encryption**: Transparent encryption/decryption of sensitive data
- **Annotation-based PII Marking**: Easy identification of sensitive fields
- **Key Management**: Secure management of encryption keys

### Schema Management
- **Schema Evolution**: Support for evolving schemas with backward compatibility
- **Schema Registry Integration**: Works with Confluent Schema Registry for schema management
- **Schema Validation**: Automatic validation of commands and events against their schemas
- **Schema Compatibility Checks**: Ensure backward compatibility of schema changes

## Architecture

The framework consists of several modules:

- **api**: Core interfaces and annotations defining the framework's programming model
- **shared**: Common utilities, serialization, and GDPR support
- **runtime**: The runtime environment for aggregates, including command handling and event sourcing
- **client**: Client library for interacting with aggregate services
- **query-support**: Support for query models and database models

## Setup Instructions

### Prerequisites

- Java 17 or higher
- Apache Kafka 3.x
- Confluent Schema Registry
- Maven 3.6+

### Maven Dependencies

Add the following to your `pom.xml`:

```xml
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-api</artifactId>
    <version>0.8.1</version>
</dependency>

<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-client</artifactId>
    <version>0.8.1</version>
</dependency>

<!-- For running aggregates -->
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-runtime</artifactId>
    <version>0.8.1</version>
</dependency>

<!-- For query models -->
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-query-support</artifactId>
    <version>0.8.1</version>
</dependency>
```

### Configuration

Create an `application.yaml` file with the following configuration:

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      enable-auto-commit: false
      isolation-level: read_committed
      max-poll-records: 500
      heartbeat-interval: 2000
      auto-offset-reset: latest
      properties:
        max.poll.interval.ms: 10000
        session.timeout.ms: 30000
        partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
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

### Define an Aggregate

```java
@AggregateInfo(value = "Wallet", version = 1, generateGDPRKeyOnCreate = true, indexed = true, indexName = "Wallets")
public final class Wallet implements Aggregate<WalletState> {
    @Override
    public Class<WalletState> getStateClass() {
        return WalletState.class;
    }

    @CommandHandler(create = true, produces = {WalletCreatedEvent.class, BalanceCreatedEvent.class})
    public Stream<DomainEvent> create(CreateWalletCommand cmd, WalletState isNull) {
        return Stream.of(new WalletCreatedEvent(cmd.id()), new BalanceCreatedEvent(cmd.id(), cmd.currency()));
    }

    @EventSourcingHandler(create = true)
    public WalletState create(WalletCreatedEvent event, WalletState isNull) {
        return new WalletState(event.id(), new ArrayList<>());
    }
    
    @EventSourcingHandler
    public WalletState createBalance(BalanceCreatedEvent event, WalletState state) {
        List<WalletState.Balance> balances = new ArrayList<>(state.balances());
        balances.add(new WalletState.Balance(event.currency(), BigDecimal.ZERO));
        return new WalletState(state.id(), balances);
    }
    
    @CommandHandler(produces = {WalletCreditedEvent.class, InvalidAmountErrorEvent.class, InvalidCurrencyErrorEvent.class})
    public Stream<DomainEvent> credit(CreditWalletCommand cmd, WalletState currentState) {
        WalletState.Balance balance = currentState.balances().stream()
                .filter(b -> b.currency().equals(cmd.currency()))
                .findFirst().orElse(null);
                
        if (balance == null) {
            return Stream.of(new InvalidCurrencyErrorEvent(cmd.id(), cmd.currency()));
        }
        
        if (cmd.amount().compareTo(BigDecimal.ZERO) < 0) {
            return Stream.of(new InvalidAmountErrorEvent(cmd.id(), cmd.currency()));
        }
        
        return Stream.of(new WalletCreditedEvent(currentState.id(), cmd.currency(), cmd.amount(), 
                balance.amount().add(cmd.amount())));
    }
}
```

### Define an Aggregate State

```java
public record WalletState(String id, List<Balance> balances) implements AggregateState {
    @Override
    public String getAggregateId() {
        return id();
    }

    public record Balance(String currency, BigDecimal amount, BigDecimal reservedAmount) {
        public Balance(String currency, BigDecimal amount) {
            this(currency, amount, BigDecimal.ZERO);
        }

        public BigDecimal getAvailableAmount() {
            return amount.subtract(reservedAmount);
        }
    }
}
```

### Create Commands

```java
@CommandInfo(type = "CreateWallet", version = 1)
public record CreateWalletCommand(@AggregateIdentifier @NotNull String id, 
                                  @NotNull String currency) implements Command {
    @Override
    public String getAggregateId() {
        return id();
    }
}

@CommandInfo(type = "CreditWallet", version = 1)
public record CreditWalletCommand(@AggregateIdentifier @NotNull String id,
                                 @NotNull String currency,
                                 @NotNull BigDecimal amount) implements Command {
    @Override
    public String getAggregateId() {
        return id();
    }
}
```

### Create Events

```java
@DomainEventInfo(type = "WalletCreated", version = 1)
public record WalletCreatedEvent(@AggregateIdentifier @NotNull String id) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}

@DomainEventInfo(type = "BalanceCreated", version = 1)
public record BalanceCreatedEvent(@AggregateIdentifier @NotNull String id, 
                                 @NotNull String currency) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}

@DomainEventInfo(type = "WalletCredited", version = 1)
public record WalletCreditedEvent(@AggregateIdentifier @NotNull String id,
                                 @NotNull String currency,
                                 @NotNull BigDecimal amount,
                                 @NotNull BigDecimal newBalance) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}
```

### Error Events

```java
@DomainEventInfo(type = "InvalidCurrencyError", version = 1)
public record InvalidCurrencyErrorEvent(@AggregateIdentifier @NotNull String walletId,
                                       @NotNull String currency) implements ErrorEvent {
    @Override
    public String getAggregateId() {
        return walletId();
    }
}

@DomainEventInfo(type = "InvalidAmountError", version = 1)
public record InvalidAmountErrorEvent(@AggregateIdentifier @NotNull String walletId,
                                     @NotNull String currency) implements ErrorEvent {
    @Override
    public String getAggregateId() {
        return walletId();
    }
}
```

### Sending Commands

```java
@Autowired
private AkcesClient akcesClient;

public void createWallet() {
    String walletId = UUID.randomUUID().toString();
    CreateWalletCommand command = new CreateWalletCommand(walletId, "USD");
    
    // Send command and get events synchronously
    List<DomainEvent> events = akcesClient.send("DEFAULT_TENANT", command)
        .toCompletableFuture()
        .join();
    
    // Or send command asynchronously
    akcesClient.sendAndForget("DEFAULT_TENANT", command);
}

public void creditWallet(String walletId, String currency, BigDecimal amount) {
    CreditWalletCommand command = new CreditWalletCommand(walletId, currency, amount);
    
    try {
        List<DomainEvent> events = akcesClient.send("DEFAULT_TENANT", command)
            .toCompletableFuture()
            .join();
            
        // Check if we received an error event
        if (events.stream().anyMatch(event -> event instanceof ErrorEvent)) {
            ErrorEvent error = (ErrorEvent) events.stream()
                .filter(event -> event instanceof ErrorEvent)
                .findFirst()
                .orElse(null);
            // Handle error
        }
    } catch (Exception e) {
        // Handle exceptions (validation errors, connectivity issues, etc.)
    }
}
```

### Create a Query Model

```java
@QueryModelInfo(value = "WalletQuery", version = 1, indexName = "Wallets")
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

public record WalletQueryModelState(String walletId, List<Balance> balances) implements QueryModelState {
    @Override
    public String getIndexKey() {
        return walletId();
    }
    
    public record Balance(String currency, BigDecimal amount, BigDecimal reservedAmount) {
        public Balance(String currency, BigDecimal amount) {
            this(currency, amount, BigDecimal.ZERO);
        }
        
        public BigDecimal getAvailableAmount() {
            return amount.subtract(reservedAmount);
        }
    }
}
```

### Query a Model

```java
@Autowired
private QueryModels queryModels;

public WalletQueryModelState getWallet(String walletId) {
    return queryModels.getHydratedState(WalletQueryModel.class, walletId)
        .toCompletableFuture()
        .join();
}

public void displayWalletBalances(String walletId) {
    try {
        WalletQueryModelState wallet = queryModels.getHydratedState(WalletQueryModel.class, walletId)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
            
        wallet.balances().forEach(balance -> {
            System.out.printf("Currency: %s, Amount: %s, Available: %s%n", 
                balance.currency(), 
                balance.amount().toPlainString(), 
                balance.getAvailableAmount().toPlainString());
        });
    } catch (QueryModelIdNotFoundException e) {
        System.out.println("Wallet not found: " + e.getModelId());
    } catch (Exception e) {
        System.out.println("Error retrieving wallet: " + e.getMessage());
    }
}
```

### Create a Database Model

```java
@DatabaseModelInfo(value = "WalletDatabase", version = 1)
public class WalletDatabaseModel extends JdbcDatabaseModel {
    @DatabaseModelEventHandler
    public void handle(WalletCreatedEvent event) {
        jdbcTemplate.update("""
            INSERT INTO wallets (wallet_id, created_date) 
            VALUES (?, NOW())
            """, 
            event.id());
    }
    
    @DatabaseModelEventHandler
    public void handle(BalanceCreatedEvent event) {
        jdbcTemplate.update("""
            INSERT INTO wallet_balances (wallet_id, currency, amount) 
            VALUES (?, ?, ?)
            """, 
            event.id(), event.currency(), 0.00);
    }
    
    @DatabaseModelEventHandler
    public void handle(WalletCreditedEvent event) {
        jdbcTemplate.update("""
            UPDATE wallet_balances
            SET amount = ?
            WHERE wallet_id = ? AND currency = ?
            """,
            event.newBalance(), event.id(), event.currency());
    }
}
```

## GDPR and PII Data

The framework provides built-in support for Personal Identifiable Information (PII):

```java
@AggregateStateInfo(value = "AccountState", version = 1)
public record AccountState(@AggregateIdentifier String userId, 
                          String country, 
                          @PIIData String firstName, 
                          @PIIData String lastName, 
                          @PIIData String email) implements AggregateState {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

PII data is automatically encrypted when stored and decrypted when retrieved. The encryption is transparent to the application code.

## Process Managers

Akces also supports process managers for coordinating complex workflows across multiple aggregates:

```java
@AggregateInfo(value = "OrderProcessManager", version = 1)
public class OrderProcessManager implements ProcessManager<OrderProcessManagerState, OrderProcess> {
    @Override
    public Class<OrderProcessManagerState> getStateClass() {
        return OrderProcessManagerState.class;
    }
    
    @EventHandler(create = true)
    public Stream<UserOrderProcessesCreatedEvent> create(AccountCreatedEvent event, OrderProcessManagerState isNull) {
        return Stream.of(new UserOrderProcessesCreatedEvent(event.userId()));
    }
    
    @CommandHandler
    public Stream<BuyOrderCreatedEvent> placeBuyOrder(PlaceBuyOrderCommand command, OrderProcessManagerState state) {
        String orderId = UUID.randomUUID().toString();
        // Start a multi-step process
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
        
        if (orderProcess instanceof BuyOrderProcess) {
            return Stream.of(new BuyOrderPlacedEvent(
                    state.userId(), 
                    orderProcess.orderId(), 
                    orderProcess.market(), 
                    orderProcess.quantity(), 
                    orderProcess.limitPrice()));
        }
        
        return Stream.empty();
    }
    
    @EventHandler
    public Stream<DomainEvent> handle(InsufficientFundsErrorEvent errorEvent, OrderProcessManagerState state) {
        return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
    }
}
```

## Running the Framework

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

### Client Application

```java
@SpringBootApplication
public class ClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClientApplication.class, args);
    }
    
    @Bean
    public CommandLineRunner commandLineRunner(AkcesClient akcesClient) {
        return args -> {
            // Create a new wallet
            String walletId = UUID.randomUUID().toString();
            CreateWalletCommand createCommand = new CreateWalletCommand(walletId, "USD");
            
            List<DomainEvent> createEvents = akcesClient.send(createCommand)
                .toCompletableFuture()
                .join();
                
            System.out.println("Wallet created: " + walletId);
            
            // Credit the wallet
            CreditWalletCommand creditCommand = new CreditWalletCommand(walletId, "USD", new BigDecimal("1000.00"));
            
            List<DomainEvent> creditEvents = akcesClient.send(creditCommand)
                .toCompletableFuture()
                .join();
                
            System.out.println("Wallet credited: " + walletId);
        };
    }
}
```

## Benefits of Using Akces Framework

- **Scalability**: Built on Kafka for horizontal scalability across distributed nodes
- **Reliability**: Event sourcing ensures data integrity and provides complete audit trails
- **Flexibility**: Clean separation of commands and queries following CQRS principles
- **Performance**: Efficient state management with RocksDB and optimized query models
- **Security**: Built-in support for data privacy and GDPR compliance
- **Evolution**: Schema evolution with backward compatibility checks
- **Developer Experience**: Intuitive annotation-based programming model
- **Observability**: Transparent view of all commands and events flowing through the system
- **Temporal Queries**: Ability to reconstruct state at any point in time

## License

Apache License 2.0
