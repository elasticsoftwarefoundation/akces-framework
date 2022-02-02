package org.elasticsoftware.akces.commands;

import jakarta.validation.constraints.NotNull;

public interface Command {
    @NotNull String getAggregateId();
}
