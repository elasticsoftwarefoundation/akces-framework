# Crypto Trading Test App — Tasks

> **Status:** All tasks complete (brownfield migration)
> **Module:** `test-apps/crypto-trading/`
> **Prerequisites:** spec.md ✅, plan.md ✅

## Path Conventions

- **Aggregates root**: `test-apps/crypto-trading/aggregates/src/main/java/org/elasticsoftware/cryptotrading/`
- **Commands root**: `test-apps/crypto-trading/commands/src/main/java/org/elasticsoftware/cryptotrading/`
- **Queries root**: `test-apps/crypto-trading/queries/src/main/java/org/elasticsoftware/cryptotrading/`
- **Test command**: `cd test-apps/crypto-trading && mvn test`
- **Full build**: `mvn clean install -pl test-apps/crypto-trading/aggregates,test-apps/crypto-trading/commands,test-apps/crypto-trading/queries`

---

## Phase 1: Project Setup

- [x] T001 Maven parent module `akces-crypto-trading-parent` with sub-modules `aggregates`, `commands`, `queries` — `test-apps/crypto-trading/pom.xml`
- [x] T002 Aggregates module `akces-crypto-trading-aggregates` with dependencies on `akces-api`, `akces-runtime`, `spring-boot-starter-webflux`, `spring-kafka`, and `akces-eventcatalog` (provided scope) — `test-apps/crypto-trading/aggregates/pom.xml`
- [x] T003 Commands module `akces-crypto-trading-commands` with dependencies on `akces-client`, aggregates classifier JARs (`commands`, `events`), `spring-boot-starter-webflux` — `test-apps/crypto-trading/commands/pom.xml`
- [x] T004 Queries module `akces-crypto-trading-queries` with dependencies on `akces-query-support`, `akces-client`, aggregates classifier JARs (`commands`, `events`, `services`), commands classifier JAR (`shared-web`), `spring-data-jpa`, `liquibase` — `test-apps/crypto-trading/queries/pom.xml`
- [x] T005 Aggregates `application.properties` with Kafka bootstrap servers, Spring actuator health probes, graceful shutdown — `aggregates/src/main/resources/application.properties`
- [x] T006 Commands `application.properties` with Kafka bootstrap servers, health probes — `commands/src/main/resources/application.properties`
- [x] T007 Queries `application.properties` with Kafka bootstrap servers, PostgreSQL datasource, Liquibase changelog, domain events package — `queries/src/main/resources/application.properties`

---

## Phase 2: Account Aggregate [US-1]

- [x] T008 [US1] Implement `AccountState` record with `userId`, `country`, and `@PIIData`-annotated `firstName`, `lastName`, `email` — `aggregates/.../account/AccountState.java`
- [x] T009 [US1] Implement `CreateAccountCommand` record with `@CommandInfo(type = "CreateAccount")`, `@AggregateIdentifier userId`, and `@NotNull` validations — `aggregates/.../account/commands/CreateAccountCommand.java`
- [x] T010 [US1] Implement `AccountCreatedEvent` record with `@DomainEventInfo(type = "AccountCreated")` and `@PIIData` fields — `aggregates/.../account/events/AccountCreatedEvent.java`
- [x] T011 [US1] Implement `Account` aggregate with `@AggregateInfo(value = "Account", generateGDPRKeyOnCreate = true, indexed = true, indexName = "Users")`, `@CommandHandler(create = true)` for `CreateAccountCommand`, `@EventSourcingHandler(create = true)` for `AccountCreatedEvent` — `aggregates/.../account/Account.java`

---

## Phase 3: Wallet Aggregate [US-2, US-3]

