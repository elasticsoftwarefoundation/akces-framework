/*
 * Copyright 2022 - 2026 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces.state;

import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.serialization.ProtocolRecordSerde;

public class RocksDBAggregateStateRepositoryFactory implements AggregateStateRepositoryFactory {
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
                aggregateRuntime.getName() + "-AggregateState-" + partitionId.toString(),
                aggregateRuntime.getName() + "-AggregateState",
                serde.serializer(),
                serde.deserializer());
    }
}
