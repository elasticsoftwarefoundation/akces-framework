package org.elasticsoftware.akces.aggregate;

import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;

import java.io.IOException;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface AggregateRuntime {

    String getName();

    void handleCommandRecord(CommandRecord commandRecord,
                             Consumer<ProtocolRecord> protocolRecordConsumer,
                             Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException;

    void handleExternalDomainEventRecord(DomainEventRecord eventRecord,
                                         Consumer<ProtocolRecord> protocolRecordConsumer,
                                         Supplier<AggregateStateRecord> stateRecordSupplier) throws IOException;

    Collection<DomainEventType<?>> getDomainEventTypes();

    JsonSchema generateJsonSchema(DomainEventType<?> domainEventType);

    void registerAndValidate(DomainEventType<?> domainEventType) throws Exception;
}