- [x] T012 [US2] Implement `WalletState` (v1) record with `id`, `List<Balance>` where Balance has `currency`, `amount`, `reservedAmount` — `aggregates/.../wallet/WalletState.java`
- [x] T013 [US2] Implement `WalletStateV2` (v2) record with `id`, `List<Balance>` where Balance has `currency`, `amount`, `List<Reservation>` (per-order reservations); `Balance.getAvailableAmount()` computes `amount - sum(reservations)` — `aggregates/.../wallet/WalletStateV2.java`
- [x] T014 [US2] Implement wallet commands: `CreateWalletCommand`, `CreateBalanceCommand`, `CreditWalletCommand`, `DebitWalletCommand` — `aggregates/.../wallet/commands/`
- [x] T015 [US3] Implement reservation commands: `ReserveAmountCommand` (userId, currency, amount, referenceId), `CancelReservationCommand` (userId, currency, referenceId) — `aggregates/.../wallet/commands/`
- [x] T016 [US2] Implement wallet domain events: `WalletCreatedEvent`, `BalanceCreatedEvent`, `WalletCreditedEvent` (with balance field), `WalletDebitedEvent` (with newBalance field) — `aggregates/.../wallet/events/`
- [x] T017 [US3] Implement reservation events: `AmountReservedEvent` (userId, currency, amount, referenceId), `ReservationCancelledEvent` (id, currency, referenceId) — `aggregates/.../wallet/events/`
- [x] T018 [US2] Implement wallet error events: `BalanceAlreadyExistsErrorEvent`, `InvalidCryptoCurrencyErrorEvent`, `InvalidAmountErrorEvent`, `InsufficientFundsErrorEvent`, `ReservationNotFoundErrorEvent` — `aggregates/.../wallet/events/`
- [x] T019 [US2] Implement `Wallet` aggregate with `@AggregateInfo(value = "Wallet", stateClass = WalletStateV2.class, indexed = true, indexName = "Users")` — `aggregates/.../wallet/Wallet.java`
- [x] T020 [US2] Implement `@UpcastingHandler` to convert `WalletState` (v1) → `WalletStateV2` (v2), mapping `reservedAmount` to `Reservation` entries — `Wallet.java`
- [x] T021 [US2] Implement `@EventHandler(create = true)` on Wallet to create wallet from `AccountCreatedEvent` (emits `WalletCreatedEvent` + `BalanceCreatedEvent` for EUR) — `Wallet.java`
- [x] T022 [US2] Implement `@CommandHandler(create = true)` for `CreateWalletCommand` — `Wallet.java`
- [x] T023 [US2] Implement `@CommandHandler` for `CreateBalanceCommand` with `BalanceAlreadyExistsErrorEvent` check — `Wallet.java`
- [x] T024 [US2] Implement `@CommandHandler` for `CreditWalletCommand` with currency and amount validation — `Wallet.java`
- [x] T025 [US2] Implement `@CommandHandler` for `DebitWalletCommand` with currency, amount, and insufficient funds validation — `Wallet.java`
- [x] T026 [US3] Implement `@CommandHandler` for `ReserveAmountCommand` with available balance check — `Wallet.java`
- [x] T027 [US3] Implement `@CommandHandler` for `CancelReservationCommand` with reservation existence check — `Wallet.java`
- [x] T028 [US2] Implement all `@EventSourcingHandler` methods for wallet state transitions (create, createBalance, credit, debit, reserveAmount, cancelReservation) — `Wallet.java`

---

## Phase 4: CryptoMarket Aggregate [US-4]

- [x] T029 [US4] Implement `CryptoMarketState` record with `id`, `baseCrypto`, `quoteCrypto`, `baseIncrement`, `quoteIncrement`, `defaultCounterPartyId` — `aggregates/.../cryptomarket/CryptoMarketState.java`
- [x] T030 [US4] Implement `Side` enum (`BUY`, `SELL`) — `aggregates/.../cryptomarket/data/Side.java`
- [x] T031 [US4] Implement `CreateCryptoMarketCommand` and `PlaceMarketOrderCommand` — `aggregates/.../cryptomarket/commands/`
- [x] T032 [US4] Implement `CryptoMarketCreatedEvent`, `MarketOrderPlacedEvent`, `MarketOrderFilledEvent`, `MarketOrderRejectedErrorEvent` — `aggregates/.../cryptomarket/events/`
- [x] T033 [US4] Implement `CryptoMarket` aggregate with `@AggregateInfo(value = "CryptoMarket")`, CoinbaseService injection, market order execution with real-time pricing (ask for BUY, bid for SELL) — `aggregates/.../cryptomarket/CryptoMarket.java`
- [x] T034 [US4] Implement `@EventSourcingHandler` methods for CryptoMarket (create, orderPlaced, orderFilled — no-op state for placed/filled since orders execute immediately) — `CryptoMarket.java`

