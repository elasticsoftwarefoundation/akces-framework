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
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.FillSellOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.PlaceBuyOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.PlaceSellOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.commands.RejectOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.orders.events.*;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.CancelReservationCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.CreditWalletCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.DebitWalletCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.commands.ReserveAmountCommand;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.AmountReservedEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.InsufficientFundsErrorEvent;
import org.elasticsoftware.cryptotrading.aggregates.wallet.events.InvalidCryptoCurrencyErrorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(OrderProcessManager.class);

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
        log.info("EventHandler: Creating user order processes from AccountCreatedEvent for userId={}", event.userId());
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

    @EventSourcingHandler
    public OrderProcessManagerState handle(SellOrderCreatedEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            add(new SellOrderProcess(
                    event.orderId(),
                    event.market(),
                    event.amount(),
                    event.clientReference()));
        }});
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(SellOrderRejectedEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            removeIf(process -> process.orderId().equals(event.orderId()));
        }});
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(SellOrderPlacedEvent event, OrderProcessManagerState state) {
        return new OrderProcessManagerState(state.userId(), new ArrayList<>(state.runningProcesses()) {{
            replaceAll(process -> process.orderId().equals(event.orderId())
                ? process.withState(OrderProcessState.PLACED)
                : process);
        }});
    }

    @EventSourcingHandler
    public OrderProcessManagerState handle(SellOrderFilledEvent event, OrderProcessManagerState state) {
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
        log.info("CommandHandler: Placing buy order for userId={}, market={}, amount={}", 
            state.userId(), command.market().id(), command.amount());
        // we need to reserve the quote currency amount on wallet of the user
        String orderId = UUID.randomUUID().toString();
        log.info("CommandHandler: Generated orderId={} for buy order", orderId);
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

    @CommandHandler(produces = {BuyOrderRejectedEvent.class, SellOrderRejectedEvent.class}, errors = {})
    public Stream<DomainEvent> rejectOrder(RejectOrderCommand command, OrderProcessManagerState state) {
        log.info("CommandHandler: Rejecting order for userId={}, orderId={}", state.userId(), command.orderId());
        if (state.hasAkcesProcess(command.orderId())) {
            return Stream.of(state.getAkcesProcess(command.orderId()).handle(command));
        } else {
            log.info("CommandHandler: No active process found for orderId={}", command.orderId());
            return Stream.empty();
        }
    }

    @CommandHandler(produces = BuyOrderFilledEvent.class, errors = {})
    public Stream<BuyOrderFilledEvent> fillOrder(FillBuyOrderCommand command, OrderProcessManagerState state) {
        log.info("CommandHandler: Filling buy order for userId={}, orderId={}, baseCurrency={}, quoteCurrency={}, quantity={}, price={}", 
            command.userId(), command.orderId(), command.baseCurrency(), command.quoteCurrency(), command.quantity(), command.price());
        if (state.hasAkcesProcess(command.orderId())) {
            OrderProcess buyOrderProcess = state.getAkcesProcess(command.orderId());
            // cancel the reservation of the quote currency
            getCommandBus().send(new CancelReservationCommand(
                    state.userId(),
                    command.quoteCurrency(),
                    command.orderId()));
            // TODO: the amount should be in the BuyOrderFilledEvent
            log.info("CommandHandler: Debiting {} {} from user wallet", buyOrderProcess.amount(), command.quoteCurrency());
            getCommandBus().send(new DebitWalletCommand(
                    command.userId(),
                    command.quoteCurrency(),
                    buyOrderProcess.amount()));
            // credit the base currency
            log.info("CommandHandler: Crediting {} {} to user wallet", command.quantity(), command.baseCurrency());
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
        log.info("CommandHandler: No active process found for orderId={}", command.orderId());
        return Stream.empty();
    }

    /**
     * This is the entry point for the user to place a sell order
     *
     * @param command
     * @param state
     * @return
     */
    @CommandHandler(produces = SellOrderCreatedEvent.class, errors = {})
    public Stream<SellOrderCreatedEvent> placeSellOrder(PlaceSellOrderCommand command, OrderProcessManagerState state) {
        log.info("CommandHandler: Placing sell order for userId={}, market={}, amount={}", 
            state.userId(), command.market().id(), command.amount());
        // we need to reserve the base currency amount on wallet of the user
        String orderId = UUID.randomUUID().toString();
        log.info("CommandHandler: Generated orderId={} for sell order", orderId);
        // send command to reserve the amount of the base currency
        getCommandBus().send(new ReserveAmountCommand(
                state.userId(),
                command.market().baseCrypto(),
                command.amount(),
                orderId));
        // register the sell order process
        return Stream.of(new SellOrderCreatedEvent(
                state.userId(),
                orderId,
                command.market(),
                command.amount(),
                command.clientReference()));
    }

    @CommandHandler(produces = SellOrderFilledEvent.class, errors = {})
    public Stream<SellOrderFilledEvent> fillSellOrder(FillSellOrderCommand command, OrderProcessManagerState state) {
        log.info("CommandHandler: Filling sell order for userId={}, orderId={}, baseCurrency={}, quoteCurrency={}, quantity={}, price={}", 
            command.userId(), command.orderId(), command.baseCurrency(), command.quoteCurrency(), command.quantity(), command.price());
        if (state.hasAkcesProcess(command.orderId())) {
            OrderProcess sellOrderProcess = state.getAkcesProcess(command.orderId());
            // cancel the reservation of the base currency
            getCommandBus().send(new CancelReservationCommand(
                    state.userId(),
                    command.baseCurrency(),
                    command.orderId()));
            // debit the base currency
            log.info("CommandHandler: Debiting {} {} from user wallet", sellOrderProcess.amount(), command.baseCurrency());
            getCommandBus().send(new DebitWalletCommand(
                    command.userId(),
                    command.baseCurrency(),
                    sellOrderProcess.amount()));
            // credit the quote currency (user receives quote currency for selling base currency)
            BigDecimal quoteAmount = command.quantity().multiply(command.price());
            log.info("CommandHandler: Crediting {} {} to user wallet", quoteAmount, command.quoteCurrency());
            getCommandBus().send(new CreditWalletCommand(
                    command.userId(),
                    command.quoteCurrency(),
                    quoteAmount));
            // TODO: we also need to update the counterparty wallet
            return Stream.of(new SellOrderFilledEvent(
                    command.userId(),
                    command.orderId(),
                    command.counterpartyId(),
                    command.price(),
                    command.quantity(),
                    command.baseCurrency(),
                    command.quoteCurrency()
            ));
        }
        log.info("CommandHandler: No active process found for orderId={}", command.orderId());
        return Stream.empty();
    }

    @EventHandler(produces = {BuyOrderPlacedEvent.class, SellOrderPlacedEvent.class}, errors = {})
    public Stream<DomainEvent> handle(AmountReservedEvent event, OrderProcessManagerState state) {
        log.info("EventHandler: Amount reserved for userId={}, currency={}, amount={}, referenceId={}", 
            event.userId(), event.currency(), event.amount(), event.referenceId());
        // happy path, need to send a command to the market to place the order
        OrderProcess orderProcess = state.getAkcesProcess(event.referenceId());
        if (orderProcess != null) {
            log.info("EventHandler: Placing market order for orderId={}, marketId={}", 
                orderProcess.orderId(), orderProcess.market().id());
            Side side = orderProcess instanceof BuyOrderProcess ? Side.BUY : Side.SELL;
            getCommandBus().send(new PlaceMarketOrderCommand(
                    orderProcess.market().id(),
                    orderProcess.orderId(),
                    state.userId(),
                    side,
                    orderProcess.amount(),
                    null));
            if (orderProcess instanceof BuyOrderProcess) {
                return Stream.of(new BuyOrderPlacedEvent(state.userId(), orderProcess.orderId(), orderProcess.market(), orderProcess.amount(), null));
            } else {
                return Stream.of(new SellOrderPlacedEvent(state.userId(), orderProcess.orderId(), orderProcess.market(), orderProcess.amount(), null));
            }
        } else {
            log.info("EventHandler: No order process found for referenceId={}", event.referenceId());
            // TODO: this cannot happen
            return Stream.empty();
        }
    }

    @EventHandler(produces = {BuyOrderRejectedEvent.class, SellOrderRejectedEvent.class}, errors = {})
    public Stream<DomainEvent> handle(InsufficientFundsErrorEvent errorEvent, OrderProcessManagerState state) {
        log.info("EventHandler: Insufficient funds error for userId={}, currency={}, available={}, requested={}, referenceId={}", 
            state.userId(), errorEvent.currency(), errorEvent.availableAmount(), errorEvent.requestedAmount(), errorEvent.referenceId());
        if (state.hasAkcesProcess(errorEvent.referenceId())) {
            return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
        } else {
            log.info("EventHandler: No active process found for referenceId={}", errorEvent.referenceId());
            return Stream.empty();
        }
    }

    @EventHandler(produces = {BuyOrderRejectedEvent.class, SellOrderRejectedEvent.class}, errors = {})
    public Stream<DomainEvent> handle(InvalidCryptoCurrencyErrorEvent errorEvent, OrderProcessManagerState state) {
        log.info("EventHandler: Invalid crypto currency error for userId={}, cryptoCurrency={}, referenceId={}", 
            state.userId(), errorEvent.cryptoCurrency(), errorEvent.referenceId());
        if (state.hasAkcesProcess(errorEvent.referenceId())) {
            return Stream.of(state.getAkcesProcess(errorEvent.referenceId()).handle(errorEvent));
        } else {
            log.info("EventHandler: No active process found for referenceId={}", errorEvent.referenceId());
            return Stream.empty();
        }
    }

    @EventBridgeHandler
    public void handle(MarketOrderRejectedErrorEvent errorEvent, CommandBus commandBus) {
        log.info("EventBridgeHandler: Market order rejected for marketId={}, orderId={}, ownerId={}, reason={}", 
            errorEvent.marketId(), errorEvent.orderId(), errorEvent.ownerId(), errorEvent.rejectionReason());
        // the aggregateId is different in this case (it's the CryptoMarketId) so we need to send a command to self
        commandBus.send(new RejectOrderCommand(errorEvent.ownerId(), errorEvent.orderId()));
    }

    @EventBridgeHandler
    public void handle(MarketOrderFilledEvent event, CommandBus commandBus) {
        log.info("EventBridgeHandler: Market order filled for marketId={}, orderId={}, ownerId={}, side={}, price={}, quantity={}", 
            event.marketId(), event.orderId(), event.ownerId(), event.side(), event.price(), event.quantity());
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
                commandBus.send(new FillSellOrderCommand(
                        event.ownerId(),
                        event.orderId(),
                        event.counterpartyId(),
                        event.price(),
                        event.quantity(),
                        event.baseCurrency(),
                        event.quoteCurrency()
                ));
                break;
        }
    }
}
