package org.elasticsoftware.akces.aggregate.wallet;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.aggregate.account.AccountCreatedEvent;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;


@AggregateInfo("Wallet")
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

    @EventHandler(create = true, produces = WalletCreatedEvent.class)
    public @NotNull Stream<DomainEvent> create(@NotNull AccountCreatedEvent event, WalletState isNull) {
        // TODO: base the currency on the country
        return Stream.of(new WalletCreatedEvent(event.getAggregateId()), new BalanceCreatedEvent(event.getAggregateId(), "EUR"));
    }

    @CommandHandler(produces = WalletCreditedEvent.class, errors = {InvalidCurrencyErrorEvent.class, InvalidAmountErrorEvent.class})
    @NotNull
    public Stream<DomainEvent> credit(@NotNull CreditWalletCommand cmd, @NotNull WalletState currentState) {
        WalletState.Balance balance = currentState.balances().stream().filter(b -> b.currency().equals(cmd.currency())).findFirst().orElse(null);
        if (balance == null ) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidCurrencyErrorEvent(cmd.id()));
        }
        if (cmd.amount().compareTo(BigDecimal.ZERO) < 0) {
            // TODO: add more detail to the error event
            return Stream.of(new InvalidAmountErrorEvent(cmd.id()));
        }
        return Stream.of(new WalletCreditedEvent(currentState.id(), cmd.currency(), cmd.amount(), balance.amount().add(cmd.amount())));
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

}
