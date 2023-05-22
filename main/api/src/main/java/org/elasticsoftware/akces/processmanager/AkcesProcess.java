package org.elasticsoftware.akces.processmanager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;

public interface AkcesProcess {
    @JsonIgnore @NotNull
    String getProcessId();
}