---

## Phase 5: CoinbaseService [US-4]

- [x] T035 [US4] Implement `CoinbaseService` with `@Service`, `WebClient` injection, `getTicker(productId)` method — `aggregates/.../services/coinbase/CoinbaseService.java`
- [x] T036 [US4] Implement `Ticker`, `Product`, `Order` records for Coinbase API response mapping — `aggregates/.../services/coinbase/`
- [x] T037 [US4] Implement `AggregateConfig` with `@Bean coinbaseWebClient` using Coinbase API base URL — `aggregates/.../AggregateConfig.java`

---

## Phase 6: OrderProcessManager [US-5, US-6]

- [x] T038 [US5] Implement `OrderProcess` sealed interface with `orderId()`, `market()`, `size()`, `amount()`, `clientReference()`, `state()` methods, plus error/reject handlers — `aggregates/.../orders/OrderProcess.java`
- [x] T039 [US5] Implement `BuyOrderProcess` record implementing `OrderProcess` with Jackson `@JsonSubTypes` name `"BUY"` — `aggregates/.../orders/BuyOrderProcess.java`
- [x] T040 [US6] Implement `SellOrderProcess` record implementing `OrderProcess` with Jackson `@JsonSubTypes` name `"SELL"` — `aggregates/.../orders/SellOrderProcess.java`
- [x] T041 [US5] Implement `OrderProcessState` enum (`CREATED`, `PLACED`, `REJECTED`, `FILLED`) — `aggregates/.../orders/OrderProcessState.java`
- [x] T042 [US5] Implement `OrderProcessManagerState` record implementing `ProcessManagerState<OrderProcess>` with `getAkcesProcess()` and `hasAkcesProcess()` — `aggregates/.../orders/OrderProcessManagerState.java`
- [x] T043 [US5] Implement `CryptoMarket` data record (orders/data) with `fromId(String)` factory for market ID parsing — `aggregates/.../orders/data/CryptoMarket.java`
- [x] T044 [US5] Implement order process manager commands: `PlaceBuyOrderCommand`, `PlaceSellOrderCommand`, `FillBuyOrderCommand`, `FillSellOrderCommand`, `RejectOrderCommand` — `aggregates/.../orders/commands/`
- [x] T045 [US5] Implement order process manager events: `UserOrderProcessesCreatedEvent`, `BuyOrderCreatedEvent`, `BuyOrderPlacedEvent`, `BuyOrderFilledEvent`, `BuyOrderRejectedEvent`, `SellOrderCreatedEvent`, `SellOrderPlacedEvent`, `SellOrderFilledEvent`, `SellOrderRejectedEvent` — `aggregates/.../orders/events/`
- [x] T046 [US5] Implement `OrderProcessManager` with `@AggregateInfo(value = "OrderProcessManager", indexed = true, indexName = "Users")` — `aggregates/.../orders/OrderProcessManager.java`
- [x] T047 [US5] Implement `@EventHandler(create = true)` for `AccountCreatedEvent` → `UserOrderProcessesCreatedEvent` — `OrderProcessManager.java`
- [x] T048 [US5] Implement `@CommandHandler` for `PlaceBuyOrderCommand`: generate UUID orderId, send `ReserveAmountCommand` to Wallet, emit `BuyOrderCreatedEvent` — `OrderProcessManager.java`
- [x] T049 [US6] Implement `@CommandHandler` for `PlaceSellOrderCommand`: generate UUID orderId, send `ReserveAmountCommand` to Wallet, emit `SellOrderCreatedEvent` — `OrderProcessManager.java`
- [x] T050 [US5] Implement `@EventHandler` for `AmountReservedEvent`: send `PlaceMarketOrderCommand` to CryptoMarket, emit `BuyOrderPlacedEvent` or `SellOrderPlacedEvent` — `OrderProcessManager.java`
- [x] T051 [US5] Implement `@EventHandler` for `InsufficientFundsErrorEvent` and `InvalidCryptoCurrencyErrorEvent`: emit `BuyOrderRejectedEvent` or `SellOrderRejectedEvent` — `OrderProcessManager.java`
- [x] T052 [US5] Implement `@EventBridgeHandler` for `MarketOrderFilledEvent`: send `FillBuyOrderCommand` or `FillSellOrderCommand` to self — `OrderProcessManager.java`
- [x] T053 [US5] Implement `@EventBridgeHandler` for `MarketOrderRejectedErrorEvent`: send `RejectOrderCommand` to self — `OrderProcessManager.java`
- [x] T054 [US5] Implement `@CommandHandler` for `FillBuyOrderCommand`: cancel reservation, debit quote currency, credit base currency, emit `BuyOrderFilledEvent` — `OrderProcessManager.java`
- [x] T055 [US6] Implement `@CommandHandler` for `FillSellOrderCommand`: cancel reservation, debit base currency, credit quote currency (`quantity × price`), emit `SellOrderFilledEvent` — `OrderProcessManager.java`
- [x] T056 [US5] Implement `@CommandHandler` for `RejectOrderCommand`: emit `BuyOrderRejectedEvent` or `SellOrderRejectedEvent` based on process type — `OrderProcessManager.java`
- [x] T057 [US5] Implement all `@EventSourcingHandler` methods for OrderProcessManager state (create, buyOrder created/placed/filled/rejected, sellOrder created/placed/filled/rejected) — `OrderProcessManager.java`

