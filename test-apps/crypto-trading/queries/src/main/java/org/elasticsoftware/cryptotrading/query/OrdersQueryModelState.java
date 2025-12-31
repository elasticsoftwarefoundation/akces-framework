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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.elasticsoftware.akces.annotations.QueryModelStateInfo;
import org.elasticsoftware.akces.query.QueryModelState;
import org.elasticsoftware.akces.serialization.BigDecimalSerializer;
import org.elasticsoftware.cryptotrading.aggregates.orders.data.CryptoMarket;

import java.math.BigDecimal;
import java.util.List;

@QueryModelStateInfo(type = "Orders")
public record OrdersQueryModelState(String userId, List<BuyOrder> openBuyOrders, List<SellOrder> openSellOrders) implements QueryModelState {
    @Override
    public String getIndexKey() {
        return userId();
    }

    public record BuyOrder(String orderId, CryptoMarket market, @JsonSerialize(using = BigDecimalSerializer.class) BigDecimal amount, String clientReference, OrderState state) {
    }

    public record SellOrder(String orderId, CryptoMarket market, @JsonSerialize(using = BigDecimalSerializer.class) BigDecimal quantity, String clientReference, OrderState state) {
    }
}
