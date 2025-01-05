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

package org.elasticsoftwarefoundation.cryptotrading.aggregates.wallet.events;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.ErrorEvent;

import javax.annotation.Nonnull;

@DomainEventInfo(type = "InvalidAmountError")
public record InvalidAmountErrorEvent(
        @NotNull @AggregateIdentifier String walletId,
        @NotNull String currency,
        String referenceId
) implements ErrorEvent {
    public InvalidAmountErrorEvent(@NotNull String walletId, @NotNull String currency) {
        this(walletId, currency, null);
    }

    @Override
    public @Nonnull String getAggregateId() {
        return walletId();
    }
}
