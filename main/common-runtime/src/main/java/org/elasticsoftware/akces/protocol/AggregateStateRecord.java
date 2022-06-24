package org.elasticsoftware.akces.protocol;

public record AggregateStateRecord(
        String name,
        int version,
        byte[] payload,
        PayloadEncoding encoding,
        String aggregateId,
        String correlationId,
        long generation
) implements ProtocolRecord {
}
