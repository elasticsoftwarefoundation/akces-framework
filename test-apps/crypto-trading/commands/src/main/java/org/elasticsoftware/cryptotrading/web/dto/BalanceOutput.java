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

package org.elasticsoftware.cryptotrading.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.BalanceCreatedEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.WalletCreditedEvent;

import java.math.BigDecimal;

public record BalanceOutput(String id,
                            String currency,
                            @JsonSerialize(using = BigDecimalSerializer.class)
                            BigDecimal amount,
                            @JsonSerialize(using = BigDecimalSerializer.class)
                            BigDecimal balance) {
    public static BalanceOutput from(WalletCreditedEvent event) {
        return new BalanceOutput(event.id(), event.currency(), event.amount(), event.balance());
    }

    public static BalanceOutput from(BalanceCreatedEvent event) {
        return new BalanceOutput(event.id(), event.currency(), BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
