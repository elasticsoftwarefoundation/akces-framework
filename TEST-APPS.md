# Akces Framework Test Applications

This document provides an overview of the test applications included in the Akces Framework repository. These applications demonstrate real-world usage patterns and implementation examples of the Akces Framework's CQRS (Command Query Responsibility Segregation) and Event Sourcing capabilities.

## Crypto Trading Application

The primary test application is a Crypto Trading platform that showcases how to build a distributed, event-driven system using the Akces Framework.

### Overview

The Crypto Trading application simulates a cryptocurrency trading platform where users can:

- Create accounts
- Manage crypto wallets with various currency balances
- Place market orders to buy cryptocurrencies
- View market information from exchanges (via Coinbase API integration)

The application demonstrates the full CQRS pattern with clear separation between commands (write operations) and queries (read operations), as well as event sourcing for maintaining complete audit trails and state reconstruction.

### Architecture

The application follows a modular architecture split into three main components:

1. **Aggregates Module** (`crypto-trading/aggregates`): Contains domain models, commands, events, and business logic
2. **Commands Module** (`crypto-trading/commands`): Provides API endpoints for write operations
3. **Queries Module** (`crypto-trading/queries`): Provides API endpoints for read operations and maintains query-optimized data models

### Key Aggregates

The application defines several key aggregates that demonstrate different aspects of domain modeling with Akces:

#### Account Aggregate

Represents a user account in the trading system:

- Creates new user accounts
- Stores basic user information including PII (Personally Identifiable Information) that is automatically protected through Akces's GDPR compliance features

```java
public final class Account implements Aggregate<AccountState> {
    public Stream<AccountCreatedEvent> create(CreateAccountCommand cmd, AccountState isNull) {
        return Stream.of(new AccountCreatedEvent(cmd.userId(), cmd.country(), 
                                               cmd.firstName(), cmd.lastName(), cmd.email()));
    }
}
```

#### Wallet Aggregate

Manages a user's cryptocurrency holdings:

- Creates wallets automatically when accounts are created
- Supports multiple currency balances within a wallet
- Handles crediting and debiting operations
- Implements reservation system for pending transactions
- Demonstrates state versioning/migration with `WalletState` and `WalletStateV2`

```java
public final class Wallet implements Aggregate<WalletStateV2> {
    public Stream<DomainEvent> credit(CreditWalletCommand cmd, WalletStateV2 currentState) {
        // Validation and business logic
        return Stream.of(new WalletCreditedEvent(currentState.id(), cmd.currency(), 
                                               cmd.amount(), balance.amount().add(cmd.amount())));
    }
}
```

#### CryptoMarket Aggregate

Represents a trading market for a specific cryptocurrency pair:

- Creates market definitions with base and quote currencies
- Processes market orders
- Integrates with external Coinbase API for pricing
- Demonstrates external service integration

```java
public class CryptoMarket implements Aggregate<CryptoMarketState> {
    public Stream<DomainEvent> handle(PlaceMarketOrderCommand command, CryptoMarketState currentState) {
        // Market order processing logic with external API integration
        Ticker currentTicker = coinbaseService.getTicker(currentState.id());
        // Further processing...
    }
}
```

#### OrderProcessManager Aggregate

Demonstrates the Process Manager pattern for coordinating complex workflows:

- Orchestrates the full lifecycle of buy orders across multiple aggregates
- Reacts to events from different aggregates to advance the process
- Handles compensating transactions for failures
- Shows how to implement saga patterns with Akces

```java
public class OrderProcessManager implements Aggregate<OrderProcessManagerState> {
    public Stream<BuyOrderCreatedEvent> placeBuyOrder(PlaceBuyOrderCommand command, OrderProcessManagerState state) {
        // Reserve funds first
        getCommandBus().send(new ReserveAmountCommand(/*...*/));
        // Create the order
        return Stream.of(new BuyOrderCreatedEvent(/*...*/));
    }
    
    // Event handlers to advance the process based on events from other aggregates
    public Stream<DomainEvent> handle(AmountReservedEvent event, OrderProcessManagerState state) {
        // Place the market order now that funds are reserved
        getCommandBus().send(new PlaceMarketOrderCommand(/*...*/));
        return Stream.of(new BuyOrderPlacedEvent(/*...*/));
    }
}
```

### Commands and Events

The application defines a rich set of commands and events that demonstrate best practices:

#### Commands

