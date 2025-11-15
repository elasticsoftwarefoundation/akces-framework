/*
 * Copyright 2022 - 2025 The Original Authors
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

package org.elasticsoftware.akces.query.database;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.gdpr.GDPRContext;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.schemas.SchemaRegistry;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public interface DatabaseModelRuntime {
    String getName();

    void apply(Map<TopicPartition, List<ConsumerRecord<String, ProtocolRecord>>> records,
               Function<String,GDPRContext> gdprContextSupplier) throws IOException;

    void validateDomainEventSchemas(SchemaRegistry schemaRegistry);

    boolean shouldHandlePIIData();

    Collection<DomainEventType<?>> getDomainEventTypes();

    Map<TopicPartition,Long> initializeOffsets(Collection<TopicPartition> topicPartitions);
}
