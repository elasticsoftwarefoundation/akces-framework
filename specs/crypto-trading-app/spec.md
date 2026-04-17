# Crypto Trading Test App — Feature Specification

> **Status:** Migrated
> **Module:** `test-apps/crypto-trading/` (`akces-crypto-trading-parent`)
> **Version:** 0.12.1-SNAPSHOT
> **Package:** `org.elasticsoftware.cryptotrading`

## 1. Overview

The Crypto Trading Test App is the reference implementation for the Akces CQRS/Event Sourcing Framework. It demonstrates a full-stack crypto trading domain spanning three Maven sub-modules (`aggregates`, `commands`, `queries`) with four aggregates, a process manager, three query models, a JDBC database model, REST command controllers, REST query controllers, and integration with an external market data provider (Coinbase). The application exercises the complete Akces feature set: command handling, event sourcing, state upcasting, GDPR/PII encryption, cross-aggregate coordination via process manager, in-memory query model projections, JDBC-backed database model projections, `@EventHandler`, `@EventBridgeHandler`, and EventCatalog documentation generation.

## 2. User Scenarios

### US-1: Create a User Account with GDPR-Protected PII

**As** a user of the crypto trading platform,
**I want** to create an account with my personal information,
**so that** I can start trading crypto currencies.

#### Acceptance Criteria

1. Submit a `CreateAccountCommand` with `userId`, `country`, `firstName`, `lastName`, and `email`.
2. The `Account` aggregate emits an `AccountCreatedEvent`.
3. `AccountState` stores `firstName`, `lastName`, and `email` as `@PIIData`-annotated fields — the framework transparently encrypts these at rest.
4. The `Account` aggregate is configured with `generateGDPRKeyOnCreate = true` on `@AggregateInfo`, generating a per-aggregate GDPR encryption key.
5. Account creation is exposed via `POST /v1/accounts` on the `AccountCommandController`.
6. The `AccountCreatedEvent` triggers cross-aggregate side effects: the `Wallet` aggregate creates a wallet via `@EventHandler(create = true)`, and the `OrderProcessManager` creates user order processes via `@EventHandler(create = true)`.

### US-2: Manage Wallet Balances (Create, Credit, Debit)

**As** a user with an account,
**I want** to manage currency balances in my wallet,
**so that** I can fund trades and track my holdings.

#### Acceptance Criteria

1. A `Wallet` is automatically created when an `AccountCreatedEvent` is received (via `@EventHandler`), along with an initial EUR balance.
2. Alternatively, a wallet can be created directly via `CreateWalletCommand` (via `@CommandHandler(create = true)`).
3. `CreateBalanceCommand` adds a new currency balance; emits `BalanceCreatedEvent` or `BalanceAlreadyExistsErrorEvent` if the currency already exists.
4. `CreditWalletCommand` adds funds to a balance; emits `WalletCreditedEvent`, or `InvalidCryptoCurrencyErrorEvent`/`InvalidAmountErrorEvent` on validation failure.
5. `DebitWalletCommand` removes funds from a balance; emits `WalletDebitedEvent`, or `InsufficientFundsErrorEvent` when available balance is insufficient.
6. Balance endpoints: `POST /v1/accounts/{accountId}/wallet/balances` (create balance), `POST /v1/accounts/{accountId}/wallet/balances/{currency}/credit` (credit).
7. `WalletStateV2` (version 2) uses per-reservation tracking (`List<Reservation>`) instead of a single `reservedAmount` field. An `@UpcastingHandler` converts `WalletState` (version 1) to `WalletStateV2`.
8. Available balance is computed as `amount - sum(reservations.amount)`.

### US-3: Reserve and Cancel Wallet Reservations

**As** the order process manager,
**I want** to reserve funds in a user's wallet when an order is placed,
**so that** the user cannot overspend while an order is being processed.

#### Acceptance Criteria

1. `ReserveAmountCommand` creates a reservation on a balance, keyed by `referenceId` (the order ID); emits `AmountReservedEvent` or `InsufficientFundsErrorEvent`/`InvalidCryptoCurrencyErrorEvent`/`InvalidAmountErrorEvent`.
2. `CancelReservationCommand` removes a reservation by `referenceId`; emits `ReservationCancelledEvent` or `ReservationNotFoundErrorEvent`/`InvalidCryptoCurrencyErrorEvent`.
3. Reservations reduce the available balance: `availableAmount = amount - sum(reservation amounts)`.

