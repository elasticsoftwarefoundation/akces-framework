# Akces Framework

## Overview

Akces is a powerful CQRS (Command Query Responsibility Segregation) and Event Sourcing framework built on Apache Kafka. It provides a comprehensive infrastructure for building distributed, event-driven applications with a clear separation between write operations (commands) and read operations (queries).

The framework implements the full event sourcing pattern, capturing all changes to application state as a sequence of events. These events serve as the system of record and can be replayed to reconstruct the state at any point in time, providing a complete audit trail and enabling temporal queries.

Akces leverages Kafka's distributed architecture for reliable event storage and processing, making it highly scalable and resilient. It also provides built-in support for privacy protection (GDPR compliance), schema evolution, and efficient state management.

## Core Concepts

- **Aggregates**: Domain entities that encapsulate business logic and maintain consistency boundaries
- **Commands**: Requests to perform actions that change the state of an aggregate
- **Domain Events**: Immutable records of facts that have occurred, representing state changes
- **Command Handlers**: Process commands and produce events 
- **Event Sourcing Handlers**: Apply events to update aggregate state
- **Query Models**: Read-optimized projections of aggregate state
- **Database Models**: Persistent storage of aggregate data optimized for queries
- **Process Managers**: Coordinate workflows across multiple aggregates

## Key Features

### Command Processing

- **Command Bus**: Distribute commands to appropriate aggregates
- **Command Validation**: Automatic schema-based validation using JSON Schema
- **Command Routing**: Intelligent routing based on aggregate IDs
- **Transactional Processing**: Atomic processing with Kafka transactions

### Event Sourcing

- **Event Store**: Kafka-based storage for all domain events
- **State Reconstruction**: Rebuild aggregate state by replaying events
- **Event Handlers**: React to events to trigger additional processes
- **Event Bridging**: Connect events from one aggregate to commands on another
- **Upcasting**: Support for evolving events and state schemas over time

### Aggregate Management

- **Partition-Based Processing**: Scale horizontally through Kafka partitioning
- **State Snapshots**: Efficient state storage using RocksDB
- **Aggregate Lifecycle**: Manage aggregate creation and updates
- **Event Indexing**: Index events for efficient retrieval

### Query Support

- **Query Models**: Build specialized read models from events
- **State Hydration**: Efficiently load and cache query model state
- **Database Integration**: Support for both JDBC and JPA database models
- **Event-Driven Updates**: Keep read models in sync with write models
- **Caching**: Built-in caching mechanism for improved read performance

### Privacy & GDPR

- **PII Data Protection**: Automatic encryption of personal data
- **Transparent Handling**: Annotation-based marking of sensitive fields (`@PIIData`)
- **Key Management**: Secure handling of encryption keys
- **Context-Aware Processing**: Apply encryption based on context

### Schema Management

- **Schema Registry Integration**: Work with Confluent Schema Registry
- **Schema Evolution**: Support versioning and evolution of schemas
- **Compatibility Checking**: Ensure backward compatibility
- **Automatic Generation**: Generate JSON schemas from command and event classes

### Process Managers

- **Orchestration**: Manage complex workflows across multiple aggregates
- **Stateful Processing**: Maintain process state through events
- **Event-Driven Flow**: React to events to advance processes
- **Error Handling**: Built-in compensation logic for failures

## Architecture

Akces is organized into several Maven modules:

- **api**: Core interfaces and annotations defining the programming model
- **runtime**: Implementation of event sourcing and command handling
- **shared**: Common utilities, serialization, and GDPR functionality
- **client**: Client library for sending commands and processing responses
- **query-support**: Support for query models and database models
- **eventcatalog**: Annotation processor for generating API documentation

## Getting Started

### Prerequisites

- Java 21 or higher
- Apache Kafka 3.x with KRaft mode enabled
- Confluent Schema Registry
- Maven 3.6 or higher

### Maven Dependencies

Add the following to your `pom.xml`:

```xml
<dependencies>
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-api</artifactId>
    <version>0.9.1</version>
</dependency>

<!-- For command senders -->
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-client</artifactId>
    <version>0.9.1</version>
</dependency>

<!-- For aggregate services -->
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-runtime</artifactId>
    <version>0.9.1</version>
</dependency>

<!-- For query models and database models -->
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-query-support</artifactId>
    <version>0.9.1</version>
</dependency>

<!-- For API documentation generation -->
<dependency>
    <groupId>org.elasticsoftwarefoundation.akces</groupId>
    <artifactId>akces-eventcatalog</artifactId>
    <version>0.9.1</version>
    <scope>provided</scope>
</dependency>
</dependencies>
```