- `CreateAccountCommand`: Creates a new user account
- `CreateWalletCommand`: Creates a new wallet
- `CreateBalanceCommand`: Adds a new currency balance to a wallet
- `CreditWalletCommand`: Adds funds to a wallet balance
- `DebitWalletCommand`: Removes funds from a wallet balance
- `ReserveAmountCommand`: Reserves funds for a pending transaction
- `PlaceBuyOrderCommand`: Initiates a buy order process
- `PlaceMarketOrderCommand`: Places an order on a market

#### Events

- `AccountCreatedEvent`: Signals account creation
- `WalletCreatedEvent`: Signals wallet creation
- `BalanceCreatedEvent`: Signals addition of a new balance
- `WalletCreditedEvent`: Signals successful crediting of funds
- `WalletDebitedEvent`: Signals successful debiting of funds
- `AmountReservedEvent`: Signals successful fund reservation
- `BuyOrderCreatedEvent`: Signals creation of a buy order
- `MarketOrderFilledEvent`: Signals successful execution of a market order

#### Error Events

The application handles errors through specialized error events:

- `InvalidAmountErrorEvent`: Signals an invalid amount in a command
- `InsufficientFundsErrorEvent`: Signals insufficient funds for an operation
- `InvalidCryptoCurrencyErrorEvent`: Signals an unknown cryptocurrency
- `MarketOrderRejectedErrorEvent`: Signals rejection of a market order

### Query Models

The application defines query models that are optimized for read operations:

- `AccountQueryModel`: Provides efficient access to account information
- `WalletQueryModel`: Provides efficient access to wallet balances
- `CryptoMarketModel`: Provides access to market information

These models are kept up-to-date by subscribing to domain events and are stored in formats optimized for queries.

### Database Integration

The application demonstrates database integration for query models:

- Uses JDBC for database access
- Implements Liquibase for database migrations
- Shows how to map domain events to database operations

```java
public class CryptoMarketModel extends JdbcDatabaseModel {
    public void handle(CryptoMarketCreatedEvent event) {
        cryptoMarketRepository.save(CryptoMarket.createNew(
            event.id(),
            event.baseCrypto(),
            event.quoteCrypto(),
            // Other properties...
        ));
    }
}
```

### REST API

The application exposes REST endpoints for both commands and queries:

#### Command Endpoints

- `POST /v1/accounts`: Create a new account
- `POST /v1/wallets/{walletId}/balances`: Add a new balance to a wallet
- `POST /v1/wallets/{walletId}/balances/{currency}/credit`: Credit funds to a wallet
- `POST /v1/accounts/{accountId}/orders/buy`: Place a buy order

#### Query Endpoints

- `GET /v1/accounts/{accountId}`: Get account information
- `GET /v1/wallets/{walletId}`: Get wallet information
- `GET /v1/markets`: Get all markets
- `GET /v1/markets/{marketId}`: Get specific market information

### Testing

The application includes comprehensive tests that demonstrate testing strategies for CQRS and Event Sourcing:

- Unit tests for individual aggregates
- Integration tests for command processing
- End-to-end tests for full workflows
- Tests that verify temporal queries and event replay

The tests use TestContainers to set up Kafka, Schema Registry, and PostgreSQL for realistic testing environments.

## Technical Highlights

### Event Sourcing Implementation

- All state changes are captured as immutable events
- State is reconstructed by replaying events
- Events are stored in Kafka topics, partitioned by aggregate ID
- Snapshots are maintained in RocksDB for efficient access

### CQRS Pattern

- Clear separation between command and query responsibilities
- Command endpoints focusing on write operations
- Query endpoints optimized for read operations
- Different data models for writes and reads

### GDPR Compliance

- PII data (like names and emails) is automatically protected
- Uses `@PIIData` annotation to mark sensitive fields
- Transparent encryption during serialization

### Schema Management

- Integration with Confluent Schema Registry
- Command and event validation using JSON Schema
- Schema evolution with version tracking

### Process Management

- Complex workflows coordinated across multiple aggregates
- Stateful process tracking
- Event-driven process advancement

## Conclusion

The Crypto Trading application serves as a comprehensive demonstration of building distributed, event-driven systems with the Akces Framework. It showcases best practices for implementing CQRS and Event Sourcing patterns and demonstrates how to solve common challenges in distributed systems.

This application can be used as a reference implementation when building your own applications with the Akces Framework, offering patterns and solutions for common requirements in enterprise applications.
