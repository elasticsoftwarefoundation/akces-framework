package org.elasticsoftware.akces.aggregate;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.events.DomainEvent;

import java.math.BigDecimal;


public final class Wallet implements Aggregate<WalletState> {

    @Override
    public String getName() {
        return "Wallet";
    }

    @Override
    public Class<WalletState> getStateClass() {
        return WalletState.class;
    }

    @Override
    public AggregateBuilder<WalletState> configure(AggregateBuilder<WalletState> builder) {
        return builder.withCreateCommandHandler("CreateWallet", 1, CreateWalletCommand.class, this::create)
                .withCommandHandler("CreditWallet", 1, CreditWalletCommand.class, Wallet::credit)
                .withEventSourcedCreateEventHandler("WalletCreated", 1, WalletCreatedEvent.class, Wallet::create)
                .withEventSourcedEventHandler("WalletCredited", 1, WalletCreditedEvent.class, Wallet::credit);
    }

    public @NotNull WalletCreatedEvent create(@NotNull CreateWalletCommand cmd) {
        return new WalletCreatedEvent(cmd.id(), cmd.currency(), BigDecimal.ZERO);
    }

    @NotNull
    static DomainEvent credit(@NotNull CreditWalletCommand cmd, @NotNull WalletState currentState) {
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

    static @NotNull WalletState create(@NotNull WalletCreatedEvent event) {
        return new WalletState(event.id(), event.currency(), event.balance());
    }

    static @NotNull WalletState credit(@NotNull WalletCreditedEvent event, @NotNull WalletState state) {
        return new WalletState(state.id(), state.currency(), state.balance().add(event.amount()));
    }

}