### US-4: Place and Execute Crypto Market Orders

**As** a crypto market aggregate,
**I want** to accept market orders and execute them against real-time Coinbase prices,
**so that** buy/sell trades are filled at current market rates.

#### Acceptance Criteria

1. `CreateCryptoMarketCommand` creates a new market with `baseCurrency`, `quoteCurrency`, `baseIncrement`, `quoteIncrement`, and `defaultCounterPartyId`; emits `CryptoMarketCreatedEvent`.
2. `PlaceMarketOrderCommand` validates that BUY orders have `funds` and SELL orders have `size`; rejects with `MarketOrderRejectedErrorEvent` if missing.
3. On valid placement, the `CryptoMarket` aggregate fetches the current ticker from `CoinbaseService`, calculates the fill price (ask for BUY, bid for SELL) and quantity, and emits `MarketOrderPlacedEvent` + `MarketOrderFilledEvent` atomically.
4. `CryptoMarketState` stores `baseCrypto`, `quoteCrypto`, increments, and `defaultCounterPartyId`.

### US-5: Coordinate Buy Order Flow via OrderProcessManager

**As** a user,
**I want** to place a buy order that is automatically coordinated across wallet and market aggregates,
**so that** funds are reserved, the market order is placed, and my balances are updated atomically.

#### Acceptance Criteria

1. `PlaceBuyOrderCommand` on the `OrderProcessManager` generates a unique order ID, sends `ReserveAmountCommand` to the `Wallet` aggregate (reserve quote currency), and emits `BuyOrderCreatedEvent`.
2. On `AmountReservedEvent` (via `@EventHandler`), the process manager sends `PlaceMarketOrderCommand` to the `CryptoMarket` aggregate and emits `BuyOrderPlacedEvent`.
3. On `MarketOrderFilledEvent` (via `@EventBridgeHandler`), the process manager sends `FillBuyOrderCommand` to itself, which: cancels the reservation, debits the quote currency, credits the base currency, and emits `BuyOrderFilledEvent`.
4. On `MarketOrderRejectedErrorEvent` (via `@EventBridgeHandler`), the process manager sends `RejectOrderCommand` to itself, emitting `BuyOrderRejectedEvent`.
5. On `InsufficientFundsErrorEvent` or `InvalidCryptoCurrencyErrorEvent` (via `@EventHandler`), the process manager emits `BuyOrderRejectedEvent`.
6. Buy orders are exposed via `POST /v1/accounts/{accountId}/orders/buy`.
7. Order process state tracks `OrderProcessState`: `CREATED → PLACED → FILLED` (or `REJECTED`).

### US-6: Coordinate Sell Order Flow via OrderProcessManager

**As** a user,
**I want** to place a sell order with the same coordinated flow as buy orders,
**so that** my base currency is reserved, the market order is placed, and I receive quote currency.

#### Acceptance Criteria

1. `PlaceSellOrderCommand` on the `OrderProcessManager` reserves the base currency amount via `ReserveAmountCommand` and emits `SellOrderCreatedEvent`.
2. On `AmountReservedEvent`, the process manager places a SELL market order and emits `SellOrderPlacedEvent`.
3. On `MarketOrderFilledEvent`, `FillSellOrderCommand` cancels the reservation, debits the base currency, credits the quote currency (`quantity × price`), and emits `SellOrderFilledEvent`.
4. On rejection, `SellOrderRejectedEvent` is emitted.
5. Sell orders are exposed via `POST /v1/accounts/{accountId}/orders/sell`.

### US-7: Query Wallet Balances via In-Memory Query Model

**As** a user,
**I want** to query my wallet's current balances,
**so that** I can see my available funds in each currency.

#### Acceptance Criteria

1. `WalletQueryModel` (`@QueryModelInfo(value = "WalletQueryModel", version = 1, indexName = "Users")`) projects `WalletCreatedEvent`, `BalanceCreatedEvent`, `WalletCreditedEvent`, and `WalletDebitedEvent` into `WalletQueryModelState`.
2. State contains `walletId` and `List<Balance>` (currency, amount, reservedAmount).
3. The query is exposed via `GET /v1/accounts/{accountId}/wallet` on `WalletQueryController`, using `QueryModels.getHydratedState()`.
4. Returns 404 when the wallet is not found.

### US-8: Query Account Details via In-Memory Query Model

**As** a user,
**I want** to query my account details,
**so that** I can verify my profile information.

