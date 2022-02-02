package org.elasticsoftware.akces.protocol;

public record DomainEventRecord(
        String name,
        int version,
        byte[] payload,
        PayloadEncoding encoding,
        String aggregateId,
        long generation
) implements ProtocolRecord {

}
