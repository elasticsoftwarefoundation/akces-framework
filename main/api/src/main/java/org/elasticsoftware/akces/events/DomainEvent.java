package org.elasticsoftware.akces.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;

public interface DomainEvent {
    @JsonIgnore
    @NotNull String getAggregateId();
}
