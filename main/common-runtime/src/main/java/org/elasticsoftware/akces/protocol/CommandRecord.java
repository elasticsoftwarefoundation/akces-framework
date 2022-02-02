package org.elasticsoftware.akces.protocol;

public record CommandRecord(
        String name,
        int version,
        byte[] payload,
        PayloadEncoding encoding,
        String aggregateId
) implements ProtocolRecord {
}
