/*
 * Copyright 2022 - 2026 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.cryptotrading;

import org.elasticsoftware.cryptotrading.query.OrderState;
import org.elasticsoftware.cryptotrading.query.OrdersQueryModelState;
import org.elasticsoftware.cryptotrading.query.WalletQueryModelState;
import org.elasticsoftware.cryptotrading.web.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class CryptoTradingE2ETests {
    private final WebTestClient e2eTestClient = WebTestClient.bindToServer()
            .baseUrl(System.getenv("AKCES_CRYPTO_TRADING_BASE_URL"))
            .build();

    /**
     * Waits for an order to reach a terminal state (FILLED or REJECTED) by polling the orders API.
     *
     * @param userId the account ID
     * @param orderId the order ID
     * @param timeout maximum time to wait
     */
    private void waitForOrderTerminalState(String userId, String orderId, Duration timeout) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        long pollIntervalMillis = 500; // Poll every 500ms

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            OrdersQueryModelState.BuyOrder order = e2eTestClient.get()
                    .uri("/v1/accounts/{userId}/orders/{orderId}", userId, orderId)
                    .exchange()
                    .expectStatus().is2xxSuccessful()
                    .expectBody(OrdersQueryModelState.BuyOrder.class)
                    .returnResult()
                    .getResponseBody();

            if (order != null && (order.state() == OrderState.FILLED || order.state() == OrderState.REJECTED)) {
                System.out.println("Order " + orderId + " reached terminal state: " + order.state());
                return;
            }

            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for order to reach terminal state");
            }
        }

        fail("Order " + orderId + " did not reach a terminal state within " + timeout.getSeconds() + " seconds");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AKCES_CRYPTO_TRADING_BASE_URL", matches = ".*")
    public void testCreateAndReadAccount() {
        AccountInput accountInput = new AccountInput("NL", "John", "Doe", "john.doe@example.com");
        String userId = e2eTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(accountOutput -> {
                    assertThat(accountOutput).isNotNull();
                    assertThat(accountOutput.userId()).isNotNull();
                    assertThat(accountOutput.country()).isEqualTo("NL");
                    assertThat(accountOutput.firstName()).isEqualTo("John");
                    assertThat(accountOutput.lastName()).isEqualTo("Doe");
                    assertThat(accountOutput.email()).isEqualTo("john.doe@example.com");
                }).returnResult().getResponseBody().userId();

        System.out.println("Created account with userId: " + userId);

        e2eTestClient.get()
                .uri("/v1/accounts/" + userId)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(retrievedAccount -> {
                    assertThat(retrievedAccount).isNotNull();
                    assertThat(retrievedAccount.userId()).isEqualTo(userId);
                    assertThat(retrievedAccount.country()).isEqualTo("NL");
                    assertThat(retrievedAccount.firstName()).isEqualTo("John");
                    assertThat(retrievedAccount.lastName()).isEqualTo("Doe");
                    assertThat(retrievedAccount.email()).isEqualTo("john.doe@example.com");
                });

    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AKCES_CRYPTO_TRADING_BASE_URL", matches = ".*")
    public void testBuyCrypto() {
        AccountInput accountInput = new AccountInput("NL", "Some", "CryptoTrader", "some.cryptotrader@example.com");
        String userId = e2eTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(accountOutput -> {
                    assertThat(accountOutput).isNotNull();
                    assertThat(accountOutput.userId()).isNotNull();
                }).returnResult().getResponseBody().userId();

        System.out.println("Created account with userId: " + userId);

        // Create an ETH wallet
        e2eTestClient.post()
                .uri("/v1/accounts/{userId}/wallet/balances", userId)
                .bodyValue(new CreateBalanceInput("ETH"))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(BalanceOutput.class)
                .value(balanceOutput -> {
                    assertThat(balanceOutput).isNotNull();
                    assertThat(balanceOutput.currency()).isEqualTo("ETH");
                    assertThat(balanceOutput.amount().toString()).isEqualTo("0");
                });

        // credit the EUR wallet with a balance of 1000
        e2eTestClient.post()
                .uri("/v1/accounts/{userId}/wallet/balances/EUR/credit", userId)
                .bodyValue(new CreditWalletInput(new BigDecimal("1000.00")))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(BalanceOutput.class)
                .value(balanceOutput -> {
                    assertThat(balanceOutput).isNotNull();
                    assertThat(balanceOutput.currency()).isEqualTo("EUR");
                    assertThat(balanceOutput.amount().toString()).isEqualTo("1000.00");
                    assertThat(balanceOutput.balance().toString()).isEqualTo("1000.00");
                });

        // buy for 1000 EUR worth of ETH
        String orderId = e2eTestClient.post()
                .uri("/v1/accounts/{userId}/orders/buy", userId)
                .bodyValue(new BuyOrderInput("ETH-EUR", new BigDecimal("1000.00"), "buy-eth-eur"))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(OrderOutput.class)
                .value(orderOutput -> {
                    assertThat(orderOutput).isNotNull();
                    assertThat(orderOutput.orderId()).isNotNull();
                    assertThat(orderOutput.market()).isNotNull();
                    assertThat(orderOutput.market().id()).isEqualTo("ETH-EUR");
                    assertThat(orderOutput.amount().toString()).isEqualTo("1000.00");
                    assertThat(orderOutput.clientReference()).isEqualTo("buy-eth-eur");
                }).returnResult().getResponseBody().orderId();

        // Wait for the order to reach a terminal state (FILLED or REJECTED)
        waitForOrderTerminalState(userId, orderId, Duration.ofSeconds(30));

        // test the wallet balances
        e2eTestClient.get()
                        .uri("/v1/accounts/{userId}/wallet", userId)
                        .exchange()
                        .expectStatus().is2xxSuccessful()
                        .expectBody(WalletQueryModelState.class)
                        .value(wallet -> {
                            assertThat(wallet).isNotNull();
                            assertThat(wallet.balances()).hasSize(2);

                            // Find and verify EUR balance (should be 0 after buying ETH)
                            var eurBalance = wallet.balances().stream()
                                    .filter(b -> "EUR".equals(b.currency()))
                                    .findFirst().orElseThrow();
                            assertThat(eurBalance.amount().toString()).isEqualTo("0.00");

                            // Find and verify ETH balance (should be 10.00 after buying)
                            var ethBalance = wallet.balances().stream()
                                    .filter(b -> "ETH".equals(b.currency()))
                                    .findFirst().orElseThrow();
                            assertThat(ethBalance.amount()).isNotEqualTo(BigDecimal.ZERO);
                        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AKCES_CRYPTO_TRADING_BASE_URL", matches = ".*")
    public void testBtcEurMarket() {
        // Test retrieving the BTC-EUR market
        e2eTestClient.get()
                .uri("/v1/markets/BTC-EUR")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody()
                .jsonPath("$.id").isEqualTo("BTC-EUR")
                .jsonPath("$.baseCrypto").isEqualTo("BTC")
                .jsonPath("$.quoteCrypto").isEqualTo("EUR")
                .jsonPath("$.baseIncrement").exists()
                .jsonPath("$.quoteIncrement").exists()
                .jsonPath("$.defaultCounterPartyId").exists();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AKCES_CRYPTO_TRADING_BASE_URL", matches = ".*")
    public void testSellCrypto() {
        AccountInput accountInput = new AccountInput("NL", "Crypto", "Seller", "crypto.seller@example.com");
        String userId = e2eTestClient.post()
                .uri("/v1/accounts")
                .bodyValue(accountInput)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(accountOutput -> {
                    assertThat(accountOutput).isNotNull();
                    assertThat(accountOutput.userId()).isNotNull();
                }).returnResult().getResponseBody().userId();

        System.out.println("Created account with userId: " + userId);

        // Create a BTC wallet and credit it with 1 BTC
        e2eTestClient.post()
                .uri("/v1/accounts/{userId}/wallet/balances", userId)
                .bodyValue(new CreateBalanceInput("BTC"))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(BalanceOutput.class)
                .value(balanceOutput -> {
                    assertThat(balanceOutput).isNotNull();
                    assertThat(balanceOutput.currency()).isEqualTo("BTC");
                    assertThat(balanceOutput.amount().toString()).isEqualTo("0");
                });

        // Credit BTC balance with 1 BTC
        e2eTestClient.post()
                .uri("/v1/accounts/{userId}/wallet/balances/BTC/credit", userId)
                .bodyValue(new CreditWalletInput(new BigDecimal("1.00")))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(BalanceOutput.class)
                .value(balanceOutput -> {
                    assertThat(balanceOutput).isNotNull();
                    assertThat(balanceOutput.currency()).isEqualTo("BTC");
                    assertThat(balanceOutput.amount().toString()).isEqualTo("1.00");
                    assertThat(balanceOutput.balance().toString()).isEqualTo("1.00");
                });

        // Create EUR balance
        e2eTestClient.post()
                .uri("/v1/accounts/{userId}/wallet/balances", userId)
                .bodyValue(new CreateBalanceInput("EUR"))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Sell 0.5 BTC for EUR
        String orderId = e2eTestClient.post()
                .uri("/v1/accounts/{userId}/orders/sell", userId)
                .bodyValue(new SellOrderInput("BTC-EUR", new BigDecimal("0.50"), "sell-btc-eur"))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(OrderOutput.class)
                .value(orderOutput -> {
                    assertThat(orderOutput).isNotNull();
                    assertThat(orderOutput.orderId()).isNotNull();
                    assertThat(orderOutput.market()).isNotNull();
                    assertThat(orderOutput.market().id()).isEqualTo("BTC-EUR");
                    assertThat(orderOutput.amount().toString()).isEqualTo("0.50");
                    assertThat(orderOutput.clientReference()).isEqualTo("sell-btc-eur");
                }).returnResult().getResponseBody().orderId();

        // Wait for the order to reach a terminal state (FILLED or REJECTED)
        waitForSellOrderTerminalState(userId, orderId, Duration.ofSeconds(30));

        // Test the wallet balances
        e2eTestClient.get()
                .uri("/v1/accounts/{userId}/wallet", userId)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(WalletQueryModelState.class)
                .value(wallet -> {
                    assertThat(wallet).isNotNull();
                    assertThat(wallet.balances()).hasSize(2);

                    // Find and verify BTC balance (should be 0.5 after selling 0.5 BTC)
                    var btcBalance = wallet.balances().stream()
                            .filter(b -> "BTC".equals(b.currency()))
                            .findFirst().orElseThrow();
                    assertThat(btcBalance.amount().toString()).isEqualTo("0.50");

                    // Find and verify EUR balance (should have increased after selling BTC)
                    var eurBalance = wallet.balances().stream()
                            .filter(b -> "EUR".equals(b.currency()))
                            .findFirst().orElseThrow();
                    assertThat(eurBalance.amount()).isNotEqualTo(BigDecimal.ZERO);
                });
    }

    /**
     * Waits for a sell order to reach a terminal state (FILLED or REJECTED) by polling the orders API.
     *
     * @param userId the account ID
     * @param orderId the order ID
     * @param timeout maximum time to wait
     */
    private void waitForSellOrderTerminalState(String userId, String orderId, Duration timeout) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        long pollIntervalMillis = 500; // Poll every 500ms

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            OrdersQueryModelState.SellOrder order = e2eTestClient.get()
                    .uri("/v1/accounts/{userId}/orders/{orderId}", userId, orderId)
                    .exchange()
                    .expectStatus().is2xxSuccessful()
                    .expectBody(OrdersQueryModelState.SellOrder.class)
                    .returnResult()
                    .getResponseBody();

            if (order != null && (order.state() == OrderState.FILLED || order.state() == OrderState.REJECTED)) {
                System.out.println("Sell order " + orderId + " reached terminal state: " + order.state());
                return;
            }

            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for sell order to reach terminal state");
            }
        }

        fail("Sell order " + orderId + " did not reach a terminal state within " + timeout.getSeconds() + " seconds");
    }
}