#### Acceptance Criteria

1. `AccountQueryModel` (`@QueryModelInfo(value = "AccountQueryModel", version = 1, indexName = "Users")`) projects `AccountCreatedEvent` into `AccountQueryModelState` (accountId, country, firstName, lastName, email).
2. PII fields are transparently decrypted when the query model state is hydrated.
3. The query is exposed via `GET /v1/accounts/{accountId}` on `AccountQueryController`.
4. Returns 404 when the account is not found.

### US-9: Query Open Orders via In-Memory Query Model

**As** a user,
**I want** to query my open buy and sell orders,
**so that** I can track order status and history.

#### Acceptance Criteria

1. `OrdersQueryModel` (`@QueryModelInfo(value = "OrdersQueryModel", version = 1, indexName = "Users")`) projects all order lifecycle events into `OrdersQueryModelState`.
2. State tracks separate lists of `BuyOrder` and `SellOrder` with `OrderState` (CREATED, PLACED, FILLED, REJECTED).
3. Queries exposed via `GET /v1/accounts/{accountId}/orders` (all orders) and `GET /v1/accounts/{accountId}/orders/{orderId}` (single order).
4. Returns 404 when the user or order is not found.

### US-10: Persist Crypto Markets via JDBC Database Model

**As** an operator,
**I want** crypto market definitions to be persisted in a relational database,
**so that** they can be queried efficiently and survive restarts.

#### Acceptance Criteria

1. `CryptoMarketModel` (`@DatabaseModelInfo(value = "CryptoMarketModel", version = 1, schemaName = "crypto_markets")`) extends `JdbcDatabaseModel` and handles `CryptoMarketCreatedEvent`.
2. On market creation, a `CryptoMarket` JPA entity is persisted via `CryptoMarketRepository` (Spring Data JPA).
3. `CryptoMarketsService` provides `getAllMarkets()` and `getMarketById()`.
4. Markets are exposed via `GET /v1/markets` (list all) and `GET /v1/markets/{marketId}` (single market) on `CryptoMarketsQueryController`.

## 3. Domain Model

### 3.1 Aggregates

| Aggregate | Annotation | State Class | Index | GDPR |
|-----------|------------|-------------|-------|------|
| `Wallet` | `@AggregateInfo(value = "Wallet", indexed = true, indexName = "Users")` | `WalletStateV2` (v2) | Users | No |
| `Account` | `@AggregateInfo(value = "Account", indexed = true, indexName = "Users", generateGDPRKeyOnCreate = true)` | `AccountState` (v1) | Users | Yes |
| `CryptoMarket` | `@AggregateInfo(value = "CryptoMarket")` | `CryptoMarketState` (v1) | — | No |
| `OrderProcessManager` | `@AggregateInfo(value = "OrderProcessManager", indexed = true, indexName = "Users")` | `OrderProcessManagerState` (v1) | Users | No |

### 3.2 Commands

| Command | Type | Version | Aggregate | Create | Key Fields |
|---------|------|---------|-----------|--------|------------|
| `CreateAccountCommand` | `CreateAccount` | 1 | Account | Yes | userId, country, firstName, lastName, email |
| `CreateWalletCommand` | `CreateWallet` | 1 | Wallet | Yes | id, currency |
| `CreateBalanceCommand` | `CreateBalance` | 1 | Wallet | No | id, currency |
| `CreditWalletCommand` | `CreditWallet` | 1 | Wallet | No | id, currency, amount |
| `DebitWalletCommand` | `DebitWallet` | 1 | Wallet | No | id, currency, amount |
| `ReserveAmountCommand` | `ReserveAmount` | 1 | Wallet | No | userId, currency, amount, referenceId |
| `CancelReservationCommand` | `CancelReservation` | 1 | Wallet | No | userId, currency, referenceId |
| `CreateCryptoMarketCommand` | `CreateCryptoMarket` | 1 | CryptoMarket | Yes | id, baseCurrency, quoteCurrency, baseIncrement, quoteIncrement, defaultCounterPartyId |
| `PlaceMarketOrderCommand` | `PlaceMarketOrder` | 1 | CryptoMarket | No | marketId, orderId, ownerId, side, funds, size |
| `PlaceBuyOrderCommand` | `PlaceBuyOrder` | 1 | OrderProcessManager | No | userId, market, amount, clientReference |
| `PlaceSellOrderCommand` | `PlaceSellOrder` | 1 | OrderProcessManager | No | userId, market, quantity, clientReference |
| `FillBuyOrderCommand` | `FillBuyOrder` | 1 | OrderProcessManager | No | userId, orderId, counterpartyId, price, quantity, baseCurrency, quoteCurrency |
| `FillSellOrderCommand` | `FillSellOrder` | 1 | OrderProcessManager | No | userId, orderId, counterpartyId, price, quantity, baseCurrency, quoteCurrency |
| `RejectOrderCommand` | `RejectOrder` | 1 | OrderProcessManager | No | userId, orderId |

