package org.elasticsoftware.akces;

public abstract class AkcesException extends RuntimeException {
    private final String aggregateName;
    private final String aggregateId;

    public AkcesException(String aggregateName, String aggregateId) {
        this.aggregateName = aggregateName;
        this.aggregateId = aggregateId;
    }

    public String getAggregateName() {
        return aggregateName;
    }

    public String getAggregateId() {
        return aggregateId;
    }
}