---

## Phase 7: Command Service [US-1, US-2, US-5, US-6]

- [x] T058 Implement `ClientConfig` with AkcesClient configuration — `commands/.../ClientConfig.java`
- [x] T059 [US1] Implement `AccountCommandController` (`POST /v1/accounts`) — create account, return `AccountOutput` — `commands/.../web/AccountCommandController.java`
- [x] T060 [US2] Implement `WalletCommandController` (`POST .../wallet/balances`, `POST .../balances/{currency}/credit`) — create balance, credit wallet — `commands/.../web/WalletCommandController.java`
- [x] T061 [US5] Implement `OrdersCommandController` (`POST .../orders/buy`, `POST .../orders/sell`) — place buy/sell orders — `commands/.../web/OrdersCommandController.java`
- [x] T062 Implement REST DTOs: `AccountInput`, `AccountOutput`, `BalanceOutput`, `BuyOrderInput`, `SellOrderInput`, `CreateBalanceInput`, `CreditWalletInput`, `OrderInput`, `OrderOutput` — `commands/.../web/dto/`
- [x] T063 Implement `ErrorEventException`, `ErrorEventResponse`, `GlobalExceptionHandler` for error event → HTTP error mapping — `commands/.../web/errors/`
- [x] T064 Implement `Jackson3BigDecimalSerializer` for custom BigDecimal JSON output — `commands/.../web/dto/Jackson3BigDecimalSerializer.java`

---

## Phase 8: Query Service [US-7, US-8, US-9, US-10]

