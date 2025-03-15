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

package org.elasticsoftware.akcestest.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.*;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;


@AggregateInfo(value = "Wallet", stateVersion = 2, indexed = true, indexName = "Users")
@SuppressWarnings("unused")
public final class Wallet implements Aggregate<WalletStateV2> {
    @Override
    public String getName() {
        return "Wallet";
    }

    @Override
    public Class<WalletStateV2> getStateClass() {
        return WalletStateV2.class;
    }

    @UpcastingHandler
    public WalletStateV2 upcast(WalletState state) {
        // if there is any reservedAmount, we need to create a reservation for it
        return new WalletStateV2(state.id(), state.balances().stream().map(
                balance -> balance.reservedAmount().compareTo(BigDecimal.ZERO) > 0 ?
                        new WalletStateV2.Balance(balance.currency(), balance.amount(), List.of(new WalletStateV2.Reservation("v1-reservedAmount", balance.reservedAmount()))) :
                        new WalletStateV2.Balance(balance.currency(), balance.amount())).toList());
    }

    @CommandHandler(create = true, produces = WalletCreatedEvent.class, errors = {})
    public @NotNull Stream<DomainEvent> create(@NotNull CreateWalletCommand cmd, WalletStateV2 isNull) {
        return Stream.of(new WalletCreatedEvent(cmd.id()), new BalanceCreatedEvent(cmd.id(), cmd.currency()));
    }

    @EventHandler(create = true, produces = WalletCreatedEvent.class, errors = {})
    public @NotNull Stream<DomainEvent> create(@NotNull AccountCreatedEvent event, WalletStateV2 isNull) {
        // TODO: base the currency on the country
        return Stream.of(new WalletCreatedEvent(event.getAggregateId()), new BalanceCreatedEvent(event.getAggregateId(), "EUR"));
    }

    @CommandHandler(produces = BalanceCreatedEvent.class, errors = {BalanceAlreadyExistsErrorEvent.class})
    public @NotNull Stream<DomainEvent> createBalance(@NotNull CreateBalanceCommand cmd, @NotNull WalletStateV2 currentState) {
        boolean balanceExists = currentState.balances().stream()
                .anyMatch(balance -> balance.currency().equals(cmd.currency()));
        if (balanceExists) {
            return Stream.of(new BalanceAlreadyExistsErrorEvent(cmd.id(), cmd.currency()));
        }
        return Stream.of(new BalanceCreatedEvent(cmd.id(), cmd.currency()));
    }

    @CommandHandler(produces = WalletCreditedEvent.class, errors = {InvalidCurrencyErrorEvent.class, InvalidAmountErrorEvent.class})
    @NotNull
    public Stream<DomainEvent> credit(@NotNull CreditWalletCommand cmd, @NotNull WalletStateV2 currentState) {
        WalletStateV2.Balance balance = currentState.balances().stream().filter(b -> b.currency().equals(cmd.currency())).findFirst().orElse(null);
        if (balance == null) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidCurrencyErrorEvent(cmd.id(), cmd.currency()));
        }
        if (cmd.amount().compareTo(BigDecimal.ZERO) < 0) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidAmountErrorEvent(cmd.id(), cmd.currency()));
        }
        return Stream.of(new WalletCreditedEvent(currentState.id(), cmd.currency(), cmd.amount(), balance.amount().add(cmd.amount())));
    }

    @CommandHandler(produces = AmountReservedEvent.class, errors = {InvalidCurrencyErrorEvent.class, InvalidAmountErrorEvent.class, InsufficientFundsErrorEvent.class})
    public Stream<DomainEvent> makeReservation(ReserveAmountCommand command, WalletStateV2 state) {
        WalletStateV2.Balance balance = state.balances().stream().filter(b -> b.currency().equals(command.currency())).findFirst().orElse(null);
        if (balance == null) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidCurrencyErrorEvent(command.userId(), command.currency(), command.referenceId()));
        }
        if (command.amount().compareTo(BigDecimal.ZERO) < 0) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidAmountErrorEvent(command.userId(), command.currency()));
        }
        // see if we have enough balance
        if (balance.getAvailableAmount().compareTo(command.amount()) >= 0) {
            return Stream.of(new AmountReservedEvent(command.userId(), command.currency(), command.amount(), command.referenceId()));
        } else {
            return Stream.of(new InsufficientFundsErrorEvent(command.userId(), command.currency(), balance.getAvailableAmount(), command.amount(), command.referenceId()));
        }
    }

    @EventSourcingHandler(create = true)
    public @NotNull WalletStateV2 create(@NotNull WalletCreatedEvent event, WalletStateV2 isNull) {
        return new WalletStateV2(event.id(), new ArrayList<>());
    }

    @EventSourcingHandler
    public @NotNull WalletStateV2 createBalance(@NotNull BalanceCreatedEvent event, WalletStateV2 state) {
        List<WalletStateV2.Balance> balances = new ArrayList<>(state.balances());
        balances.add(new WalletStateV2.Balance(event.currency(), BigDecimal.ZERO));
        return new WalletStateV2(state.id(), balances);
    }

    @EventSourcingHandler
    public @NotNull WalletStateV2 credit(@NotNull WalletCreditedEvent event, @NotNull WalletStateV2 state) {
        return new WalletStateV2(state.id(), state.balances().stream().map(b -> {
            if (b.currency().equals(event.currency())) {
                return new WalletStateV2.Balance(b.currency(), b.amount().add(event.amount()));
            } else {
                return b;
            }
        }).toList());
    }

    @EventSourcingHandler
    public @NotNull WalletStateV2 reserveAmount(@NotNull AmountReservedEvent event, @NotNull WalletStateV2 state) {
        return new WalletStateV2(state.id(), state.balances().stream().map(b -> {
            if (b.currency().equals(event.currency())) {
                List<WalletStateV2.Reservation> updatedReservations = new ArrayList<>(b.reservations());
                updatedReservations.add(new WalletStateV2.Reservation(event.referenceId(), event.amount()));
                return new WalletStateV2.Balance(b.currency(), b.amount(), updatedReservations);
            } else {
                return b;
            }
        }).toList());
    }

}
