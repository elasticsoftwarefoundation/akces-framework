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

package org.elasticsoftware.akcestest.aggregate.account;

import jakarta.annotation.Nonnull;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.annotations.PIIData;

@AggregateStateInfo(type = "Account", version = 1)
public record AccountState(@Nonnull String userId,
                           @Nonnull String country,
                           @Nonnull @PIIData String firstName,
                           @Nonnull @PIIData String lastName,
                           @Nonnull @PIIData String email,
                           Boolean twoFactorEnabled) implements AggregateState {
    // Compact constructor to handle possible null values from deserialization
    public AccountState {
        if (twoFactorEnabled == null) {
            twoFactorEnabled = false;
        }
    }

    // Default constructor with false for twoFactorEnabled
    public AccountState(@Nonnull String userId,
                       @Nonnull String country,
                       @Nonnull String firstName,
                       @Nonnull String lastName,
                       @Nonnull String email) {
        this(userId, country, firstName, lastName, email, false);
    }

    @Override
    public String getAggregateId() {
        return userId();
    }
}