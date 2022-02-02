package org.elasticsoftware.akces.protocol;

public record AggregateStateRecord(
        String name,
        int version,
        byte[] payload,
        PayloadEncoding encoding,
        String aggregateId,
        long generation
) implements ProtocolRecord {
}
