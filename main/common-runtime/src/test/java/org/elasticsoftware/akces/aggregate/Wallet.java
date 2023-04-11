package org.elasticsoftware.akces.aggregate;

import jakarta.inject.Named;
import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;


@AggregateInfo("Wallet")
@Named("Wallet")
public final class Wallet implements Aggregate<WalletState> {

    @Override
    public String getName() {
        return "Wallet";
    }

    @Override
    public Class<WalletState> getStateClass() {
        return WalletState.class;
    }

    @CommandHandler(create = true)
    public @NotNull WalletCreatedEvent create(@NotNull CreateWalletCommand cmd, WalletState isNull) {
        return new WalletCreatedEvent(cmd.id(), cmd.currency(), BigDecimal.ZERO);
    }

    @EventHandler(create = true)
    public @NotNull WalletCreatedEvent create(@NotNull AccountCreatedEvent event, WalletState isNull) {
        // TODO: base the currency on the country
        return new WalletCreatedEvent(event.getAggregateId(), "EUR", BigDecimal.ZERO);
    }

    @CommandHandler
    @NotNull
    public DomainEvent credit(@NotNull CreditWalletCommand cmd, @NotNull WalletState currentState) {
        if (!cmd.currency().equals(currentState.currency())) {
            // TODO: add more detail to the error event
            return new InvalidCurrencyErrorEvent(cmd.id());
        }
        if (cmd.amount().compareTo(BigDecimal.ZERO) > 0) {
            // TODO: add more detail to the error event
            return new InvalidAmountErrorEvent(cmd.id());
        }
        return new WalletCreditedEvent(currentState.id(), cmd.amount(), currentState.balance().add(cmd.amount()));
    }

    @EventSourcingHandler(create = true)
    public @NotNull WalletState create(@NotNull WalletCreatedEvent event, WalletState isNull) {
        return new WalletState(event.id(), event.currency(), event.balance());
    }

    @EventSourcingHandler
    public @NotNull WalletState credit(@NotNull WalletCreditedEvent event, @NotNull WalletState state) {
        return new WalletState(state.id(), state.currency(), state.balance().add(event.amount()));
    }

}
