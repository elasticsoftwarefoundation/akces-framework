/*
 * Copyright 2022 - 2025 The Original Authors
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

import org.elasticsoftware.cryptotrading.query.WalletQueryModelState;
import org.elasticsoftware.cryptotrading.web.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

public class CryptoTradingE2ETests {
    private final WebTestClient e2eTestClient = WebTestClient.bindToServer()
            .baseUrl(System.getenv("AKCES_CRYPTO_TRADING_BASE_URL"))
            .build();

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
                    assertThat(balanceOutput.amount()).isEqualTo(BigDecimal.ZERO);
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
                    assertThat(balanceOutput.amount()).isEqualTo(new BigDecimal("1000.00"));
                    assertThat(balanceOutput.balance()).isEqualTo(new BigDecimal("1000.00"));
                });

        // buy for 1000 EUR worth of ETH
        e2eTestClient.post()
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
                    assertThat(orderOutput.amount()).isEqualTo(new BigDecimal("1000.00"));
                    assertThat(orderOutput.clientReference()).isEqualTo("buy-eth-eur");
                });

        // We need to wait for the order to be filled
        // TODO: we need to check the order API for the status of the order
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            // ignore
        }

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
                            assertThat(eurBalance.amount()).isEqualTo(new BigDecimal("0.00"));

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
}