### 3.3 Domain Events

| Event | Type | Version | Aggregate Source |
|-------|------|---------|------------------|
| `AccountCreatedEvent` | `AccountCreated` | 1 | Account |
| `WalletCreatedEvent` | `WalletCreated` | 1 | Wallet |
| `BalanceCreatedEvent` | `BalanceCreated` | 1 | Wallet |
| `WalletCreditedEvent` | `WalletCredited` | 1 | Wallet |
| `WalletDebitedEvent` | `WalletDebited` | 1 | Wallet |
| `AmountReservedEvent` | `AmountReserved` | 1 | Wallet |
| `ReservationCancelledEvent` | `ReservationCancelled` | 1 | Wallet |
| `CryptoMarketCreatedEvent` | `CryptoMarketCreated` | 1 | CryptoMarket |
| `MarketOrderPlacedEvent` | `MarketOrderPlaced` | 1 | CryptoMarket |
| `MarketOrderFilledEvent` | `MarketOrderFilled` | 1 | CryptoMarket |
| `UserOrderProcessesCreatedEvent` | `UserOrderProcessesCreated` | 1 | OrderProcessManager |
| `BuyOrderCreatedEvent` | `BuyOrderCreated` | 1 | OrderProcessManager |
| `BuyOrderPlacedEvent` | `BuyOrderPlaced` | 1 | OrderProcessManager |
| `BuyOrderFilledEvent` | `BuyOrderFilled` | 1 | OrderProcessManager |
| `BuyOrderRejectedEvent` | `BuyOrderRejected` | 1 | OrderProcessManager |
| `SellOrderCreatedEvent` | `SellOrderCreated` | 1 | OrderProcessManager |
| `SellOrderPlacedEvent` | `SellOrderPlaced` | 1 | OrderProcessManager |
| `SellOrderFilledEvent` | `SellOrderFilled` | 1 | OrderProcessManager |
| `SellOrderRejectedEvent` | `SellOrderRejected` | 1 | OrderProcessManager |

### 3.4 Error Events

| Error Event | Type | Aggregate |
|-------------|------|-----------|
| `BalanceAlreadyExistsErrorEvent` | `BalanceAlreadyExistsError` | Wallet |
| `InvalidCryptoCurrencyErrorEvent` | `InvalidCryptoCurrencyError` | Wallet |
| `InvalidAmountErrorEvent` | `InvalidAmountError` | Wallet |
| `InsufficientFundsErrorEvent` | `InsufficientFundsError` | Wallet |
| `ReservationNotFoundErrorEvent` | `ReservationNotFoundError` | Wallet |
| `MarketOrderRejectedErrorEvent` | `MarketOrderRejectedError` | CryptoMarket |

### 3.5 Query Models

| Model | Annotation | State Class | Index | Events Consumed |
|-------|------------|-------------|-------|-----------------|
| `WalletQueryModel` | `@QueryModelInfo(value = "WalletQueryModel", version = 1, indexName = "Users")` | `WalletQueryModelState` | Users | WalletCreated, BalanceCreated, WalletCredited, WalletDebited |
| `AccountQueryModel` | `@QueryModelInfo(value = "AccountQueryModel", version = 1, indexName = "Users")` | `AccountQueryModelState` | Users | AccountCreated |
| `OrdersQueryModel` | `@QueryModelInfo(value = "OrdersQueryModel", version = 1, indexName = "Users")` | `OrdersQueryModelState` | Users | UserOrderProcessesCreated, BuyOrderCreated/Placed/Filled/Rejected, SellOrderCreated/Placed/Filled/Rejected |

### 3.6 Database Models

| Model | Annotation | Backing | Events Consumed |
|-------|------------|---------|-----------------|
| `CryptoMarketModel` | `@DatabaseModelInfo(value = "CryptoMarketModel", version = 1, schemaName = "crypto_markets")` | `JdbcDatabaseModel` + Spring Data JPA (`CryptoMarketRepository`) | CryptoMarketCreated |

