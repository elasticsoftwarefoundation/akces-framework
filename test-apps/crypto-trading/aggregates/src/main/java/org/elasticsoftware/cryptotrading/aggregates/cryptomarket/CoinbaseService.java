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

package org.elasticsoftware.cryptotrading.aggregates.cryptomarket;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
public class CoinbaseService {
    private final WebClient webClient;

    public CoinbaseService(@Qualifier("coinbaseWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Ticker getTicker(String productId) {
        return webClient.get()
                .uri("/products/{productId}/ticker", productId)
                .retrieve()
                .bodyToMono(Ticker.class)
                .block();
    }

    public Product getProduct(String productId) {
        return webClient.get()
                .uri("/products/{productId}", productId)
                .retrieve()
                .bodyToMono(Product.class)
                .block();
    }

    public List<Product> getProducts() {
        return webClient.get()
                .uri("/products")
                .retrieve()
                .bodyToFlux(Product.class)
                .collectList()
                .block();
    }


}
