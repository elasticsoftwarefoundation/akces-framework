package org.elasticsoftware.akces.commands;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;

public interface Command {
    @JsonIgnore
    @NotNull String getAggregateId();
}
