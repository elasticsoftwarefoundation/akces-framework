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

import org.elasticsoftware.akces.annotations.QueryModelEventHandler;
import org.elasticsoftware.akces.annotations.QueryModelInfo;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.BuyOrderCreatedEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.BuyOrderFilledEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.BuyOrderPlacedEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.BuyOrderRejectedEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.UserOrderProcessesCreatedEvent;

import java.util.ArrayList;
import java.util.List;

@QueryModelInfo(value = "OrdersQueryModel", version = 1, indexName = "Users")
public class OrdersQueryModel implements QueryModel<OrdersQueryModelState> {
    @Override
    public String getName() {
        return "OrdersQueryModel";
    }

    @Override
    public Class<OrdersQueryModelState> getStateClass() {
        return OrdersQueryModelState.class;
    }

    @Override
    public String getIndexName() {
        return "Users";
    }

    @QueryModelEventHandler(create = true)
    public OrdersQueryModelState create(UserOrderProcessesCreatedEvent event, OrdersQueryModelState isNull) {
        return new OrdersQueryModelState(event.userId(), List.of());
    }

    @QueryModelEventHandler(create = false)
    public OrdersQueryModelState addOrder(BuyOrderCreatedEvent event, OrdersQueryModelState currentState) {
        OrdersQueryModelState.BuyOrder buyOrder = new OrdersQueryModelState.BuyOrder(
                event.orderId(),
                event.market(),
                event.amount(),
                event.clientReference()
        );
        List<OrdersQueryModelState.BuyOrder> orders = new ArrayList<>(currentState.openBuyOrders());
        orders.add(buyOrder);
        return new OrdersQueryModelState(currentState.userId(), orders);
    }

    @QueryModelEventHandler(create = false)
    public OrdersQueryModelState orderPlaced(BuyOrderPlacedEvent event, OrdersQueryModelState currentState) {
        // Order is still open, state remains the same
        return currentState;
    }

    @QueryModelEventHandler(create = false)
    public OrdersQueryModelState orderFilled(BuyOrderFilledEvent event, OrdersQueryModelState currentState) {
        // Remove the filled order from open orders
        List<OrdersQueryModelState.BuyOrder> orders = currentState.openBuyOrders().stream()
                .filter(order -> !order.orderId().equals(event.orderId()))
                .toList();
        return new OrdersQueryModelState(currentState.userId(), orders);
    }

    @QueryModelEventHandler(create = false)
    public OrdersQueryModelState orderRejected(BuyOrderRejectedEvent event, OrdersQueryModelState currentState) {
        // Remove the rejected order from open orders
        List<OrdersQueryModelState.BuyOrder> orders = currentState.openBuyOrders().stream()
                .filter(order -> !order.orderId().equals(event.orderId()))
                .toList();
        return new OrdersQueryModelState(currentState.userId(), orders);
    }
}
