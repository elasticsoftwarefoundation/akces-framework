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

import org.elasticsoftware.cryptotrading.web.dto.AccountInput;
import org.elasticsoftware.cryptotrading.web.dto.AccountOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.reactive.server.WebTestClient;

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

        e2eTestClient.get()
                .uri("/v1/accounts/" + userId)
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody(AccountOutput.class)
                .value(retrievedAccount -> {
                    assertThat(retrievedAccount).isNotNull();
                    assertThat(retrievedAccount.userId()).isEqualTo(userId);
                    assertThat(retrievedAccount.country()).isEqualTo("US");
                    assertThat(retrievedAccount.firstName()).isEqualTo("John");
                    assertThat(retrievedAccount.lastName()).isEqualTo("Doe");
                    assertThat(retrievedAccount.email()).isEqualTo("john.doe@example.com");
                });

    }
}
