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

package org.elasticsoftware.akces.agentic.runtime;

import jakarta.annotation.Nonnull;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.agentic.commands.ForgetMemoryCommand;
import org.elasticsoftware.akces.agentic.commands.StoreMemoryCommand;
import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.control.AggregateServiceCommandType;
import org.elasticsoftware.akces.control.AggregateServiceDomainEventType;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.control.AkcesRegistry;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.protocol.SchemaRecord;
import org.elasticsoftware.akces.schemas.IncompatibleSchemaException;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import org.elasticsoftware.akces.schemas.storage.KafkaTopicSchemaStorage;
import org.elasticsoftware.akces.schemas.storage.SchemaStorage;
import org.elasticsoftware.akces.util.HostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.elasticsoftware.akces.kafka.PartitionUtils.COMMANDS_SUFFIX;
import static org.elasticsoftware.akces.kafka.PartitionUtils.DOMAINEVENTS_SUFFIX;

/**
 * Lifecycle manager and {@link AkcesRegistry} for a single-partition
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate} service.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Initialise a {@link SchemaRegistry} backed by Kafka.</li>
 *   <li>Register and validate schemas for the built-in memory commands
 *       ({@link StoreMemoryCommand}, {@link ForgetMemoryCommand}) and events
 *       ({@link MemoryStoredEvent}, {@link MemoryRevokedEvent}).</li>
 *   <li>Register and validate schemas for all commands and events declared by the
 *       wrapped {@link AggregateRuntime}.</li>
 *   <li>Publish an {@link AggregateServiceRecord} to the {@code Akces-Control} topic so
 *       that clients can discover the service and its supported command types.</li>
 *   <li>Create and start the associated {@link AgenticAggregatePartition}.</li>
 * </ol>
 *
 * <p>This class implements {@link AkcesRegistry} so that the partition can resolve
 * topics and partitions without any external registry.  Because agentic aggregates
 * are always single-partition services, {@link #resolvePartition(String)} always
 * returns {@code 0}.
 */
