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
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Table("crypto_markets")
public record CryptoMarket(@Id @NotNull String id,
                           @NotNull String baseCrypto,
                           @NotNull String quoteCrypto,
                           @NotNull String baseIncrement,
                           @NotNull String quoteIncrement,
                           @NotNull String defaultCounterPartyId) implements Persistable<String> {
    @Override
    public String getId() {
        return id();
    }

    @Transient
    @Override
    public boolean isNew() {
        // TODO: this will only hold true on creation, but we don't have a way to know if the entity is new or not
        return true;
    }
}
