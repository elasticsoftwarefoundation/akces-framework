package org.elasticsoftware.akces.aggregate;

import org.elasticsoftware.akces.commands.Command;

public record CommandType<C extends Command>(String typeName, int version, Class<C> typeClass, boolean create) {
}
