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

package org.elasticsoftware.cryptotrading.query.jdbc;

import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Table("crypto_markets")
public record CryptoMarket(@Id @NotNull String id,
                           @NotNull String baseCrypto,
                           @NotNull String quoteCrypto,
                           @NotNull String baseIncrement,
                           @NotNull String quoteIncrement,
                           @NotNull String defaultCounterPartyId,
                           @Transient boolean createNew) implements Persistable<String> {

    @PersistenceCreator
    public CryptoMarket(@NotNull String id,
                      @NotNull String baseCrypto,
                      @NotNull String quoteCrypto,
                      @NotNull String baseIncrement,
                      @NotNull String quoteIncrement,
                      @NotNull String defaultCounterPartyId) {
        this(id, baseCrypto, quoteCrypto, baseIncrement, quoteIncrement, defaultCounterPartyId, false);
    }

    public static CryptoMarket createNew(@NotNull String id,
                                     @NotNull String baseCrypto,
                                     @NotNull String quoteCrypto,
                                     @NotNull String baseIncrement,
                                     @NotNull String quoteIncrement,
                                     @NotNull String defaultCounterPartyId) {
        return new CryptoMarket(id, baseCrypto, quoteCrypto, baseIncrement, quoteIncrement, defaultCounterPartyId, true);
    }

    @Override
    public String getId() {
        return id();
    }

    @Transient
    public boolean createNew() {
        return createNew;
    }

    @Transient
    @Override
    public boolean isNew() {
        return createNew;
    }
}