### Configuration

Configure the framework in your `application.yaml`:

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
        linger.ms: 0
        retry.backoff.ms: 0
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1

akces:
  schemaregistry:
    url: http://localhost:8081
  rocksdb:
    baseDir: /tmp/akces
  aggregate:
    schemas:
      forceRegister: false  # Set to true to force schema registration even if incompatible
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

    @CommandHandler(create = true, produces = {WalletCreatedEvent.class, BalanceCreatedEvent.class})
    public Stream<DomainEvent> create(CreateWalletCommand cmd, WalletState isNull) {
        return Stream.of(new WalletCreatedEvent(cmd.id()), 
                         new BalanceCreatedEvent(cmd.id(), cmd.currency()));
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
    
    @CommandHandler(produces = {WalletCreditedEvent.class}, errors = {InvalidCurrencyErrorEvent.class, InvalidAmountErrorEvent.class})
    public Stream<DomainEvent> credit(CreditWalletCommand cmd, WalletState currentState) {
        WalletState.Balance balance = currentState.balances().stream()
                .filter(b -> b.currency().equals(cmd.currency()))
                .findFirst()
                .orElse(null);
                
        if (balance == null) {
            return Stream.of(new InvalidCurrencyErrorEvent(cmd.id(), cmd.currency()));
        }
        
        if (cmd.amount().compareTo(BigDecimal.ZERO) < 0) {
            return Stream.of(new InvalidAmountErrorEvent(cmd.id(), cmd.currency()));
        }
        
        return Stream.of(new WalletCreditedEvent(currentState.id(), 
                                               cmd.currency(), 
                                               cmd.amount(), 
                                               balance.amount().add(cmd.amount())));
    }
}
```

### Defining the Aggregate State

```java
@AggregateStateInfo(type = "WalletState", version = 1)
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

### Creating Commands

```java
@CommandInfo(type = "CreateWallet", version = 1)
public record CreateWalletCommand(
    @AggregateIdentifier 
    @NotNull String id, 
    
    @NotNull String currency
) implements Command {
    @Override
    public String getAggregateId() {
        return id();
    }
}

@CommandInfo(type = "CreditWallet", version = 1)
public record CreditWalletCommand(
    @AggregateIdentifier 
    @NotNull String id,
    
    @NotNull String currency,
    
    @NotNull BigDecimal amount
) implements Command {
    @Override
    public String getAggregateId() {
        return id();
    }
}
```

### Creating Events

```java
@DomainEventInfo(type = "WalletCreated", version = 1)
public record WalletCreatedEvent(
    @AggregateIdentifier 
    @NotNull String id
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}

@DomainEventInfo(type = "BalanceCreated", version = 1)
public record BalanceCreatedEvent(
    @AggregateIdentifier 
    @NotNull String id, 
    
    @NotNull String currency
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}

@DomainEventInfo(type = "WalletCredited", version = 1)
public record WalletCreditedEvent(
    @AggregateIdentifier 
    @NotNull String id,
    
    @NotNull String currency,
    
    @NotNull BigDecimal amount,
    
    @NotNull BigDecimal newBalance
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return id();
    }
}
```

### Error Events

```java
@DomainEventInfo(type = "InvalidCurrencyError", version = 1)
public record InvalidCurrencyErrorEvent(
    @AggregateIdentifier 
    @NotNull String walletId,
    
    @NotNull String currency
) implements ErrorEvent {
    @Override
    public String getAggregateId() {
        return walletId();
    }
}

@DomainEventInfo(type = "InvalidAmountError", version = 1)
public record InvalidAmountErrorEvent(
    @AggregateIdentifier 
    @NotNull String walletId,
    
    @NotNull String currency
) implements ErrorEvent {
    @Override
    public String getAggregateId() {
        return walletId();
    }
}
```

### Sending Commands

