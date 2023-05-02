package org.elasticsoftware.akces.state;

import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;

public class RocksDBAggregateStateRepositoryFactory implements AggregateStateRepositoryFactory{
    private final ProtocolRecordSerde serde;
    private final String baseDir;

    public RocksDBAggregateStateRepositoryFactory(ProtocolRecordSerde serde, String baseDir) {
        this.serde = serde;
        this.baseDir = baseDir;
    }

    @Override
    public AggregateStateRepository create(AggregateRuntime aggregateRuntime, Integer partitionId) {
        return new RocksDBAggregateStateRepository(
                baseDir,
                aggregateRuntime.getName()+"-AggregateState-"+partitionId.toString(),
                aggregateRuntime.getName()+"-AggregateState",
                serde.serializer(),
                serde.deserializer());
    }
}
