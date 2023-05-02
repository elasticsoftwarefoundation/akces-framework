package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.CommandBus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class CommandBusHolder {
    protected static final ThreadLocal<CommandBus> commandBusThreadLocal = new ThreadLocal<>();

    protected CommandBusHolder() {
    }

    public static CommandBus getCommandBus(Class<? extends Aggregate> aggregateClass) {
        return commandBusThreadLocal.get();
    }
}