```java
@Service
public class WalletService {
    private final AkcesClient akcesClient;
    
    @Autowired
    public WalletService(AkcesClient akcesClient) {
        this.akcesClient = akcesClient;
    }
    
    public String createWallet(String currency) {
        String walletId = UUID.randomUUID().toString();
        CreateWalletCommand command = new CreateWalletCommand(walletId, currency);
        
        // Send command and wait for response
        List<DomainEvent> events = akcesClient.send("DEFAULT_TENANT", command)
            .toCompletableFuture()
            .join();
            
        // Check for success
        if (events.stream().anyMatch(e -> e instanceof ErrorEvent)) {
            throw new RuntimeException("Failed to create wallet");
        }
        
        return walletId;
    }
    
    public void creditWallet(String walletId, String currency, BigDecimal amount) {
        CreditWalletCommand command = new CreditWalletCommand(walletId, currency, amount);
        
        try {
            // Send command without waiting for response
            akcesClient.sendAndForget("DEFAULT_TENANT", command);
        } catch (CommandRefusedException e) {
            // Handle specific command exceptions
            throw new RuntimeException("Command refused: " + e.getMessage());
        } catch (CommandValidationException e) {
            throw new RuntimeException("Invalid command: " + e.getMessage());
        }
    }
}
```

### Creating a Query Model

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
    public WalletQueryModelState createBalance(BalanceCreatedEvent event, WalletQueryModelState state) {
        List<WalletQueryModelState.Balance> balances = new ArrayList<>(state.balances());
        balances.add(new WalletQueryModelState.Balance(event.currency(), BigDecimal.ZERO));
        return new WalletQueryModelState(state.walletId(), balances);
    }
    
    @QueryModelEventHandler
    public WalletQueryModelState creditWallet(WalletCreditedEvent event, WalletQueryModelState state) {
        return new WalletQueryModelState(
            state.walletId(),
            state.balances().stream()
                .map(balance -> {
                    if (balance.currency().equals(event.currency())) {
                        return new WalletQueryModelState.Balance(
                            balance.currency(),
                            balance.amount().add(event.amount()),
                            balance.reservedAmount()
                        );
                    }
                    return balance;
                })
                .toList()
        );
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

### Querying a Model

```java
@RestController
@RequestMapping("/wallets")
public class WalletController {
    private final QueryModels queryModels;
    
    @Autowired
    public WalletController(QueryModels queryModels) {
        this.queryModels = queryModels;
    }
    
    @GetMapping("/{walletId}")
    public ResponseEntity<WalletQueryModelState> getWallet(@PathVariable String walletId) {
        try {
            WalletQueryModelState wallet = queryModels.getHydratedState(WalletQueryModel.class, walletId)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
                
            return ResponseEntity.ok(wallet);
        } catch (QueryModelIdNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
}
```

### Creating a Database Model

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
    public void handle(BalanceCreatedEvent event) {
        jdbcTemplate.update(
            "INSERT INTO wallet_balances (wallet_id, currency, amount, reserved_amount) VALUES (?, ?, 0, 0)",
            event.id(),
            event.currency()
        );
    }
    
    @DatabaseModelEventHandler
    public void handle(WalletCreditedEvent event) {
        jdbcTemplate.update(
            "UPDATE wallet_balances SET amount = ? WHERE wallet_id = ? AND currency = ?",
            event.newBalance(),
            event.id(),
            event.currency()
        );
    }
}
```

### GDPR and PII Data

Akces provides built-in support for handling personal identifiable information (PII):

```java
@AggregateStateInfo(type = "UserState", version = 1)
public record UserState(
    @AggregateIdentifier 
    String userId,
    
    String country,
    
    @PIIData 
    String firstName,
    
    @PIIData 
    String lastName,
    
    @PIIData 
    String email
) implements AggregateState {
    @Override
    public String getAggregateId() {
        return userId();
    }
}
```

With this annotation, the framework automatically:
- Encrypts PII data before storing it
- Decrypts PII data when loading it
- Manages encryption keys securely through a dedicated Kafka topic
- Ensures only authorized access to decrypted data

### Schema Evolution

Akces supports evolving your domain model over time:

```java
// Original version
@DomainEventInfo(type = "AccountCreated", version = 1)
public record AccountCreatedEvent(
    @AggregateIdentifier String userId,
    String country,
    String firstName,
    String lastName,
    String email
) implements DomainEvent { 
    @Override
    public String getAggregateId() {
        return userId();
    }
}

// New version with additional field
@DomainEventInfo(type = "AccountCreated", version = 2)
public record AccountCreatedEventV2(
    @AggregateIdentifier String userId,
    String country,
    String firstName,
    String lastName,
    String email,
    Boolean twoFactorEnabled
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return userId();
    }
}

