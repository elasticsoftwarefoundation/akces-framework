package org.elasticsoftware.akces.control;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.elasticsoftware.akces.commands.Command;

public record CommandServiceCommandType<C extends Command>(
        String typeName,
        int version,
        boolean create
) {
}
