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

package org.elasticsoftware.cryptotrading.aggregates.cryptomarket;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.commands.CreateCryptoMarketCommand;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.commands.PlaceMarketOrderCommand;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.data.Side;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.events.CryptoMarketCreatedEvent;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.events.MarketOrderFilledEvent;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.events.MarketOrderPlacedEvent;
import org.elasticsoftware.cryptotrading.aggregates.cryptomarket.events.MarketOrderRejectedErrorEvent;
import org.elasticsoftware.cryptotrading.services.coinbase.CoinbaseService;
import org.elasticsoftware.cryptotrading.services.coinbase.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.stream.Stream;

@AggregateInfo(value = "CryptoMarket", stateClass = CryptoMarketState.class)
public class CryptoMarket implements Aggregate<CryptoMarketState> {
    private static final Logger log = LoggerFactory.getLogger(CryptoMarket.class);
    private final CoinbaseService coinbaseService;
    private final MathContext mathContext = new MathContext(8);

    public CryptoMarket(CoinbaseService coinbaseService) {
        this.coinbaseService = coinbaseService;
    }

    @Override
    public String getName() {
        return "CryptoMarket";
    }

    @Override
    public Class<CryptoMarketState> getStateClass() {
        return CryptoMarketState.class;
    }

    @CommandHandler(create = true, produces = CryptoMarketCreatedEvent.class, errors = {})
    public @NotNull Stream<DomainEvent> handle(@NotNull CreateCryptoMarketCommand command, CryptoMarketState isNull) {
        log.info("CommandHandler: Creating crypto market for id={}, baseCurrency={}, quoteCurrency={}", 
            command.id(), command.baseCurrency(), command.quoteCurrency());
        return Stream.of(new CryptoMarketCreatedEvent(command.id(),
                command.baseCurrency(),
                command.quoteCurrency(),
                command.baseIncrement(),
                command.quoteIncrement(),
                command.defaultCounterPartyId()));
    }

    @CommandHandler(produces = {MarketOrderPlacedEvent.class, MarketOrderFilledEvent.class}, errors = {MarketOrderRejectedErrorEvent.class})
    public @NotNull Stream<DomainEvent> handle(@NotNull PlaceMarketOrderCommand command, CryptoMarketState currentState) {
        log.info("CommandHandler: Placing market order for marketId={}, orderId={}, ownerId={}, side={}, funds={}, size={}", 
            command.marketId(), command.orderId(), command.ownerId(), command.side(), command.funds(), command.size());
        if (command.side().equals(Side.BUY) && command.funds() == null) {
            log.info("CommandHandler: Market order rejected - funds required for BUY order. marketId={}, orderId={}", 
                command.marketId(), command.orderId());
            return Stream.of(new MarketOrderRejectedErrorEvent(command.marketId(),
                    command.orderId(),
                    command.ownerId(),
                    "Funds are required for a BUY order"));
        } else if (command.side().equals(Side.SELL) && command.size() == null) {
            log.info("CommandHandler: Market order rejected - size required for SELL order. marketId={}, orderId={}", 
                command.marketId(), command.orderId());
            return Stream.of(new MarketOrderRejectedErrorEvent(command.marketId(),
                    command.orderId(),
                    command.ownerId(),
                    "Size is required for a SELL order"));
        } else {
            // execute the order immediately
            Ticker currentTicker = coinbaseService.getTicker(currentState.id());
            // An individual looking to sell will receive the bid price while one looking to buy will pay the ask price
            BigDecimal price = command.side().equals(Side.BUY) ? new BigDecimal(currentTicker.ask()) : new BigDecimal(currentTicker.bid());
            // calculate the quantity based on the funds or size
            BigDecimal quantity = command.side().equals(Side.BUY) ? command.funds().divide(price, mathContext) : command.size();
            log.info("CommandHandler: Market order filled for marketId={}, orderId={}, price={}, quantity={}", 
                command.marketId(), command.orderId(), price, quantity);
            MarketOrderPlacedEvent marketOrderPlacedEvent = new MarketOrderPlacedEvent(command.marketId(),
                    command.orderId(),
                    command.ownerId(),
                    command.side(),
                    command.funds(),
                    command.size());
            MarketOrderFilledEvent marketOrderFilledEvent = new MarketOrderFilledEvent(command.marketId(),
                    command.orderId(),
                    command.ownerId(),
                    currentState.defaultCounterPartyId(),
                    command.side(),
                    currentState.baseCrypto(),
                    currentState.quoteCrypto(),
                    price,
                    quantity);
            return Stream.of(marketOrderPlacedEvent, marketOrderFilledEvent);
        }
    }

    @EventSourcingHandler(create = true)
    public @NotNull CryptoMarketState apply(@NotNull CryptoMarketCreatedEvent event, CryptoMarketState isNull) {
        return new CryptoMarketState(event.id(),
                event.baseCrypto(),
                event.quoteCrypto(),
                event.baseIncrement(),
                event.quoteIncrement(),
                event.defaultCounterPartyId());
    }

    @EventSourcingHandler
    public @NotNull CryptoMarketState apply(@NotNull MarketOrderPlacedEvent event, CryptoMarketState currentState) {
        // since we immediately execute the order, we don't need to do anything here
        return currentState;
    }

    @EventSourcingHandler
    public @NotNull CryptoMarketState apply(@NotNull MarketOrderFilledEvent event, CryptoMarketState currentState) {
        // since we immediately execute the order, we don't need to do anything here
        return currentState;
    }
}
