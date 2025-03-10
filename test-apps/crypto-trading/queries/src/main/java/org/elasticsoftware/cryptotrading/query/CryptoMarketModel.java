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

package org.elasticsoftware.cryptotrading.query;

import org.elasticsoftware.akces.annotations.DatabaseModelEventHandler;
import org.elasticsoftware.akces.annotations.DatabaseModelInfo;
import org.elasticsoftware.akces.query.database.jdbc.JdbcDatabaseModel;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.events.CryptoMarketCreatedEvent;
import org.elasticsoftware.cryptotrading.query.jdbc.CryptoMarket;
import org.elasticsoftware.cryptotrading.query.jdbc.CryptoMarketRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@DatabaseModelInfo(value = "CryptoMarketModel", version = 1, schemaName = "crypto_markets")
public class CryptoMarketModel extends JdbcDatabaseModel {
    private final CryptoMarketRepository cryptoMarketRepository;

    public CryptoMarketModel(PlatformTransactionManager transactionManager,
                             JdbcTemplate jdbcTemplate,
                             CryptoMarketRepository cryptoMarketRepository) {
        super(transactionManager, jdbcTemplate);
        this.cryptoMarketRepository = cryptoMarketRepository;
    }

    @DatabaseModelEventHandler
    public void handle(CryptoMarketCreatedEvent event) {
        cryptoMarketRepository.save(new CryptoMarket(
                event.id(),
                event.baseCrypto(),
                event.quoteCrypto(),
                event.baseIncrement(),
                event.quoteIncrement(),
                event.defaultCounterPartyId()
        ));
    }
}
