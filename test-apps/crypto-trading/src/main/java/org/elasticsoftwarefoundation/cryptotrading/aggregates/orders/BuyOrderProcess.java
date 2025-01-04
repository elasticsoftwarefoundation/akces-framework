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

package org.elasticsoftwarefoundation.cryptotrading.aggregates.orders;

import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.wallet.InsufficientFundsErrorEvent;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.wallet.InvalidCryptoCurrencyErrorEvent;

import java.math.BigDecimal;

public record BuyOrderProcess(String orderId, CryptoMarket market, BigDecimal quantity, BigDecimal limitPrice, String clientReference) implements OrderProcess {
    @Override
    public String getProcessId() {
        return orderId();
    }

    @Override
    public DomainEvent handle(InsufficientFundsErrorEvent error) {
        return new BuyOrderRejectedEvent(error.walletId(), orderId(), clientReference());
    }

    @Override
    public DomainEvent handle(InvalidCryptoCurrencyErrorEvent error) {
        return new BuyOrderRejectedEvent(error.walletId(), orderId(), clientReference());
    }
}
