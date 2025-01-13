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

package org.elasticsoftware.akces.query.models.wallet;

import org.elasticsoftware.akces.annotations.QueryModelEventHandler;
import org.elasticsoftware.akces.annotations.QueryModelInfo;
import org.elasticsoftware.akces.query.QueryModel;
import org.elasticsoftware.akcestest.aggregate.wallet.BalanceCreatedEvent;
import org.elasticsoftware.akcestest.aggregate.wallet.WalletCreatedEvent;

import java.util.List;

@QueryModelInfo(value = "WalletQueryModel", version = 1, indexName = "Users")
public class WalletQueryModel implements QueryModel<WalletQueryModelState> {
    @Override
    public String getName() {
        return "WalletQueryModel";
    }

    @Override
    public Class<WalletQueryModelState> getStateClass() {
        return WalletQueryModelState.class;
    }

    @Override
    public String getIndexName() {
        return "Users";
    }

    @QueryModelEventHandler(create = true)
    public WalletQueryModelState create(WalletCreatedEvent event, WalletQueryModelState isNull) {
        return new WalletQueryModelState(event.id(), List.of());
    }

    @QueryModelEventHandler(create = false)
    public WalletQueryModelState createBalance(BalanceCreatedEvent event, WalletQueryModelState currentState) {
        return new WalletQueryModelState(event.id(), List.of());
    }
}
