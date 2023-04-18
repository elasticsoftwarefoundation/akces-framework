package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.CommandBus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

class CommandBusHolder {
    protected static Map<Class<? extends Aggregate>, CommandBus> commandBusMap = new ConcurrentHashMap<>();

    protected CommandBusHolder() {
    }

    public static CommandBus getCommandBus(Class<? extends Aggregate> aggregateClass) {
        return commandBusMap.get(aggregateClass);
    }
}
