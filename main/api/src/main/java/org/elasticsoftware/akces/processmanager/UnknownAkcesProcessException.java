package org.elasticsoftware.akces.processmanager;

import org.elasticsoftware.akces.AkcesException;

public class UnknownAkcesProcessException extends AkcesException {
    private final String processId;

    public UnknownAkcesProcessException(String aggregateName, String aggregateId, String processId) {
        super(aggregateName, aggregateId);
        this.processId = processId;
    }

    public String getProcessId() {
        return processId;
    }
}
