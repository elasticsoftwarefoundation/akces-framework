package org.elasticsoftware.akces.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.elasticsoftware.akces.commands.Command;

public record CommandType<C extends Command>(
        String typeName,
        int version,
        @JsonIgnore Class<C> typeClass,
        boolean create,
        boolean external
) {
}
