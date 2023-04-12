package org.elasticsoftware.akces.protocol;

public sealed interface ProtocolRecord permits AggregateStateRecord, CommandRecord, DomainEventRecord {
    String tenantId();

    String name();

    int version();

    byte[] payload();

    PayloadEncoding encoding();

    String aggregateId();

    String correlationId();
}
