package org.elasticsoftware.akces.control;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
        @JsonSubTypes.Type(CommandServiceRecord.class)
)
public sealed interface AkcesControlRecord permits CommandServiceRecord {
}
