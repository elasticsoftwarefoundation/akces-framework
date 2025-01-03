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

package org.elasticsoftware.akcestest.aggregate.account;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.aggregate.Aggregate;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.annotations.CommandHandler;
import org.elasticsoftware.akces.annotations.EventSourcingHandler;

import java.util.stream.Stream;

@AggregateInfo(value = "Account",generateGDPRKeyOnCreate = true, indexed = true, indexName = "Users")
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
    public Stream<AccountCreatedEvent> create(CreateAccountCommand cmd, AccountState isNull) {
        return Stream.of(new AccountCreatedEvent(cmd.userId(), cmd.country(), cmd.firstName(), cmd.lastName(), cmd.email()));
    }

    @EventSourcingHandler(create = true)
    @NotNull
    public AccountState create(@NotNull AccountCreatedEvent event, AccountState isNull) {
        return new AccountState(event.userId(), event.country(), event.firstName(), event.lastName(), event.email());
    }
}