- [x] T065 [US7] Implement `WalletQueryModelState` record with `walletId`, `List<Balance>` (currency, amount, reservedAmount) — `queries/.../query/WalletQueryModelState.java`
- [x] T066 [US7] Implement `WalletQueryModel` with `@QueryModelInfo(value = "WalletQueryModel", version = 1, indexName = "Users")` and `@QueryModelEventHandler` methods for WalletCreated (create), BalanceCreated, WalletCredited, WalletDebited — `queries/.../query/WalletQueryModel.java`
- [x] T067 [US8] Implement `AccountQueryModelState` record with `accountId`, `country`, `firstName`, `lastName`, `email` — `queries/.../query/AccountQueryModelState.java`
- [x] T068 [US8] Implement `AccountQueryModel` with `@QueryModelInfo(value = "AccountQueryModel", version = 1, indexName = "Users")` and `@QueryModelEventHandler(create = true)` for AccountCreatedEvent — `queries/.../query/AccountQueryModel.java`
- [x] T069 [US9] Implement `OrderState` enum (CREATED, PLACED, FILLED, REJECTED) — `queries/.../query/OrderState.java`
- [x] T070 [US9] Implement `OrdersQueryModelState` record with `userId`, `List<BuyOrder>`, `List<SellOrder>` — `queries/.../query/OrdersQueryModelState.java`
- [x] T071 [US9] Implement `OrdersQueryModel` with `@QueryModelInfo(value = "OrdersQueryModel", version = 1, indexName = "Users")` and `@QueryModelEventHandler` methods for all order lifecycle events (UserOrderProcessesCreated, BuyOrder created/placed/filled/rejected, SellOrder created/placed/filled/rejected) — `queries/.../query/OrdersQueryModel.java`
- [x] T072 [US10] Implement `CryptoMarket` JPA entity and `CryptoMarketRepository` (Spring Data JPA) — `queries/.../query/jdbc/CryptoMarket.java`, `CryptoMarketRepository.java`
- [x] T073 [US10] Implement `CryptoMarketModel` extending `JdbcDatabaseModel` with `@DatabaseModelInfo(value = "CryptoMarketModel", version = 1, schemaName = "crypto_markets")` and `@DatabaseModelEventHandler` for `CryptoMarketCreatedEvent` — `queries/.../query/CryptoMarketModel.java`
- [x] T074 [US10] Implement `CryptoMarketsService` with `getAllMarkets()` and `getMarketById()` — `queries/.../services/CryptoMarketsService.java`
- [x] T075 [US7] Implement `WalletQueryController` (`GET /v1/accounts/{id}/wallet`) using `QueryModels.getHydratedState()` — `queries/.../web/WalletQueryController.java`
- [x] T076 [US8] Implement `AccountQueryController` (`GET /v1/accounts/{id}`) using `QueryModels.getHydratedState()` — `queries/.../web/AccountQueryController.java`
- [x] T077 [US9] Implement `OrdersQueryController` (`GET .../orders`, `GET .../orders/{orderId}`) — `queries/.../web/OrdersQueryController.java`
- [x] T078 [US10] Implement `CryptoMarketsQueryController` (`GET /v1/markets`, `GET /v1/markets/{id}`) — `queries/.../web/CryptoMarketsQueryController.java`
- [x] T079 Implement `ClientConfig` for queries module — `queries/.../ClientConfig.java`

---

## Phase 9: Integration Tests

- [x] T080 Implement `TestUtils` for aggregates module (shared Kafka/Testcontainers setup, mock CoinbaseService) — `aggregates/src/test/.../TestUtils.java`
- [x] T081 [US5] Implement `BuyTradeTest`: full buy order flow (create account → create wallet → credit → place buy order → market fill) with Testcontainers Kafka and mocked CoinbaseService — `aggregates/src/test/.../BuyTradeTest.java`
- [x] T082 [US6] Implement `SellTradeTest`: full sell order flow with Testcontainers Kafka — `aggregates/src/test/.../SellTradeTest.java`
- [x] T083 [US4] Implement `CoinbaseServiceTest`: live Coinbase API integration test — `aggregates/src/test/.../CoinbaseServiceTest.java`
- [x] T084 [US4] Implement `CoinbaseMarketDataTest`: market data retrieval test — `aggregates/src/test/.../CoinbaseMarketDataTest.java`
- [x] T085 Implement `TestUtils` for commands module — `commands/src/test/.../TestUtils.java`
- [x] T086 Implement `CryptoTradingCommandApiTest`: REST command API integration tests via WebTestClient + Testcontainers — `commands/src/test/.../CryptoTradingCommandApiTest.java`
- [x] T087 Implement `TestUtils` for queries module — `queries/src/test/.../TestUtils.java`
- [x] T088 Implement `CryptoTradingQueryApiTest`: REST query API integration tests via WebTestClient + Testcontainers — `queries/src/test/.../CryptoTradingQueryApiTest.java`
- [x] T089 Implement `CryptoTradingE2ETests`: end-to-end tests covering complete user journeys (create account → credit → trade → query results) — `queries/src/test/.../CryptoTradingE2ETests.java`

