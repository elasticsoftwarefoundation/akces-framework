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

package org.elasticsoftware.cryptotrading.aggregates.orders.events;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.CryptoMarket;

import java.math.BigDecimal;

@DomainEventInfo(type = "BuyOrderPlaced", version = 1)
public record BuyOrderPlacedEvent(
        @NotNull @AggregateIdentifier String userId,
        @NotNull String orderId,
        @NotNull CryptoMarket market,
        @NotNull BigDecimal amount,
        BigDecimal limitPrice
) implements DomainEvent {
    @Override
    public String getAggregateId() {
        return orderId();
    }
}
package org.elasticsoftware.cryptotrading.aggregates.orders.events;

import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.DomainEventInfo;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

@DomainEventInfo(type = "BuyOrderPlaced", version = 1)
public record BuyOrderPlacedEvent(
        String accountId,
        String orderId,
        String marketId,
        BigDecimal size,
        BigDecimal amount,
        String clientReference
) implements DomainEvent {

    @Override
    public @NotNull String getAggregateId() {
        return accountId;
    }
}
