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

package org.elasticsoftware.cryptotrading.services;

import jakarta.annotation.PostConstruct;
import org.elasticsoftware.akces.client.AkcesClient;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.commands.CreateCryptoMarketCommand;
import org.elasticsoftware.cryptotrading.query.jdbc.CryptoMarket;
import org.elasticsoftware.cryptotrading.query.jdbc.CryptoMarketRepository;
import org.elasticsoftware.cryptotrading.services.coinbase.CoinbaseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CryptoMarketsService {
    private final CryptoMarketRepository cryptoMarketRepository;
    private final CoinbaseService coinbaseService;
    private final AkcesClient akcesClient;
    private final String counterPartyId;

    public CryptoMarketsService(CryptoMarketRepository cryptoMarketRepository,
                                CoinbaseService coinbaseService,
                                AkcesClient akcesClient,
                                @Value("${akces.cryptotrading.counterPartyId}") String counterPartyId) {
        this.cryptoMarketRepository = cryptoMarketRepository;
        this.coinbaseService = coinbaseService;
        this.akcesClient = akcesClient;
        this.counterPartyId = counterPartyId;
    }

    @PostConstruct
    public void init() {
        coinbaseService.getProducts().stream()
                .filter(product -> !cryptoMarketRepository.existsById(product.id()))
                .forEach(product -> akcesClient.sendAndForget(new CreateCryptoMarketCommand(
                        product.id(),
                        product.baseCurrency(),
                        product.quoteCurrency(),
                        product.baseIncrement(),
                        product.quoteIncrement(),
                        counterPartyId)));
    }

    @Transactional(readOnly = true)
    public List<CryptoMarket> getAllMarkets() {
        return cryptoMarketRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<CryptoMarket> getMarketById(String id) {
        return cryptoMarketRepository.findById(id);
    }
}
