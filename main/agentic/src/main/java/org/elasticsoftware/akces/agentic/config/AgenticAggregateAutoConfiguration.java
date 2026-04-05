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

package org.elasticsoftware.akces.agentic.config;

import org.elasticsoftware.akces.agentic.runtime.AgenticAggregatePartition;
import org.elasticsoftware.akces.agentic.runtime.AgenticAggregateRuntime;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.annotations.AgenticAggregateInfo;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Spring Boot auto-configuration for the Akces Agentic Aggregate module.
 *
 * <p>This configuration creates two primary beans:
 * <ol>
 *   <li>{@link AgenticAggregatePartition} — the single-partition Kafka consumer/producer
 *       loop that processes commands for an
 *       {@link org.elasticsoftware.akces.aggregate.AgenticAggregate}.</li>
 *   <li>{@link AgenticAggregateRuntime} — the lifecycle manager that initialises the
 *       schema registry, registers built-in and aggregate-specific schemas, publishes the
 *       {@code Akces-Control} record, and starts the partition.</li>
 * </ol>
 *
 * <p>Both beans are wired using the named Kafka factories produced by
 * {@link org.elasticsoftware.akces.agentic.runtime.AgenticAggregateServiceApplication}.
 *
 * <p>This configuration is automatically imported via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 */
@Configuration
@AutoConfiguration
public class AgenticAggregateAutoConfiguration {

    /**
     * Resolves the {@code maxMemories} sliding-window capacity from the
     * {@link AgenticAggregateInfo} annotation on the aggregate class.
     * Falls back to {@code 100} if the annotation is not present.
     *
     * @param aggregateRuntime the aggregate runtime whose class carries the annotation
     * @return the configured max-memories value
     */
    private static int resolveMaxMemories(AggregateRuntime aggregateRuntime) {
        AgenticAggregateInfo info = aggregateRuntime.getAggregateClass()
                .getAnnotation(AgenticAggregateInfo.class);
        return info != null ? info.maxMemories() : 100;
    }

    /**
     * Creates the {@link AgenticAggregatePartition} bean.
     *
     * <p>The partition hard-assigns Kafka partition {@code 0} and enforces the sliding-window
     * memory capacity defined by {@link AgenticAggregateInfo#maxMemories()}.
     * The {@code maxMemories} value is derived from the {@link AgenticAggregateInfo}
     * annotation on the aggregate class provided by the {@link AggregateRuntime}.
     *
     * @param consumerFactory        the protocol-record consumer factory
     * @param producerFactory        the protocol-record producer factory
     * @param aggregateRuntime       the aggregate runtime that handles commands
     * @param stateRepositoryFactory factory used to create the aggregate-state repository
     * @return the configured {@link AgenticAggregatePartition}
     */
    @Bean(name = "agenticAggregatePartition")
    public AgenticAggregatePartition agenticAggregatePartition(
            @Qualifier("agenticServiceConsumerFactory")
            ConsumerFactory<String, ProtocolRecord> consumerFactory,
            @Qualifier("agenticServiceProducerFactory")
            ProducerFactory<String, ProtocolRecord> producerFactory,
            AggregateRuntime aggregateRuntime,
            @Qualifier("agenticServiceAggregateStateRepositoryFactory")
            AggregateStateRepositoryFactory stateRepositoryFactory) {
        int maxMemories = resolveMaxMemories(aggregateRuntime);
        return new AgenticAggregatePartition(
                consumerFactory,
                producerFactory,
                aggregateRuntime,
                null, // SchemaRegistry is provided by AgenticAggregateRuntime after init
                stateRepositoryFactory,
                null, // AkcesRegistry provided by AgenticAggregateRuntime
                maxMemories);
    }

    /**
     * Creates the {@link AgenticAggregateRuntime} bean.
     *
     * <p>The runtime manages the full lifecycle: schema registration, control-topic
     * publication, and partition startup.  It also implements
     * {@link org.elasticsoftware.akces.control.AkcesRegistry} so that commands are always
     * routed to partition {@code 0}.
     *
     * @param schemaConsumerFactory  the schema consumer factory
     * @param schemaProducerFactory  the schema producer factory
     * @param controlProducerFactory the control-record producer factory
     * @param aggregateRuntime       the aggregate runtime
     * @param partition              the agentic partition created above
     * @return the configured and started {@link AgenticAggregateRuntime}
     */
    @Bean(name = "agenticAggregateRuntime",
          destroyMethod = "close")
    public AgenticAggregateRuntime agenticAggregateRuntime(
            @Qualifier("agenticServiceSchemaConsumerFactory")
            ConsumerFactory<String, SchemaRecord> schemaConsumerFactory,
            @Qualifier("agenticServiceSchemaProducerFactory")
            ProducerFactory<String, SchemaRecord> schemaProducerFactory,
            @Qualifier("agenticServiceControlProducerFactory")
            ProducerFactory<String, AkcesControlRecord> controlProducerFactory,
            AggregateRuntime aggregateRuntime,
            @Qualifier("agenticAggregatePartition")
            AgenticAggregatePartition partition) {
        AgenticAggregateRuntime runtime = new AgenticAggregateRuntime(
                schemaConsumerFactory,
                schemaProducerFactory,
                controlProducerFactory,
                aggregateRuntime,
                partition);
        runtime.setDaemon(false);
        runtime.start();
        return runtime;
    }
}

