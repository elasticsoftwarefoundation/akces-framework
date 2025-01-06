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

package org.elasticsoftware.cryptotrading.aggregates.orders;

import jakarta.validation.constraints.NotNull;
import org.elasticsoftware.akces.annotations.AggregateIdentifier;
import org.elasticsoftware.akces.processmanager.ProcessManagerState;
import org.elasticsoftware.akces.processmanager.UnknownAkcesProcessException;

import java.util.List;

public record OrderProcessManagerState(
        @NotNull @AggregateIdentifier String userId,
        List<BuyOrderProcess> runningProcesses
) implements ProcessManagerState<OrderProcess> {
    public OrderProcessManagerState(@NotNull String userId) {
        this(userId, List.of());
    }
    @Override
    public String getAggregateId() {
        return userId();
    }

    @Override
    public OrderProcess getAkcesProcess(String processId) {
        return runningProcesses.stream().filter(p -> p.orderId().equals(processId)).findFirst()
                .orElseThrow(() -> new UnknownAkcesProcessException("OrderProcessManager", userId(), processId));
    }

    @Override
    public boolean hasAkcesProcess(String processId) {
        return runningProcesses.stream().anyMatch(p -> p.orderId().equals(processId));
    }
}
