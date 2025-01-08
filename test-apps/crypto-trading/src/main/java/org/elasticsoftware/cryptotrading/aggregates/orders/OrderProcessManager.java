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

package org.elasticsoftware.cryptotrading.aggregates.orders;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.cryptotrading.aggregates.account.AccountCreatedEvent;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.Side;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.commands.PlaceMarketOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.events.MarketOrderFilledEvent;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.events.MarketOrderRejectedErrorEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.PlaceBuyOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.RejectOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.BuyOrderCreatedEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.BuyOrderPlacedEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.BuyOrderRejectedEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.UserOrderProcessesCreatedEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.ReserveAmountCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.AmountReservedEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.InsufficientFundsErrorEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.InvalidCryptoCurrencyErrorEvent;

import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;

@AggregateInfo(value = "OrderProcessManager", indexed = true, indexName = "Users")
public class OrderProcessManager implements Aggregate<OrderProcessManagerState> {
    @Override
    public String getName() {
        return "OrderProcessManager";
    }

    @Override
    public Class<OrderProcessManagerState> getStateClass() {
        return OrderProcessManagerState.class;
    }

    @EventHandler(create = true, produces = UserOrderProcessesCreatedEvent.class, errors = {})
    public Stream<UserOrderProcessesCreatedEvent> create(AccountCreatedEvent event, OrderProcessManagerState isNull) {
        return Stream.of(new UserOrderProcessesCreatedEvent(event.userId()));
    }

    @EventSourcingHandler(create = true)
    public OrderProcessManagerState create(UserOrderProcessesCreatedEvent event, OrderProcessManagerState isNull) {
        return new OrderProcessManagerState(event.userId());
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(BuyOrderCreatedEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            add(new BuyOrderProcess(
                    event.orderId(),
                    event.market(),
                    event.amount(),
                    event.clientReference()));
        }} );
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(BuyOrderRejectedEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            removeIf(process -> process.orderId().equals(event.orderId()));
        }} );
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(BuyOrderPlacedEvent event, OrderProcessManagerState state) {
        // TODO: we should not remove it but mark it as placed
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            removeIf(process -> process.orderId().equals(event.orderId()));
        }} );
    }

    /**
     * This is the entry point for the user to place a buy order
     *
     * @param command
     * @param state
     * @return
     */
    @CommandHandler(produces = BuyOrderCreatedEvent.class, errors = {})
    public Stream<BuyOrderCreatedEvent> placeBuyOrder(PlaceBuyOrderCommand command, OrderProcessManagerState state) {
        // we need to reserve the quote currency amount on wallet of the user
        String orderId = UUID.randomUUID().toString();
        // send command to reserve the amount of the quote currency
        getCommandBus().send(new ReserveAmountCommand(
                state.userId(),
                command.market().quoteCrypto(),
                command.amount(),
                orderId));
        // register the buy order process
        return Stream.of(new BuyOrderCreatedEvent(
                state.userId(),
                orderId,
                command.market(),
                command.amount(),
                command.clientReference()));
    }

    @CommandHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<BuyOrderRejectedEvent> rejectOrder(RejectOrderCommand command, OrderProcessManagerState state) {
        if(state.hasAkcesProcess(command.orderId())) {
            return Stream.of(state.getAkcesProcess(command.orderId()).handle(command));
        } else {
            return Stream.empty();
        }
    }

    @EventHandler(produces = BuyOrderPlacedEvent.class, errors = {})
    public Stream<DomainEvent> handle(AmountReservedEvent event, OrderProcessManagerState state) {
        // happy path, need to send a command to the market to place the order
        OrderProcess orderProcess = state.getAkcesProcess(event.referenceId());
        if(orderProcess != null) {
            getCommandBus().send(new PlaceMarketOrderCommand(
                    orderProcess.market().id(),
                    orderProcess.orderId(),
                    state.userId(),
                    Side.BUY,
                    orderProcess.amount(),
                    orderProcess.size()));
            return Stream.of(new BuyOrderPlacedEvent(state.userId(), orderProcess.orderId(), orderProcess.market(), orderProcess.size(), orderProcess.amount()));
        } else {
            // TODO: this cannot happen
            return Stream.empty();
        }
    }

    @EventHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<DomainEvent> handleError(InsufficientFundsErrorEvent errorEvent, OrderProcessManagerState state) {
        if(state.hasAkcesProcess(errorEvent.referenceId())) {
            return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
        } else {
            return Stream.empty();
        }
    }

    @EventHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<DomainEvent> handleError(InvalidCryptoCurrencyErrorEvent errorEvent, OrderProcessManagerState state) {
        if(state.hasAkcesProcess(errorEvent.referenceId())) {
            return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
        } else {
            return Stream.empty();
        }
    }

    @EventHandler(produces = {}, errors = {})
    public Stream<DomainEvent> handle(MarketOrderFilledEvent event, OrderProcessManagerState state) {
//        OrderProcess orderProcess = state.getAkcesProcess(event.orderId());
//        switch (orderProcess instanceof BuyOrderProcess buyOrderProcess) {
//            // we need to send a command to the wallet to release the reserved amount
//            // we need to cancel the reservation and debit the quote currency
//            // we need to credit the base currency
//        }
        // TODO: implement
        return Stream.empty();
    }

    @EventHandler(produces = {}, errors = {})
    public Stream<DomainEvent> handle(MarketOrderRejectedErrorEvent errorEvent, OrderProcessManagerState state) {
        // the aggregateId is different in this case (it's the CryptoMarketId) so we need to send a command to self
        getCommandBus().send(new RejectOrderCommand(state.userId(), errorEvent.orderId()));
        return Stream.empty();
    }
}