## 4. Cross-Aggregate Event Flow

```
AccountCreatedEvent
  ├─→ Wallet @EventHandler(create=true) → WalletCreatedEvent + BalanceCreatedEvent
  └─→ OrderProcessManager @EventHandler(create=true) → UserOrderProcessesCreatedEvent

PlaceBuyOrderCommand → OrderProcessManager
  │  sends ReserveAmountCommand → Wallet
  └─ emits BuyOrderCreatedEvent
      │
AmountReservedEvent → OrderProcessManager @EventHandler
  │  sends PlaceMarketOrderCommand → CryptoMarket
  └─ emits BuyOrderPlacedEvent
      │
MarketOrderFilledEvent → OrderProcessManager @EventBridgeHandler
  │  sends FillBuyOrderCommand → self
  └─ FillBuyOrderCommand handler:
       sends CancelReservationCommand → Wallet
       sends DebitWalletCommand → Wallet
       sends CreditWalletCommand → Wallet
       emits BuyOrderFilledEvent

MarketOrderRejectedErrorEvent → OrderProcessManager @EventBridgeHandler
  │  sends RejectOrderCommand → self
  └─ emits BuyOrderRejectedEvent

InsufficientFundsErrorEvent → OrderProcessManager @EventHandler
  └─ emits BuyOrderRejectedEvent
```

The sell order flow mirrors the buy flow with `PlaceSellOrderCommand`, `SellOrderCreatedEvent`, `SellOrderPlacedEvent`, `SellOrderFilledEvent`, and `SellOrderRejectedEvent`.

## 5. REST API

### 5.1 Command Endpoints (commands module)

| Method | Path | Controller | Description |
|--------|------|------------|-------------|
| POST | `/v1/accounts` | `AccountCommandController` | Create account |
| POST | `/v1/accounts/{accountId}/wallet/balances` | `WalletCommandController` | Create balance |
| POST | `/v1/accounts/{accountId}/wallet/balances/{currency}/credit` | `WalletCommandController` | Credit balance |
| POST | `/v1/accounts/{accountId}/orders/buy` | `OrdersCommandController` | Place buy order |
| POST | `/v1/accounts/{accountId}/orders/sell` | `OrdersCommandController` | Place sell order |

### 5.2 Query Endpoints (queries module)

| Method | Path | Controller | Description |
|--------|------|------------|-------------|
| GET | `/v1/accounts/{accountId}` | `AccountQueryController` | Get account details |
| GET | `/v1/accounts/{accountId}/wallet` | `WalletQueryController` | Get wallet balances |
| GET | `/v1/accounts/{accountId}/orders` | `OrdersQueryController` | Get open orders |
| GET | `/v1/accounts/{accountId}/orders/{orderId}` | `OrdersQueryController` | Get order by ID |
| GET | `/v1/markets` | `CryptoMarketsQueryController` | List all markets |
| GET | `/v1/markets/{marketId}` | `CryptoMarketsQueryController` | Get market by ID |

## 6. External Dependencies

| Dependency | Purpose |
|------------|---------|
| `CoinbaseService` | Fetches real-time ticker prices from Coinbase API via `WebClient` |
| PostgreSQL | Backing store for `CryptoMarketModel` (via JDBC/Spring Data JPA) and Liquibase schema management |
| Apache Kafka | Event store, command routing, and query model event consumption |

## 7. Non-Functional Requirements

- **Java 25**, Spring Boot 4.x, Apache Kafka 4.x (KRaft mode)
- **Reactive REST**: All controllers use `Mono`/`Flux` from Project Reactor (Spring WebFlux)
- **GDPR compliance**: Account aggregate uses `@PIIData` with automatic encryption/decryption
- **Schema versioning**: `WalletState` has version 1 (legacy) and `WalletStateV2` (version 2) with `@UpcastingHandler`
- **Testcontainers**: Integration tests use Testcontainers for Kafka, with mocked `CoinbaseService`
- **EventCatalog**: `akces-eventcatalog` is a provided-scope dependency for compile-time documentation generation

## 8. Out of Scope

- Authentication/authorization (the `auth` sub-module exists but is a separate concern)
- Counterparty wallet updates on order fills (marked as TODO in source)
- Advanced order types (limit orders, stop-loss, etc.)
- Real-time WebSocket price streaming
- Portfolio valuation and P&L tracking
