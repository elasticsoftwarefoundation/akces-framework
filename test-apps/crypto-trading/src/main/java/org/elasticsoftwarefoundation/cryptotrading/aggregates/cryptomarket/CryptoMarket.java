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

package org.elasticsoftwarefoundation.cryptotrading.aggregates.cryptomarket;

import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.cryptomarket.commands.CreateCryptoMarketCommand;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.cryptomarket.events.CryptoMarketCreatedEvent;
import jakarta.validation.constraints.NotNull;

import java.util.stream.Stream;

@AggregateInfo(value = "CryptoMarket")
public class CryptoMarket implements Aggregate<CryptoMarketState> {

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
        return Stream.of(new CryptoMarketCreatedEvent(command.id(), command.baseCrypto(), command.quoteCrypto()));
    }

    @EventSourcingHandler(create = true)
    public @NotNull CryptoMarketState apply(@NotNull CryptoMarketCreatedEvent event, CryptoMarketState isNull) {
        return new CryptoMarketState(event.id(), event.baseCrypto(), event.quoteCrypto());
    }
}
