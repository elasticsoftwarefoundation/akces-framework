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
import org.elasticsoftware.akces.annotations.*;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.cryptotrading.aggregates.account.events.AccountCreatedEvent;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.commands.PlaceMarketOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.data.Side;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.events.MarketOrderFilledEvent;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.events.MarketOrderRejectedErrorEvent;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.FillBuyOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.PlaceBuyOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.RejectOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.*;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.CancelReservationCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.CreditWalletCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.DebitWalletCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.ReserveAmountCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.AmountReservedEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.InsufficientFundsErrorEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.InvalidCryptoCurrencyErrorEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;

@AggregateInfo(
        value = "OrderProcessManager",
        stateClass = OrderProcessManagerState.class,
        indexed = true,
        indexName = "Users")
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
        }});
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(BuyOrderRejectedEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            removeIf(process -> process.orderId().equals(event.orderId()));
        }});
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(BuyOrderPlacedEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            replaceAll(process -> process.orderId().equals(event.orderId())
                ? process.withState(OrderProcessState.PLACED)
                : process);
        }});
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(BuyOrderFilledEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            removeIf(process -> process.orderId().equals(event.orderId()));
        }});
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
        if (state.hasAkcesProcess(command.orderId())) {
            return Stream.of(state.getAkcesProcess(command.orderId()).handle(command));
        } else {
            return Stream.empty();
        }
    }

    @CommandHandler(produces = BuyOrderFilledEvent.class, errors = {})
    public Stream<BuyOrderFilledEvent> fillOrder(FillBuyOrderCommand command, OrderProcessManagerState state) {
        if (state.hasAkcesProcess(command.orderId())) {
            // cancel the reservation of the quote currency
            getCommandBus().send(new CancelReservationCommand(
                    state.userId(),
                    command.quoteCurrency(),
                    command.orderId()));
            // debit the quote currency
            BigDecimal quoteAmount = command.price().multiply(command.quantity());
            getCommandBus().send(new DebitWalletCommand(
                    command.userId(),
                    command.quoteCurrency(),
                    quoteAmount));
            // credit the base currency
            getCommandBus().send(new CreditWalletCommand(
                    command.userId(),
                    command.baseCurrency(),
                    command.quantity()));
            // TODO: we also need to update the counterparty wallet
            return Stream.of(new BuyOrderFilledEvent(
                    command.userId(),
                    command.orderId(),
                    command.counterpartyId(),
                    command.price(),
                    command.quantity(),
                    command.baseCurrency(),
                    command.quoteCurrency()
            ));
        }
        return Stream.empty();
    }

    @EventHandler(produces = BuyOrderPlacedEvent.class, errors = {})
    public Stream<DomainEvent> handle(AmountReservedEvent event, OrderProcessManagerState state) {
        // happy path, need to send a command to the market to place the order
        OrderProcess orderProcess = state.getAkcesProcess(event.referenceId());
        if (orderProcess != null) {
            getCommandBus().send(new PlaceMarketOrderCommand(
                    orderProcess.market().id(),
                    orderProcess.orderId(),
                    state.userId(),
                    Side.BUY,
                    orderProcess.amount(),
                    null));
            return Stream.of(new BuyOrderPlacedEvent(state.userId(), orderProcess.orderId(), orderProcess.market(), orderProcess.amount(), null));
        } else {
            // TODO: this cannot happen
            return Stream.empty();
        }
    }

    @EventHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<DomainEvent> handle(InsufficientFundsErrorEvent errorEvent, OrderProcessManagerState state) {
        if (state.hasAkcesProcess(errorEvent.referenceId())) {
            return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
        } else {
            return Stream.empty();
        }
    }

    @EventHandler(produces = BuyOrderRejectedEvent.class, errors = {})
    public Stream<DomainEvent> handle(InvalidCryptoCurrencyErrorEvent errorEvent, OrderProcessManagerState state) {
        if (state.hasAkcesProcess(errorEvent.referenceId())) {
            return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
        } else {
            return Stream.empty();
        }
    }

    @EventBridgeHandler
    public void handle(MarketOrderRejectedErrorEvent errorEvent, CommandBus commandBus) {
        // the aggregateId is different in this case (it's the CryptoMarketId) so we need to send a command to self
        commandBus.send(new RejectOrderCommand(errorEvent.ownerId(), errorEvent.orderId()));
    }

    @EventBridgeHandler
    public void handle(MarketOrderFilledEvent event, CommandBus commandBus) {
        switch (event.side()) {
            case BUY:
                commandBus.send(new FillBuyOrderCommand(
                        event.ownerId(),
                        event.orderId(),
                        event.counterpartyId(),
                        event.price(),
                        event.quantity(),
                        event.baseCurrency(),
                        event.quoteCurrency()
                ));
                break;
            case SELL:
        }
    }
}
