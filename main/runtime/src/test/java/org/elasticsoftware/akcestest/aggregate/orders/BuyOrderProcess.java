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

package org.elasticsoftware.akcestest.aggregate.orders;

import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.InsufficientFundsErrorEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.InvalidCurrencyErrorEvent;

import java.math.BigDecimal;

public record BuyOrderProcess(String orderId, FxMarket market, BigDecimal quantity, BigDecimal limitPrice,
                              String clientReference) implements OrderProcess {
    @Override
    public String getProcessId() {
        return orderId();
    }

    @Override
    public DomainEvent handle(InsufficientFundsErrorEvent error) {
        return new BuyOrderRejectedEvent(error.walletId(), orderId(), clientReference());
    }

    @Override
    public DomainEvent handle(InvalidCurrencyErrorEvent error) {
        return new BuyOrderRejectedEvent(error.walletId(), orderId(), clientReference());
    }
}
