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

package org.elasticsoftwarefoundation.cryptotrading.aggregates.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.account.AccountCreatedEvent;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.wallet.commands.CreateBalanceCommand;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.wallet.commands.CreateWalletCommand;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.wallet.commands.CreditWalletCommand;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.wallet.commands.ReserveAmountCommand;
import org.elasticsoftwarefoundation.cryptotrading.aggregates.wallet.events.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;


@AggregateInfo(value = "Wallet", version = 1, indexed = true, indexName = "Users")
@SuppressWarnings("unused")
public final class Wallet implements Aggregate<WalletState> {
    @Override
    public String getName() {
        return "Wallet";
    }

    @Override
    public Class<WalletState> getStateClass() {
        return WalletState.class;
    }

    @CommandHandler(create = true, produces = WalletCreatedEvent.class, errors = {})
    public @NotNull Stream<DomainEvent> create(@NotNull CreateWalletCommand cmd, WalletState isNull) {
        return Stream.of(new WalletCreatedEvent(cmd.id()), new BalanceCreatedEvent(cmd.id(), cmd.currency()));
    }

    @EventHandler(create = true, produces = WalletCreatedEvent.class, errors = {})
    public @NotNull Stream<DomainEvent> create(@NotNull AccountCreatedEvent event, WalletState isNull) {
        return Stream.of(new WalletCreatedEvent(event.getAggregateId()), new BalanceCreatedEvent(event.getAggregateId(), "BTC"));
    }

    @CommandHandler(produces = WalletCreditedEvent.class, errors = {InvalidCryptoCurrencyErrorEvent.class, InvalidAmountErrorEvent.class})
    @NotNull
    public Stream<DomainEvent> credit(@NotNull CreditWalletCommand cmd, @NotNull WalletState currentState) {
        WalletState.Balance balance = currentState.balances().stream().filter(b -> b.currency().equals(cmd.currency())).findFirst().orElse(null);
        if (balance == null ) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidCryptoCurrencyErrorEvent(cmd.id(), cmd.currency()));
        }
        if (cmd.amount().compareTo(BigDecimal.ZERO) < 0) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidAmountErrorEvent(cmd.id(), cmd.currency()));
        }
        return Stream.of(new WalletCreditedEvent(currentState.id(), cmd.currency(), cmd.amount(), balance.amount().add(cmd.amount())));
    }

    @CommandHandler(produces = AmountReservedEvent.class, errors = {InvalidCryptoCurrencyErrorEvent.class, InvalidAmountErrorEvent.class, InsufficientFundsErrorEvent.class})
    public Stream<DomainEvent> makeReservation(ReserveAmountCommand command, WalletState state) {
        WalletState.Balance balance = state.balances().stream().filter(b -> b.currency().equals(command.currency())).findFirst().orElse(null);
        if (balance == null ) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidCryptoCurrencyErrorEvent(command.userId(), command.currency(), command.referenceId()));
        }
        if (command.amount().compareTo(BigDecimal.ZERO) < 0) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidAmountErrorEvent(command.userId(), command.currency()));
        }
        // see if we have enough balance
        if(balance.getAvailableAmount().compareTo(command.amount()) >= 0) {
            return Stream.of(new AmountReservedEvent(command.userId(), command.currency(), command.amount(), command.referenceId()));
        } else {
            return Stream.of(new InsufficientFundsErrorEvent(command.userId(), command.currency(),balance.getAvailableAmount(), command.amount(), command.referenceId()));
        }
    }

    @EventSourcingHandler(create = true)
    public @NotNull WalletState create(@NotNull WalletCreatedEvent event, WalletState isNull) {
        return new WalletState(event.id(), new ArrayList<>());
    }

    @EventSourcingHandler
    public @NotNull WalletState createBalance(@NotNull BalanceCreatedEvent event, WalletState state) {
        List<WalletState.Balance> balances = new ArrayList<>(state.balances());
        balances.add(new WalletState.Balance(event.currency(), BigDecimal.ZERO));
        return new WalletState(state.id(), balances);
    }

    @EventSourcingHandler
    public @NotNull WalletState credit(@NotNull WalletCreditedEvent event, @NotNull WalletState state) {
        return new WalletState(state.id(), state.balances().stream().map(b -> {
            if (b.currency().equals(event.currency())) {
                return new WalletState.Balance(b.currency(), b.amount().add(event.amount()));
            } else {
                return b;
            }
        }).toList());
    }

    @EventSourcingHandler
    public @NotNull WalletState reserveAmount(@NotNull AmountReservedEvent event, @NotNull WalletState state) {
        return new WalletState(state.id(), state.balances().stream().map(b -> {
            if (b.currency().equals(event.currency())) {
                return new WalletState.Balance(b.currency(), b.amount(), b.reservedAmount().add(event.amount()));
            } else {
                return b;
            }
        }).toList());
    }

    @CommandHandler(produces = BalanceCreatedEvent.class, errors = {BalanceAlreadyExistsErrorEvent.class})
public @NotNull Stream<DomainEvent> createBalance(@NotNull CreateBalanceCommand cmd, @NotNull WalletState currentState) {
    boolean balanceExists = currentState.balances().stream()
            .anyMatch(balance -> balance.currency().equals(cmd.currency()));
    if (balanceExists) {
        return Stream.of(new BalanceAlreadyExistsErrorEvent(cmd.id(), cmd.currency()));
    }
    return Stream.of(new BalanceCreatedEvent(cmd.id(), cmd.currency()));
}

}
