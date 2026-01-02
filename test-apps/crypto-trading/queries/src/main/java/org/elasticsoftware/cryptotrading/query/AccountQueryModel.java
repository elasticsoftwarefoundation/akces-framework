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

package org.elasticsoftware.cryptotrading.query;

import org.elasticsoftware.akces.annotations.QueryModelEventHandler;
import org.elasticsoftware.akces.annotations.QueryModelInfo;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.cryptotrading.aggregates.account.events.AccountCreatedEvent;

// we cannot set the value of QueryModelInfo to Account because it will clash with the Account aggregate bean name
@QueryModelInfo(value = "AccountQueryModel", version = 1, indexName = "Users")
public class AccountQueryModel implements QueryModel<AccountQueryModelState> {
    @Override
    public String getName() {
        return "Account";
    }

    @Override
    public Class<AccountQueryModelState> getStateClass() {
        return AccountQueryModelState.class;
    }

    @Override
    public String getIndexName() {
        return "Users";
    }

    @QueryModelEventHandler(create = true)
    public AccountQueryModelState create(AccountCreatedEvent event, AccountQueryModelState isNull) {
        return new AccountQueryModelState(
                event.userId(),
                event.country(),
                event.firstName(),
                event.lastName(),
                event.email());
    }
}
