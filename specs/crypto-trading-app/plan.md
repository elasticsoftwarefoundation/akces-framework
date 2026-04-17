# Crypto Trading Test App — Implementation Plan

> **Status:** Migrated
> **Module:** `test-apps/crypto-trading/` (`akces-crypto-trading-parent`)
> **Spec:** `specs/crypto-trading-app/spec.md`
> **Input:** Brownfield migration — reference implementation already complete (92 source files, ~4683 lines; 10 test files, ~3099 test lines)

## 1. Summary

The Crypto Trading Test App is a multi-module reference implementation demonstrating the complete Akces CQRS/Event Sourcing framework in a crypto trading domain. It spans three Maven sub-modules: `aggregates` (domain logic with 4 aggregates and a process manager), `commands` (REST command API with Spring WebFlux controllers), and `queries` (read-side projections with in-memory query models, a JDBC database model, and REST query endpoints). The app integrates with the Coinbase API for real-time market pricing and uses PostgreSQL for persistent query storage.

## 2. Technical Context

| Attribute | Value |
|-----------|-------|
| Language | Java 25+ |
| Framework | Spring Boot 4.x (WebFlux) |
| Messaging | Apache Kafka 4.x (KRaft mode) |
| Serialization | Jackson 3.x (`tools.jackson`) |
| Database | PostgreSQL (via Spring Data JPA + Liquibase) |
| External API | Coinbase Exchange API (REST via `WebClient`) |
| Build | Maven (parent `akces-framework-test-apps`) |
| Test | JUnit 5, Testcontainers (Kafka), Mockito, Spring Boot Test, WebTestClient |
| Modules | `aggregates`, `commands`, `queries` |

### Internal Akces Dependencies

| Artifact | Used By | Provides |
|----------|---------|----------|
| `akces-api` | aggregates, queries | Core interfaces, annotations (`@AggregateInfo`, `@CommandInfo`, `@DomainEventInfo`, `@PIIData`, etc.) |
| `akces-runtime` | aggregates | Event sourcing runtime, aggregate lifecycle, Kafka event store |
| `akces-client` | commands, queries (test) | `AkcesClient` for command submission, auto-configuration |
| `akces-query-support` | queries | `QueryModel`, `DatabaseModel`, `QueryModels`, `JdbcDatabaseModel` |
| `akces-eventcatalog` | aggregates (provided) | Compile-time EventCatalog documentation generation |

### Cross-Module Dependency Pattern

The `queries` module depends on `aggregates` via classifier-separated JARs:
- `akces-crypto-trading-aggregates:commands` — command classes
- `akces-crypto-trading-aggregates:events` — event classes
- `akces-crypto-trading-aggregates:services` — aggregate service metadata

The `commands` module shares web DTOs with `queries` via:
- `akces-crypto-trading-commands:shared-web` — DTO classes

## 3. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         REST Clients                                 │
│  POST /v1/accounts, /orders/buy, /orders/sell, /wallet/balances/... │
│  GET  /v1/accounts/{id}, /wallet, /orders, /markets                 │
└────────────┬──────────────────────────────────┬─────────────────────┘
             │ Commands                         │ Queries
┌────────────▼──────────────┐     ┌─────────────▼────────────────────┐
│   commands module          │     │   queries module                  │
│   CommandControllers       │     │   QueryControllers                │
│   ├─ AccountCommandCtrl    │     │   ├─ AccountQueryCtrl             │
│   ├─ WalletCommandCtrl     │     │   ├─ WalletQueryCtrl              │
│   └─ OrdersCommandCtrl     │     │   ├─ OrdersQueryCtrl              │
│                            │     │   └─ CryptoMarketsQueryCtrl       │
│   AkcesClient.send()       │     │                                   │
│   └─ Kafka Commands topic  │     │   QueryModels.getHydratedState()  │
└────────────┬───────────────┘     │   CryptoMarketsService            │
             │                     └──────┬──────────────┬─────────────┘
             │ Kafka                      │ Kafka Events │ PostgreSQL
