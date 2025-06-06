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

package org.elasticsoftware.cryptotrading.aggregates.wallet;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;

import java.math.BigDecimal;
import java.util.List;

@AggregateStateInfo(type = "Wallet", version = 2)
public record WalletStateV2(
        @AggregateIdentifier @NotNull String id,
        List<Balance> balances
) implements AggregateState {
    @Override
    public String getAggregateId() {
        return id();
    }

    public record Balance(@NotNull String currency,@NotNull BigDecimal amount,@NotNull List<Reservation> reservations) {
        public Balance(@NotNull String currency) {
            this(currency, BigDecimal.ZERO, List.of());
        }

        public Balance(@NotNull String currency, @NotNull BigDecimal amount) {
            this(currency, amount, List.of());
        }

        @JsonIgnore
        public BigDecimal getAvailableAmount() {
            return amount.subtract(reservations().stream().map(Reservation::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        }
    }

    public record Reservation(
            @NotNull String referenceId,
            @NotNull BigDecimal amount
    ) {
        public Reservation {
            // Defensive validation
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Reservation amount must be positive");
            }
        }
    }
}
