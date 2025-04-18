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

package org.elasticsoftware.cryptotrading.web;

import org.elasticsoftware.cryptotrading.query.jdbc.CryptoMarket;
import org.elasticsoftware.cryptotrading.services.CryptoMarketsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v{version:1}/markets")
public class CryptoMarketsQueryController {
    private final CryptoMarketsService cryptoMarketsService;

    public CryptoMarketsQueryController(CryptoMarketsService cryptoMarketsService) {
        this.cryptoMarketsService = cryptoMarketsService;
    }

    @GetMapping
    public Flux<CryptoMarket> getAllMarkets() {
        return Flux.fromIterable(cryptoMarketsService.getAllMarkets());
    }

    @GetMapping("/{marketId}")
    public Mono<ResponseEntity<CryptoMarket>> getMarketById(@PathVariable String marketId) {
        return Mono.justOrEmpty(cryptoMarketsService.getMarketById(marketId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
