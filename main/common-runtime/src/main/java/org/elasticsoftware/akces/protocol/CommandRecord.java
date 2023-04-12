package org.elasticsoftware.akces.protocol;

public record CommandRecord(
        String tenantId,
        String name,
        int version,
        byte[] payload,
        PayloadEncoding encoding,
        String aggregateId,
        String correlationId
) implements ProtocolRecord {
}
