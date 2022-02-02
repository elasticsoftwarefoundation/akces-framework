package org.elasticsoftware.akces.aggregate;

public record AggregateStateType<C extends AggregateState>(String typeName, int version, Class<C> typeClass) {
}
