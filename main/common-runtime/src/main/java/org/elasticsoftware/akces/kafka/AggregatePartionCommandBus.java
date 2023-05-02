package org.elasticsoftware.akces.kafka;

import org.elasticsoftware.akces.aggregate.CommandBusHolder;
class AggregatePartionCommandBus extends CommandBusHolder {
    static void registerCommandBus(AggregatePartition aggregatePartition) {
        commandBusThreadLocal.set(aggregatePartition);
    }
}
