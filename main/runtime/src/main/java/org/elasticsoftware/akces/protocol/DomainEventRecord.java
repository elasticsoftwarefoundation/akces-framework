package org.elasticsoftware.akces.protocol;

public record DomainEventRecord(
        String tenantId,
        String name,
        int version,
        byte[] payload,
        PayloadEncoding encoding,
        String aggregateId,
        String correlationId,
        long generation
) implements ProtocolRecord {

}
