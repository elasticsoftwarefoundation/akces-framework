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

import jakarta.inject.Inject;
import org.elasticsoftware.cryptotrading.services.coinbase.CoinbaseService;
import org.elasticsoftware.cryptotrading.services.coinbase.Product;
import org.elasticsoftware.cryptotrading.services.coinbase.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = {AggregateConfig.class})
public class CoinbaseServiceTest {
    @Inject
    private CoinbaseService coinbaseService;

    @Test
    public void testGetProductById() {
        String productId = "BTC-USD";
        Product product = coinbaseService.getProduct(productId);
        assertNotNull(product, "Product should not be null");
        // check all properties of product
    }

    @Test
    public void testGetTickerById() {
        String productId = "BTC-USD";
        Ticker ticker = coinbaseService.getTicker(productId);
        assertNotNull(ticker, "Ticker should not be null");
        // check all properties of ticker
    }

    @Test
    public void testGetProducts() {
        List<Product> products = coinbaseService.getProducts();
        assertNotNull(products, "Products should not be null");
        products.forEach(product -> {
            System.out.println(product.id());
        });
    }

}