---

## Phase 10: Polish

- [x] T090 Liquibase database changelog for `crypto_markets` schema — `queries/src/main/resources/db/changelog/`
- [x] T091 Verify all modules build: `mvn clean install -pl test-apps/crypto-trading/aggregates,test-apps/crypto-trading/commands,test-apps/crypto-trading/queries`
- [x] T092 Verify EventCatalog annotation processing generates documentation in `META-INF/eventcatalog/` for all aggregates

---

## Gap Analysis

### Identified Gaps (existing TODOs in source code)

| ID | Location | Description | Severity | Status |
|----|----------|-------------|----------|--------|
| **GAP-1** | `Wallet.java:95,100,153,159` | Error events for `InvalidCryptoCurrencyErrorEvent` and `InvalidAmountErrorEvent` lack detailed context (e.g., requested vs available amounts) | Low | Open |
| **GAP-2** | `OrderProcessManager.java:194` | `FillBuyOrderCommand` handler debits `buyOrderProcess.amount()` but the debit amount should come from the `BuyOrderFilledEvent`; currently coupled to process state | Low | Open |
| **GAP-3** | `OrderProcessManager.java:206,274` | Counterparty wallet is not updated on order fill — only the initiating user's wallet is debited/credited | Medium | Open |
| **GAP-4** | `OrderProcessManager.java:313` | `AmountReservedEvent` handler has unreachable `else` branch with TODO ("this cannot happen") — should be logged as error or throw | Low | Open |
| **GAP-5** | `WalletCommandController.java:69` | Unexpected event type in `createBalance` response is silently ignored — should handle gracefully | Low | Open |

### Test Coverage Gaps

| ID | Description | Severity |
|----|-------------|----------|
| **GAP-T1** | No dedicated test for `CancelReservationCommand` with `ReservationNotFoundErrorEvent` path | Low |
| **GAP-T2** | No test for `WalletState` → `WalletStateV2` upcasting handler | Medium |
| **GAP-T3** | No test for order rejection due to `InvalidCryptoCurrencyErrorEvent` in the process manager flow | Low |
| **GAP-T4** | No test for `DebitWalletCommand` via REST endpoint (no debit endpoint exposed) | Low |
| **GAP-T5** | No negative test for `CreateCryptoMarketCommand` (duplicate market creation) | Low |
| **GAP-T6** | No test for `CryptoMarketModel` database model event handler in isolation | Low |
| **GAP-T7** | Query controllers return 500 on unexpected errors — no test for internal error paths | Low |

### Missing REST Endpoints

| ID | Description | Severity |
|----|-------------|----------|
| **GAP-E1** | No REST endpoint for `DebitWalletCommand` — wallet debits only happen internally via OrderProcessManager | Low |
| **GAP-E2** | No REST endpoint for `CreateWalletCommand` — wallets are auto-created via `AccountCreatedEvent` handler | Low |
| **GAP-E3** | No REST endpoint for `CreateCryptoMarketCommand` — markets must be created programmatically or via tests | Medium |
| **GAP-E4** | No REST endpoint for `ReserveAmountCommand` / `CancelReservationCommand` — only used internally by process manager | Low |
