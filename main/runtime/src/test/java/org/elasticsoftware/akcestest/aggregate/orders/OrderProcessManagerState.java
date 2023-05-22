package org.elasticsoftware.akcestest.aggregate.orders;

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
}
