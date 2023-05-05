package org.elasticsoftware.akces.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;

@AggregateInfo("Account")
@SuppressWarnings("unused")
public final class Account implements Aggregate<AccountState> {
    @Override
    public String getName() {
        return "Account";
    }

    @Override
    public Class<AccountState> getStateClass() {
        return AccountState.class;
    }

    @CommandHandler(create = true, produces = AccountCreatedEvent.class, errors = {})
    public AccountCreatedEvent create(CreateAccountCommand cmd, AccountState isNull) {
        return new AccountCreatedEvent(cmd.userId(), cmd.country());
    }

    @EventSourcingHandler(create = true)
    @NotNull
    public AccountState create(@NotNull AccountCreatedEvent event, AccountState isNull) {
        return new AccountState(event.userId(), event.country());
    }
}
