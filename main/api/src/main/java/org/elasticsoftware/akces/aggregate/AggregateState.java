package org.elasticsoftware.akces.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;

public interface AggregateState {
    @JsonIgnore
    @NotNull String getAggregateId();
}
