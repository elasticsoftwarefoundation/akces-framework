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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

//@SpringBootTest
public class CoinbaseMarketDataTest {

    @Test
    void testCoinbaseGetProducts() {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.exchange.coinbase.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        String response = webClient.get()
                .uri("/products")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        System.out.println(response);
    }

    @Test
    void testCoinbaseGetSingleProduct() {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.exchange.coinbase.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        String response = webClient.get()
                .uri("/products/BTC-EUR")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        System.out.println(response);
    }

    @Test
    void testGetCoinbaseTicker() {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://api.exchange.coinbase.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        String response = webClient.get()
                .uri("/products/BTC-EUR/ticker")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        System.out.println(response);
    }
}