┌────────────▼────────────────────────────▼──────────────┘
│   aggregates module                                     │
│                                                         │
│   ┌──────────┐  ┌───────────┐  ┌──────────────┐       │
│   │ Account  │  │  Wallet   │  │ CryptoMarket │       │
│   │ (GDPR)   │  │ (Upcasted)│  │ (Coinbase)   │       │
│   └────┬─────┘  └─────┬─────┘  └──────┬───────┘       │
│        │              │               │                 │
│        └──────┬───────┘               │                 │
│               ▼                       │                 │
│   ┌───────────────────────┐           │                 │
│   │  OrderProcessManager  │◄──────────┘                 │
│   │  (coordinates orders) │  @EventBridgeHandler        │
│   └───────────────────────┘                             │
└─────────────────────────────────────────────────────────┘
```

## 4. Component Inventory

### 4.1 Aggregates Module (59 source files)

| # | Component | File(s) | Responsibility |
|---|-----------|---------|----------------|
| 1 | `Account` aggregate | `Account.java`, `AccountState.java` | User account with GDPR PII fields |
| 2 | `Wallet` aggregate | `Wallet.java`, `WalletState.java`, `WalletStateV2.java` | Multi-currency wallet with reservations; state upcasting v1→v2 |
| 3 | `CryptoMarket` aggregate | `CryptoMarket.java`, `CryptoMarketState.java` | Market order execution via Coinbase |
| 4 | `OrderProcessManager` | `OrderProcessManager.java`, `OrderProcessManagerState.java` | Cross-aggregate buy/sell order coordination |
| 5 | Process types | `OrderProcess.java`, `BuyOrderProcess.java`, `SellOrderProcess.java`, `OrderProcessState.java` | Sealed process hierarchy with Jackson polymorphism |
| 6 | Wallet commands (6) | `CreateWalletCommand.java`, `CreateBalanceCommand.java`, `CreditWalletCommand.java`, `DebitWalletCommand.java`, `ReserveAmountCommand.java`, `CancelReservationCommand.java` | Wallet command set |
| 7 | Wallet events (7) | `WalletCreatedEvent.java`, `BalanceCreatedEvent.java`, `WalletCreditedEvent.java`, `WalletDebitedEvent.java`, `AmountReservedEvent.java`, `ReservationCancelledEvent.java` | Wallet domain events |
| 8 | Wallet errors (5) | `BalanceAlreadyExistsErrorEvent.java`, `InvalidCryptoCurrencyErrorEvent.java`, `InvalidAmountErrorEvent.java`, `InsufficientFundsErrorEvent.java`, `ReservationNotFoundErrorEvent.java` | Wallet error events |
| 9 | Account commands (1) | `CreateAccountCommand.java` | Account creation |
| 10 | Account events (1) | `AccountCreatedEvent.java` | Account created with PII |
| 11 | Market commands (2) | `CreateCryptoMarketCommand.java`, `PlaceMarketOrderCommand.java` | Market management |
| 12 | Market events (3+1) | `CryptoMarketCreatedEvent.java`, `MarketOrderPlacedEvent.java`, `MarketOrderFilledEvent.java`, `MarketOrderRejectedErrorEvent.java` | Market events |
| 13 | Order PM commands (5) | `PlaceBuyOrderCommand.java`, `PlaceSellOrderCommand.java`, `FillBuyOrderCommand.java`, `FillSellOrderCommand.java`, `RejectOrderCommand.java` | Order process commands |
| 14 | Order PM events (9) | `UserOrderProcessesCreatedEvent.java`, `BuyOrderCreated/Placed/Filled/RejectedEvent.java`, `SellOrderCreated/Placed/Filled/RejectedEvent.java` | Order lifecycle events |
| 15 | `CoinbaseService` | `CoinbaseService.java`, `Ticker.java`, `Product.java`, `Order.java` | External Coinbase API client |
| 16 | `AggregateConfig` | `AggregateConfig.java` | Spring configuration (WebClient bean) |
| 17 | Data types | `Side.java`, `CryptoMarket.java` (orders data) | Shared enums and value objects |

### 4.2 Commands Module (17 source files)

| # | Component | File(s) | Responsibility |
|---|-----------|---------|----------------|
| 1 | `AccountCommandController` | `AccountCommandController.java` | `POST /v1/accounts` |
| 2 | `WalletCommandController` | `WalletCommandController.java` | `POST /v1/accounts/{id}/wallet/balances`, `.../credit` |
| 3 | `OrdersCommandController` | `OrdersCommandController.java` | `POST /v1/accounts/{id}/orders/buy`, `.../sell` |
| 4 | DTOs (8) | `AccountInput.java`, `AccountOutput.java`, `BalanceOutput.java`, `BuyOrderInput.java`, `SellOrderInput.java`, `CreateBalanceInput.java`, `CreditWalletInput.java`, `OrderInput.java`, `OrderOutput.java` | REST request/response DTOs |
| 5 | Error handling | `ErrorEventException.java`, `ErrorEventResponse.java`, `GlobalExceptionHandler.java` | Error event → HTTP error mapping |
| 6 | `ClientConfig` | `ClientConfig.java` | `AkcesClient` configuration |
| 7 | `Jackson3BigDecimalSerializer` | `Jackson3BigDecimalSerializer.java` | Custom BigDecimal JSON serialization |

### 4.3 Queries Module (16 source files)

| # | Component | File(s) | Responsibility |
|---|-----------|---------|----------------|
| 1 | `WalletQueryModel` | `WalletQueryModel.java`, `WalletQueryModelState.java` | In-memory wallet projection |
| 2 | `AccountQueryModel` | `AccountQueryModel.java`, `AccountQueryModelState.java` | In-memory account projection |
| 3 | `OrdersQueryModel` | `OrdersQueryModel.java`, `OrdersQueryModelState.java`, `OrderState.java` | In-memory orders projection |
| 4 | `CryptoMarketModel` | `CryptoMarketModel.java` | JDBC database model for markets |
| 5 | JPA entities | `CryptoMarket.java`, `CryptoMarketRepository.java` | Spring Data JPA persistence |
| 6 | `CryptoMarketsService` | `CryptoMarketsService.java` | Market query service |
| 7 | `WalletQueryController` | `WalletQueryController.java` | `GET /v1/accounts/{id}/wallet` |
| 8 | `AccountQueryController` | `AccountQueryController.java` | `GET /v1/accounts/{id}` |
| 9 | `OrdersQueryController` | `OrdersQueryController.java` | `GET /v1/accounts/{id}/orders`, `.../orders/{orderId}` |
| 10 | `CryptoMarketsQueryController` | `CryptoMarketsQueryController.java` | `GET /v1/markets`, `/v1/markets/{id}` |
| 11 | `ClientConfig` | `ClientConfig.java` | Client configuration for queries |

### 4.4 Test Files (10)

| # | File | Module | Scope |
|---|------|--------|-------|
| 1 | `BuyTradeTest.java` | aggregates | Full buy order flow: account → wallet → reserve → market order → fill |
| 2 | `SellTradeTest.java` | aggregates | Full sell order flow |
| 3 | `CoinbaseServiceTest.java` | aggregates | External Coinbase API integration |
| 4 | `CoinbaseMarketDataTest.java` | aggregates | Market data retrieval |
| 5 | `TestUtils.java` | aggregates | Shared test utilities |
| 6 | `CryptoTradingCommandApiTest.java` | commands | REST command API integration tests |
| 7 | `TestUtils.java` | commands | Command test utilities |
| 8 | `CryptoTradingQueryApiTest.java` | queries | REST query API integration tests |
| 9 | `CryptoTradingE2ETests.java` | queries | End-to-end tests (commands + queries) |
| 10 | `TestUtils.java` | queries | Query test utilities |

## 5. Key Design Decisions

### D-1: Cross-Aggregate Coordination via Process Manager

The `OrderProcessManager` implements the saga pattern using `@EventHandler` and `@EventBridgeHandler`. Buy/sell orders span three aggregates (OrderProcessManager → Wallet → CryptoMarket) with compensating actions on failure (reservation cancellation, order rejection). `@EventBridgeHandler` is used for events where the aggregate ID differs (CryptoMarket events routed to the order owner's process manager).

### D-2: State Upcasting for Schema Evolution

`WalletStateV2` replaces `WalletState` via `@UpcastingHandler`, converting the single `reservedAmount` field per balance into a `List<Reservation>` with per-order reference tracking. This demonstrates the framework's schema evolution capabilities.

### D-3: Sealed Process Type Hierarchy

`OrderProcess` is a sealed interface with Jackson `@JsonTypeInfo`/`@JsonSubTypes`, permitting `BuyOrderProcess` and `SellOrderProcess`. This ensures type-safe polymorphic serialization of process state within the `OrderProcessManagerState`.

### D-4: Reactive REST with Synchronous Akces Client

Controllers use `Mono.fromCompletionStage(akcesClient.send(...))` to bridge the Akces `CompletionStage` API with Spring WebFlux's reactive model. Error events are mapped to `ErrorEventException` via a `GlobalExceptionHandler`.

### D-5: Classifier-Separated Module Artifacts

The `aggregates` module produces separate classified JARs (`commands`, `events`, `services`) so that the `queries` and `commands` modules can depend only on the message classes they need, avoiding a full dependency on aggregate implementation.

### D-6: External Market Integration

The `CryptoMarket` aggregate injects `CoinbaseService` (a Spring `@Service` using `WebClient`) to fetch real-time ticker prices. In tests, this is mocked via Mockito.

## 6. Integration Points

| System | Protocol | Direction | Module |
|--------|----------|-----------|--------|
| Kafka (event store) | Protobuf protocol records | Produce + Consume | aggregates |
| Kafka (commands) | Protobuf protocol records | Produce (commands) + Consume (aggregates) | commands, aggregates |
| Kafka (query events) | Protobuf protocol records | Consume | queries |
| Coinbase API | REST (HTTPS) | Consume | aggregates |
| PostgreSQL | JDBC + Spring Data JPA | Read/Write | queries |

## 7. Risk & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Coinbase API unavailability | Market orders fail | Mock in tests; production should add circuit breaker |
| Process manager state corruption | Stuck orders | Idempotent event handlers; order process state machine |
| PII data exposure | GDPR violation | `@PIIData` encryption enforced by framework; `generateGDPRKeyOnCreate` enabled |
| Cross-module JAR versioning | Incompatible events | All modules share same parent version (`0.12.1-SNAPSHOT`) |

## 8. Test Strategy

| Layer | Tool | Scope |
|-------|------|-------|
| Integration (aggregates) | Testcontainers Kafka + Spring Boot Test | Full buy/sell trade flows with mocked Coinbase |
| Integration (commands) | WebTestClient + Testcontainers Kafka | REST command endpoints end-to-end |
| Integration (queries) | WebTestClient + Testcontainers Kafka | REST query endpoints, query model hydration |
| E2E | Full stack (commands + queries + aggregates) | Complete user journey: create account → credit → trade → query |
| External API | Live Coinbase API | CoinbaseService integration (non-CI) |

## 9. Complexity Tracking

No violations — this is a reference/test application with well-bounded scope per module.