public class AgenticAggregateRuntime extends Thread implements AkcesRegistry, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AgenticAggregateRuntime.class);

    /** Built-in command types provided by the agentic framework. */
    @SuppressWarnings("unchecked")
    private static final List<CommandType<?>> BUILTIN_COMMAND_TYPES = List.of(
            new CommandType<>("StoreMemory", 1, StoreMemoryCommand.class, false, false, false),
            new CommandType<>("ForgetMemory", 1, ForgetMemoryCommand.class, false, false, false)
    );

    /** Built-in domain-event types provided by the agentic framework. */
    @SuppressWarnings("unchecked")
    private static final List<DomainEventType<?>> BUILTIN_EVENT_TYPES = List.of(
            new DomainEventType<>("MemoryStored", 1, MemoryStoredEvent.class, false, false, false, false),
            new DomainEventType<>("MemoryRevoked", 1, MemoryRevokedEvent.class, false, false, false, false)
    );

    private final ConsumerFactory<String, SchemaRecord> schemaConsumerFactory;
    private final ProducerFactory<String, SchemaRecord> schemaProducerFactory;
    private final ProducerFactory<String, AkcesControlRecord> controlProducerFactory;
    private final AggregateRuntime aggregateRuntime;
    private final AgenticAggregatePartition partition;
    private final ExecutorService partitionExecutor;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private volatile SchemaRegistry schemaRegistry;

    /**
     * Creates a new {@code AgenticAggregateRuntime}.
     *
     * @param schemaConsumerFactory  factory for creating the Kafka schema consumer
     * @param schemaProducerFactory  factory for creating the Kafka schema producer
     * @param controlProducerFactory factory for publishing to the {@code Akces-Control} topic
     * @param aggregateRuntime       the underlying aggregate runtime that handles commands
     * @param partition              the agentic partition that processes command records
     */
    public AgenticAggregateRuntime(
            ConsumerFactory<String, SchemaRecord> schemaConsumerFactory,
            ProducerFactory<String, SchemaRecord> schemaProducerFactory,
            ProducerFactory<String, AkcesControlRecord> controlProducerFactory,
            AggregateRuntime aggregateRuntime,
            AgenticAggregatePartition partition) {
        super(aggregateRuntime.getName() + "-AgenticAggregateRuntime");
        this.schemaConsumerFactory = schemaConsumerFactory;
        this.schemaProducerFactory = schemaProducerFactory;
        this.controlProducerFactory = controlProducerFactory;
        this.aggregateRuntime = aggregateRuntime;
        this.partition = partition;
        this.partitionExecutor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, aggregateRuntime.getName() + "-AgenticPartitionThread"));
    }

    /**
     * Main lifecycle method.
     *
     * <ol>
     *   <li>Initialises {@link SchemaStorage} and {@link SchemaRegistry}.</li>
     *   <li>Registers all built-in and aggregate-specific schemas.</li>
     *   <li>Publishes the {@link AggregateServiceRecord} to {@code Akces-Control}.</li>
     *   <li>Starts the {@link AgenticAggregatePartition} on a dedicated thread.</li>
     *   <li>Waits until {@link #close()} is called.</li>
     * </ol>
     */
    @Override
    public void run() {
        logger.info("Starting AgenticAggregateRuntime for {}Aggregate", aggregateRuntime.getName());
        try {
            // Initialise schema storage and registry
            SchemaStorage schemaStorage = new KafkaTopicSchemaStorage(
                    schemaProducerFactory,
                    aggregateRuntime.getName() + "-Agentic-SchemaProducer",
                    schemaConsumerFactory.createConsumer(
                            aggregateRuntime.getName() + "-Agentic-SchemaConsumer",
                            aggregateRuntime.getName() + "-" + HostUtils.getHostName()
                                    + "-Agentic-SchemaConsumer"));
            schemaStorage.initialize();
            this.schemaRegistry = new KafkaSchemaRegistry(schemaStorage);

            // Register built-in command and event schemas
            registerBuiltinSchemas();

            // Register aggregate-specific command and event schemas
            registerAggregateSchemas();

            // Publish the service record so clients can discover this aggregate
            publishControlRecord();

            // Start partition processing
            logger.info("Starting AgenticAggregatePartition for {}Aggregate",
                    aggregateRuntime.getName());
            partitionExecutor.submit(partition);

            // Wait until shutdown is signalled
            shutdownLatch.await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("AgenticAggregateRuntime for {}Aggregate was interrupted",
                    aggregateRuntime.getName());
        } catch (Exception e) {
            logger.error("Fatal error in AgenticAggregateRuntime for {}Aggregate — shutting down",
                    aggregateRuntime.getName(), e);
        } finally {
            shutdownPartition();
        }
        logger.info("AgenticAggregateRuntime for {}Aggregate has stopped", aggregateRuntime.getName());
    }

    /**
     * Registers the built-in {@link StoreMemoryCommand}, {@link ForgetMemoryCommand},
     * {@link MemoryStoredEvent}, and {@link MemoryRevokedEvent} schemas with the
     * schema registry.
     */
    private void registerBuiltinSchemas() {
        logger.info("Registering built-in agentic schemas for {}Aggregate",
                aggregateRuntime.getName());
        for (CommandType<?> commandType : BUILTIN_COMMAND_TYPES) {
            try {
                aggregateRuntime.registerAndValidate(commandType, schemaRegistry);
            } catch (IncompatibleSchemaException e) {
                logger.warn("Built-in command schema {} is incompatible — attempting force-register",
                        commandType.typeName(), e);
                try {
                    aggregateRuntime.registerAndValidate(commandType, schemaRegistry, true);
                } catch (Exception ex) {
                    logger.error("Failed to force-register built-in command schema {}",
                            commandType.typeName(), ex);
                    throw new RuntimeException("Failed to register built-in command schema: "
                            + commandType.typeName(), ex);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to register built-in command schema: "
                        + commandType.typeName(), e);
            }
        }
        for (DomainEventType<?> eventType : BUILTIN_EVENT_TYPES) {
            try {
                aggregateRuntime.registerAndValidate(eventType, schemaRegistry);
            } catch (IncompatibleSchemaException e) {
                logger.warn("Built-in event schema {} is incompatible — attempting force-register",
                        eventType.typeName(), e);
                try {
                    aggregateRuntime.registerAndValidate(eventType, schemaRegistry, true);
                } catch (Exception ex) {
                    logger.error("Failed to force-register built-in event schema {}",
                            eventType.typeName(), ex);
                    throw new RuntimeException("Failed to register built-in event schema: "
                            + eventType.typeName(), ex);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to register built-in event schema: "
                        + eventType.typeName(), e);
            }
        }
    }

    /**
     * Registers schemas for all command and domain-event types produced by the underlying
     * {@link AggregateRuntime}.
     */
    private void registerAggregateSchemas() {
        logger.info("Registering aggregate schemas for {}Aggregate", aggregateRuntime.getName());
        for (DomainEventType<?> eventType : aggregateRuntime.getProducedDomainEventTypes()) {
            try {
                aggregateRuntime.registerAndValidate(eventType, schemaRegistry);
            } catch (Exception e) {
                logger.error("Failed to register domain event schema {}", eventType.typeName(), e);
                throw new RuntimeException("Failed to register domain event schema: "
                        + eventType.typeName(), e);
            }
        }
        for (CommandType<?> commandType : aggregateRuntime.getLocalCommandTypes()) {
            try {
                aggregateRuntime.registerAndValidate(commandType, schemaRegistry);
            } catch (Exception e) {
                logger.error("Failed to register command schema {}", commandType.typeName(), e);
                throw new RuntimeException("Failed to register command schema: "
                        + commandType.typeName(), e);
            }
        }
    }

    /**
     * Publishes an {@link AggregateServiceRecord} to the {@code Akces-Control} topic so that
     * clients can discover this service.
     *
     * <p>The record includes both the built-in agentic command types
     * ({@link StoreMemoryCommand}, {@link ForgetMemoryCommand}) and any additional command
     * types declared by the wrapped aggregate.
     */
    private void publishControlRecord() {
        String transactionalId = aggregateRuntime.getName() + "-" + HostUtils.getHostName()
                + "-agentic-control";
        try (Producer<String, AkcesControlRecord> controlProducer =
                     controlProducerFactory.createProducer(transactionalId)) {
            // Combine built-in + aggregate command types for the service record
            List<AggregateServiceCommandType> allCommands = new ArrayList<>();
            BUILTIN_COMMAND_TYPES.forEach(ct ->
                    allCommands.add(new AggregateServiceCommandType(
                            ct.typeName(), ct.version(), ct.create(),
                            "commands." + ct.typeName())));
            aggregateRuntime.getLocalCommandTypes().forEach(ct ->
                    allCommands.add(new AggregateServiceCommandType(
                            ct.typeName(), ct.version(), ct.create(),
                            "commands." + ct.typeName())));

            // Combine built-in + aggregate event types
            List<AggregateServiceDomainEventType> allEvents = new ArrayList<>();
            BUILTIN_EVENT_TYPES.forEach(et ->
                    allEvents.add(new AggregateServiceDomainEventType(
                            et.typeName(), et.version(), et.create(), et.external(),
                            "domainevents." + et.typeName())));
            aggregateRuntime.getProducedDomainEventTypes().forEach(et ->
                    allEvents.add(new AggregateServiceDomainEventType(
                            et.typeName(), et.version(), et.create(), et.external(),
                            "domainevents." + et.typeName())));

            AggregateServiceRecord serviceRecord = new AggregateServiceRecord(
                    aggregateRuntime.getName(),
                    aggregateRuntime.getName() + COMMANDS_SUFFIX,
                    aggregateRuntime.getName() + DOMAINEVENTS_SUFFIX,
                    allCommands,
                    allEvents,
                    List.of() // Agentic aggregates do not consume external events by default
            );

            controlProducer.beginTransaction();
            // Publish to partition 0 (single-partition agentic service)
            controlProducer.send(new ProducerRecord<>("Akces-Control", AgenticAggregatePartition.AGENTIC_PARTITION,
                    aggregateRuntime.getName(), serviceRecord));
            controlProducer.commitTransaction();
            logger.info("Published AggregateServiceRecord for {}Aggregate to Akces-Control",
                    aggregateRuntime.getName());
        } catch (Exception e) {
            logger.error("Failed to publish AggregateServiceRecord for {}Aggregate",
                    aggregateRuntime.getName(), e);
            throw new RuntimeException("Failed to publish control record", e);
        }
    }

    /**
     * Signals the {@link AgenticAggregatePartition} to stop, waits for it to terminate,
     * then shuts down the executor.
     */
    private void shutdownPartition() {
        try {
            partition.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        partitionExecutor.shutdown();
        try {
            if (!partitionExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                partitionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            partitionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Signals this runtime to shut down gracefully and waits up to 30 seconds.
     *
     * @throws Exception if waiting is interrupted
     */
    @Override
    public void close() throws Exception {
        logger.info("Shutting down AgenticAggregateRuntime for {}Aggregate",
                aggregateRuntime.getName());
        shutdownLatch.countDown();
        try {
            if (!shutdownLatch.await(30, TimeUnit.SECONDS)) {
                logger.warn("AgenticAggregateRuntime for {}Aggregate did not shutdown within 30 seconds",
                        aggregateRuntime.getName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // AkcesRegistry implementation
    // -------------------------------------------------------------------------

    /**
     * Resolves the {@link CommandType} for the given command class.
     *
     * <p>Built-in agentic commands ({@link StoreMemoryCommand}, {@link ForgetMemoryCommand})
     * are returned directly.  All other commands are resolved from the underlying
     * {@link AggregateRuntime}'s local command types.
     *
     * @param commandClass the command class to resolve
     * @return the corresponding {@link CommandType}
     * @throws IllegalStateException if the command type cannot be resolved
     */
    @Override
    @Nonnull
    public CommandType<?> resolveType(@Nonnull Class<? extends Command> commandClass) {
        // Check built-in types first
        for (CommandType<?> builtIn : BUILTIN_COMMAND_TYPES) {
            if (builtIn.typeClass().equals(commandClass)) {
                return builtIn;
            }
        }
        // Delegate to aggregate runtime
        var commandInfo = commandClass.getAnnotation(
                org.elasticsoftware.akces.annotations.CommandInfo.class);
        if (commandInfo != null) {
            CommandType<?> localType = aggregateRuntime.getLocalCommandType(
                    commandInfo.type(), commandInfo.version());
            if (localType != null) {
                return localType;
            }
        }
        throw new IllegalStateException(
                "Cannot resolve CommandType for class: " + commandClass.getName());
    }

    /**
     * Resolves the command topic for the given command class.
     *
     * <p>All commands for an agentic aggregate are routed to
     * {@code {aggregateName}-Commands}.
     *
     * @param commandClass the command class
     * @return the command topic name
     */
    @Override
    @Nonnull
    public String resolveTopic(@Nonnull Class<? extends Command> commandClass) {
        return aggregateRuntime.getName() + COMMANDS_SUFFIX;
    }

    /**
     * Resolves the command topic for the given {@link CommandType}.
     *
     * <p>All commands for an agentic aggregate are routed to
     * {@code {aggregateName}-Commands}.
     *
     * @param commandType the command type
     * @return the command topic name
     */
    @Override
    @Nonnull
    public String resolveTopic(@Nonnull CommandType<?> commandType) {
        return aggregateRuntime.getName() + COMMANDS_SUFFIX;
    }

    /**
     * Resolves the domain-event topic for the given {@link DomainEventType}.
     *
     * @param externalDomainEventType the domain event type
     * @return the domain-event topic name
     */
    @Override
    @Nonnull
    public String resolveTopic(@Nonnull DomainEventType<?> externalDomainEventType) {
        return aggregateRuntime.getName() + DOMAINEVENTS_SUFFIX;
    }

    /**
     * Resolves the partition for the given aggregate ID.
     *
     * <p>Agentic aggregates are always single-partition services, so this method
     * always returns {@code 0}.
     *
     * @param aggregateId the aggregate identifier (unused)
     * @return always {@code 0}
     */
    @Override
    @Nonnull
    public Integer resolvePartition(@Nonnull String aggregateId) {
        return AgenticAggregatePartition.AGENTIC_PARTITION;
    }

    /**
     * Returns the {@link SchemaRegistry} initialised by this runtime.
     * This method may return {@code null} if the runtime has not yet started.
     *
     * @return the schema registry, or {@code null} if not yet initialised
     */
    public SchemaRegistry getSchemaRegistry() {
        return schemaRegistry;
    }

    /**
     * Returns the name of the managed aggregate (delegating to the underlying runtime).
     *
     * @return the aggregate name
     */
    public String getAggregateName() {
        return aggregateRuntime.getName();
    }

    /**
     * Returns {@code true} if the underlying partition is actively processing commands.
     *
     * @return {@code true} when the partition is in the PROCESSING state
     */
    public boolean isRunning() {
        return partition.isProcessing();
    }
}