// The upcasting handler
@UpcastingHandler
public AccountCreatedEventV2 cast(AccountCreatedEvent event) {
    return new AccountCreatedEventV2(
        event.userId(), 
        event.country(), 
        event.firstName(), 
        event.lastName(), 
        event.email(), 
        false // Default value for new field
    );
}
```

### Process Managers

For coordinating complex workflows across multiple aggregates:

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
    
    @EventSourcingHandler(create = true)
    public OrderProcessManagerState create(UserOrderProcessesCreatedEvent event, OrderProcessManagerState isNull) {
        return new OrderProcessManagerState(event.userId());
    }
    
    @CommandHandler
    public Stream<BuyOrderCreatedEvent> placeBuyOrder(PlaceBuyOrderCommand command, OrderProcessManagerState state) {
        String orderId = UUID.randomUUID().toString();
        
        // Reserve funds first - send command to Wallet aggregate
        getCommandBus().send(new ReserveAmountCommand(
            state.userId(),
            command.market().quoteCurrency(),
            command.quantity().multiply(command.limitPrice()),
            orderId
        ));
        
        // Create order record
        return Stream.of(new BuyOrderCreatedEvent(
            state.userId(),
            orderId,
            command.market(),
            command.quantity(),
            command.limitPrice(),
            command.clientReference()
        ));
    }
    
    @EventHandler
    public Stream<DomainEvent> handle(AmountReservedEvent event, OrderProcessManagerState state) {
        if (state.hasAkcesProcess(event.referenceId())) {
            OrderProcess process = state.getAkcesProcess(event.referenceId());
            return Stream.of(new BuyOrderPlacedEvent(
                state.userId(),
                process.orderId(),
                process.market(),
                process.quantity(),
                process.limitPrice()
            ));
        }
        return Stream.empty();
    }
    
    @EventHandler
    public Stream<DomainEvent> handle(InsufficientFundsErrorEvent errorEvent, OrderProcessManagerState state) {
        if (state.hasAkcesProcess(errorEvent.referenceId())) {
            return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
        }
        return Stream.empty();
    }
}
```

## Running the Framework

### Aggregate Service

```java
@SpringBootApplication
public class AggregateServiceApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AggregateServiceApplication.class);
        application.setSources(Set.of(args));
        application.run();
    }
}
```

### Query Service

```java
@SpringBootApplication
public class QueryServiceApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(QueryServiceApplication.class);
        application.setSources(Set.of(args));
        application.run();
    }
}
```

### Command Service

```java
@SpringBootApplication
public class CommandServiceApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(CommandServiceApplication.class);
        application.setSources(Set.of(args));
        application.run();
    }
}
```

## Benefits of Using Akces

- **Scalability**: Built on Kafka for horizontal scaling across multiple nodes
- **Reliability**: Event sourcing ensures data integrity and complete audit trails
- **Flexibility**: Clean separation of commands and queries with CQRS
- **Performance**: Efficient state management with RocksDB and optimized query models
- **Security**: Built-in GDPR compliance with transparent PII handling
- **Evolution**: Schema evolution with backward compatibility checks
- **Developer Experience**: Intuitive annotation-based programming model
- **Observability**: Complete visibility into all commands and events

## API Documentation Generation

Akces includes an annotation processor that can generate EventCatalog-compatible documentation for your commands and events:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.elasticsoftwarefoundation.akces</groupId>
                        <artifactId>akces-eventcatalog</artifactId>
                        <version>0.9.1</version>
                    </path>
                </annotationProcessorPaths>
                <annotationProcessors>
                    <annotationProcessor>org.elasticsoftware.akces.eventcatalog.EventCatalogProcessor</annotationProcessor>
                </annotationProcessors>
                <compilerArgs>
                    <arg>-Aakces.eventcatalog.repoBaseUrl=https://github.com/yourusername/yourrepo/blob/main/</arg>
                    <arg>-Aakces.eventcatalog.owners=team1,team2</arg>
                    <arg>-Aakces.eventcatalog.schemaDomain=example.com</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

## License

Apache License 2.0

## Release Process

This project uses the Maven Release Plugin and GitHub Actions to create releases.
Run `mvn release:prepare release:perform && git push` to select the version to be released and create a VCS tag.

GitHub Actions will start [the build process](https://github.com/elasticsoftwarefoundation/akces-framework/actions/workflows/maven-publish.yml).

If successful, the build will be automatically published to [Github Packages](https://maven.pkg.github.com/elasticsoftwarefoundation/akces-framework/).
