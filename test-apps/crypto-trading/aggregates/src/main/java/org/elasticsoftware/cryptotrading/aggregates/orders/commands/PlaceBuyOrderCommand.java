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

package org.elasticsoftware.cryptotrading.aggregates.orders.commands;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.cryptotrading.aggregates.orders.CryptoMarket;

import java.math.BigDecimal;

@CommandInfo(type = "PlaceBuyOrder", version = 1)
public record PlaceBuyOrderCommand(
        @NotNull @AggregateIdentifier String userId,
        @NotNull CryptoMarket market,
        @NotNull BigDecimal amount,
        @NotNull String clientReference
) implements Command {
    @Override
    @NotNull
    public String getAggregateId() {
        return userId();
    }
}
package org.elasticsoftware.cryptotrading.aggregates.orders.commands;

import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandInfo;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

@CommandInfo(type = "PlaceBuyOrder")
public record PlaceBuyOrderCommand(
        String accountId,
        String marketId,
        BigDecimal size,
        BigDecimal amount,
        String clientReference
) implements Command {
    @Override
    public @NotNull String getAggregateId() {
        return accountId;
    }
}
