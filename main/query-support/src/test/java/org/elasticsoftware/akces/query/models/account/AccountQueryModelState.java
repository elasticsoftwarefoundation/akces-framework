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

package org.elasticsoftware.akces.query.models.account;

import org.elasticsoftware.akces.annotations.QueryModelStateInfo;
import org.elasticsoftware.akces.query.QueryModelState;

@QueryModelStateInfo(type = "Account")
public record AccountQueryModelState(
        String accountId,
        String country,
        String firstName,
        String lastName,
        String email
) implements QueryModelState {
    @Override
    public String getIndexKey() {
        return accountId();
    }
}
