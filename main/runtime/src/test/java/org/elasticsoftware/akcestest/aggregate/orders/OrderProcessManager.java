/*
 * Copyright 2022 - 2023 The Original Authors
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

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.AmountReservedEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.InsufficientFundsErrorEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.InvalidCurrencyErrorEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.ReserveAmountCommand;

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
                    event.quantity(),
                    event.limitPrice(),
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
                command.market().quoteCurrency(),
                command.quantity().multiply(command.limitPrice()),
                orderId));
        // register the buy order process
        return Stream.of(new BuyOrderCreatedEvent(
                state.userId(),
                orderId,
                command.market(),
                command.quantity(),
                command.limitPrice(),
                command.clientReference()));
    }



    @EventHandler(produces = BuyOrderPlacedEvent.class, errors = {})
    public Stream<DomainEvent> handle(AmountReservedEvent event, OrderProcessManagerState state) {
        // happy path, need to send a command to the market to place the order
        OrderProcess orderProcess = state.getAkcesProcess(event.referenceId());
        if(orderProcess != null) {
            // TODO: send order to ForexMarket
            return Stream.of(new BuyOrderPlacedEvent(state.userId(), orderProcess.orderId(), orderProcess.market(), orderProcess.quantity(), orderProcess.limitPrice()));
        } else {
            // TODO: this cannot happen
            return Stream.empty();
        }
    }

    @EventHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<DomainEvent> handle(InsufficientFundsErrorEvent errorEvent, OrderProcessManagerState state) {
        return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
    }

    @EventHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<DomainEvent> handle(InvalidCurrencyErrorEvent errorEvent, OrderProcessManagerState state) {
        return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
    }
}
