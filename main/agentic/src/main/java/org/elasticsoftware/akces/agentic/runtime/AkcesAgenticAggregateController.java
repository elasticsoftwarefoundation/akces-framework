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
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.control.AggregateServiceCommandType;
import org.elasticsoftware.akces.control.AggregateServiceDomainEventType;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AggregateServiceType;
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
import org.elasticsoftware.akces.state.AggregateStateRepositoryFactory;
import org.elasticsoftware.akces.util.HostUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdminOperations;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.elasticsoftware.akces.kafka.PartitionUtils.COMMANDS_SUFFIX;
import static org.elasticsoftware.akces.kafka.PartitionUtils.DOMAINEVENTS_SUFFIX;

/**
 * Lifecycle manager and {@link AkcesRegistry} for a single-partition
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate} service.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Initialise a {@link SchemaRegistry} backed by Kafka.</li>
 *   <li>Register and validate schemas for the built-in memory events
 *       ({@link org.elasticsoftware.akces.agentic.events.MemoryStoredEvent},
 *       {@link org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent}).</li>
 *   <li>Register and validate schemas for all commands and events declared by the
 *       wrapped {@link AgenticAggregateRuntime}.</li>
 *   <li>Publish an {@link AggregateServiceRecord} to the {@code Akces-Control} topic so
 *       that clients can discover the service and its supported command types.</li>
 *   <li>Create and start the associated {@link AgenticAggregatePartition}.</li>
 * </ol>
 *
 * <p>This class implements {@link AkcesRegistry} so that the partition can resolve
 * topics and partitions without any external registry. Because agentic aggregates
 * are always single-partition services, {@link #resolvePartition(String)} always
 * returns {@code 0}.
 *
 * <p>Unlike the previous design, this class is responsible for creating the
 * {@link AgenticAggregatePartition} internally during {@link #run()}, removing the
 * need for the partition to be registered as a separate Spring bean.
 *
 * <p>Implements {@link ApplicationContextAware} and {@link EnvironmentAware} so that
 * Spring injects the application context and environment at startup. When the partition
 * stops (normally or due to a fatal error), this controller publishes an
 * {@link AvailabilityChangeEvent} with {@link LivenessState#BROKEN} to signal a liveness
 * check failure, consistent with the behaviour of
 * {@link org.elasticsoftware.akces.AkcesAggregateController}.
 */
public class AkcesAgenticAggregateController extends Thread
        implements AutoCloseable, AkcesRegistry, ApplicationContextAware, EnvironmentAware {

    private static final Logger logger = LoggerFactory.getLogger(AkcesAgenticAggregateController.class);

    /** Maximum time in seconds to wait for graceful shutdown completion. */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;

    /** Poll timeout (ms) used when reading the {@code Akces-Control} topic during initialisation. */
    private static final long CONTROL_TOPIC_POLL_TIMEOUT_MS = 100L;

    /** Built-in domain-event types provided by the agentic framework. */
    @SuppressWarnings("unchecked")
    private static final List<DomainEventType<?>> BUILTIN_EVENT_TYPES = List.of(
            AgenticAggregateRuntime.MEMORY_STORED_TYPE,
            AgenticAggregateRuntime.MEMORY_REVOKED_TYPE
    );

    private final ConsumerFactory<String, SchemaRecord> schemaConsumerFactory;
    private final ProducerFactory<String, SchemaRecord> schemaProducerFactory;
    private final ProducerFactory<String, AkcesControlRecord> controlProducerFactory;
    private final ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory;
    private final KafkaAdminOperations kafkaAdmin;
    private final AgenticAggregateRuntime aggregateRuntime;
    private final ConsumerFactory<String, ProtocolRecord> consumerFactory;
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final AggregateStateRepositoryFactory stateRepositoryFactory;
    private final ExecutorService partitionExecutor;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final CountDownLatch doneLatch = new CountDownLatch(1);
    private final Map<String, AggregateServiceRecord> aggregateServices = new ConcurrentHashMap<>();

    /** The partition instance created in {@link #run()}; {@code null} until then. */
    private volatile AgenticAggregatePartition partition;

    private volatile SchemaRegistry schemaRegistry;

    /** Spring {@link ApplicationContext} injected by {@link #setApplicationContext(ApplicationContext)}. */
    private ApplicationContext applicationContext;

    /** Spring {@link Environment} injected by {@link #setEnvironment(Environment)}.
     *  Retained for Spring Boot health-check semantics consistency with
     *  {@link org.elasticsoftware.akces.AkcesAggregateController}; available for
     *  subclasses or future extensions that may need environment properties. */
    private Environment environment;

    /**
     * Creates a new {@code AkcesAgenticAggregateController}.
     *
     * @param schemaConsumerFactory  factory for creating the Kafka schema consumer
     * @param schemaProducerFactory  factory for creating the Kafka schema producer
     * @param controlProducerFactory factory for publishing to the {@code Akces-Control} topic
     * @param controlConsumerFactory factory for consuming from the {@code Akces-Control} topic
     * @param kafkaAdmin             Kafka admin operations for topic introspection
     * @param aggregateRuntime       the underlying agentic aggregate runtime that handles commands
     * @param consumerFactory        factory for creating the Kafka protocol consumer
     * @param producerFactory        factory for creating the Kafka protocol producer
     * @param stateRepositoryFactory factory for creating the aggregate-state repository
     */
    public AkcesAgenticAggregateController(
            ConsumerFactory<String, SchemaRecord> schemaConsumerFactory,
            ProducerFactory<String, SchemaRecord> schemaProducerFactory,
            ProducerFactory<String, AkcesControlRecord> controlProducerFactory,
            ConsumerFactory<String, AkcesControlRecord> controlConsumerFactory,
            KafkaAdminOperations kafkaAdmin,
            AgenticAggregateRuntime aggregateRuntime,
            ConsumerFactory<String, ProtocolRecord> consumerFactory,
            ProducerFactory<String, ProtocolRecord> producerFactory,
            AggregateStateRepositoryFactory stateRepositoryFactory) {
        super(aggregateRuntime.getName() + "-AkcesAgenticController");
        this.schemaConsumerFactory = schemaConsumerFactory;
        this.schemaProducerFactory = schemaProducerFactory;
        this.controlProducerFactory = controlProducerFactory;
        this.controlConsumerFactory = controlConsumerFactory;
        this.kafkaAdmin = kafkaAdmin;
        this.aggregateRuntime = aggregateRuntime;
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
        this.stateRepositoryFactory = stateRepositoryFactory;
        this.partitionExecutor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, aggregateRuntime.getName() + "-AgenticPartitionThread"));
    }

    /**
     * Main lifecycle method.
     *
     * <ol>
     *   <li>Initialises {@link SchemaStorage} and {@link SchemaRegistry}.</li>
     *   <li>Registers all built-in and aggregate-specific schemas.</li>
     *   <li>Reads existing {@link AggregateServiceRecord}s from the {@code Akces-Control} topic
     *       to enable external event topic resolution.</li>
     *   <li>Publishes the {@link AggregateServiceRecord} to {@code Akces-Control}.</li>
     *   <li>Creates and starts the {@link AgenticAggregatePartition} on a dedicated thread.</li>
     *   <li>Waits until {@link #close()} is called.</li>
     *   <li>After shutdown, publishes {@link AvailabilityChangeEvent} with
     *       {@link LivenessState#BROKEN} to signal liveness failure.</li>
     * </ol>
     */
    @Override
    public void run() {
        logger.info("Starting AkcesAgenticAggregateController for {}Aggregate", aggregateRuntime.getName());
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

            // Load existing aggregate service records so external event topics can be resolved
            loadAggregateServicesFromControlTopic();

            // Publish the service record so clients can discover this aggregate
            publishControlRecord();

            // Create and start the partition — this is the agentic equivalent of the
            // standard KafkaAggregateRuntime submitting a KafkaAggregatePartition.
            AgenticAggregatePartition localPartition = new AgenticAggregatePartition(
                    consumerFactory,
                    producerFactory,
                    aggregateRuntime,
                    stateRepositoryFactory,
                    aggregateRuntime.getExternalDomainEventTypes(),
                    this);
            this.partition = localPartition;
            logger.info("Starting AgenticAggregatePartition for {}Aggregate",
                    aggregateRuntime.getName());
            partitionExecutor.submit(localPartition);

            // Wait until shutdown is signalled
            shutdownLatch.await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("AkcesAgenticAggregateController for {}Aggregate was interrupted",
                    aggregateRuntime.getName());
        } catch (Exception e) {
            logger.error("Fatal error in AkcesAgenticAggregateController for {}Aggregate — shutting down",
                    aggregateRuntime.getName(), e);
        } finally {
            shutdownPartition();
            // Signal liveness failure so health checks report BROKEN
            if (applicationContext != null) {
                applicationContext.publishEvent(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));
            }
        }
        logger.info("AkcesAgenticAggregateController for {}Aggregate has stopped", aggregateRuntime.getName());
        doneLatch.countDown();
    }

    /**
     * Registers the built-in {@link org.elasticsoftware.akces.agentic.events.MemoryStoredEvent}
     * and {@link org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent} schemas with the
     * schema registry. These events are produced internally by the Embabel layer when the
     * agent stores or revokes memories.
     */
    private void registerBuiltinSchemas() {
        logger.info("Registering built-in agentic schemas for {}Aggregate",
                aggregateRuntime.getName());
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
     * {@link AgenticAggregateRuntime}.
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
     * Reads all existing {@link AggregateServiceRecord}s from the {@code Akces-Control} topic
     * so that external aggregate topics can be resolved when setting up external event
     * subscriptions.
     *
     * <p>Uses a one-shot consumer: seeks to the beginning of all partitions, reads until
     * the end offsets reached at the time of the call, then closes the consumer. This mirrors
     * the pattern used by {@link org.elasticsoftware.akces.AkcesAggregateController} during
     * its initialisation phase.
     */
    private void loadAggregateServicesFromControlTopic() {
        String groupId = aggregateRuntime.getName() + "-Agentic-ControlReader";
        String clientId = aggregateRuntime.getName() + "-" + HostUtils.getHostName() + "-ControlReader";
        try (Consumer<String, AkcesControlRecord> controlConsumer =
                     controlConsumerFactory.createConsumer(groupId, clientId, null)) {

            // Use Kafka admin to discover all Akces-Control partitions
            Map<String, TopicDescription> descriptions = kafkaAdmin.describeTopics("Akces-Control");
            TopicDescription controlDesc = descriptions.get("Akces-Control");
            if (controlDesc == null) {
                logger.warn("Akces-Control topic not found; no external aggregate services loaded");
                return;
            }
            List<TopicPartition> allPartitions = controlDesc.partitions().stream()
                    .map(p -> new TopicPartition("Akces-Control", p.partition()))
                    .toList();

            controlConsumer.assign(allPartitions);
            controlConsumer.seekToBeginning(allPartitions);
            Map<TopicPartition, Long> endOffsets = controlConsumer.endOffsets(allPartitions);

            // Track which partitions still have records to read
            Set<TopicPartition> remaining = allPartitions.stream()
                    .filter(tp -> endOffsets.getOrDefault(tp, 0L) > 0L)
                    .collect(Collectors.toSet());

            while (!remaining.isEmpty()) {
                ConsumerRecords<String, AkcesControlRecord> records =
                        controlConsumer.poll(Duration.ofMillis(CONTROL_TOPIC_POLL_TIMEOUT_MS));
                for (ConsumerRecord<String, AkcesControlRecord> record : records) {
                    if (record.value() instanceof AggregateServiceRecord serviceRecord) {
                        aggregateServices.put(record.key(), serviceRecord);
                    }
                }
                // Remove partitions that have been fully consumed
                remaining.removeIf(tp -> {
                    long endOffset = endOffsets.getOrDefault(tp, 0L);
                    long current = controlConsumer.position(tp);
                    return current >= endOffset;
                });
            }
            logger.info("Loaded {} aggregate service records from Akces-Control",
                    aggregateServices.size());
        } catch (Exception e) {
            logger.warn("Failed to load aggregate service records from Akces-Control; " +
                    "external event subscriptions may not work correctly", e);
        }
    }

    /**
     * Publishes an {@link AggregateServiceRecord} to the {@code Akces-Control} topic so that
     * clients can discover this service.
     */
    private void publishControlRecord() {
        String transactionalId = aggregateRuntime.getName() + "-" + HostUtils.getHostName()
                + "-agentic-control";
        try (Producer<String, AkcesControlRecord> controlProducer =
                     controlProducerFactory.createProducer(transactionalId)) {
            // Collect aggregate command types for the service record
            List<AggregateServiceCommandType> allCommands = new ArrayList<>();
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

            // Consumed external events — mirrors the pattern in AkcesAggregateController so
            // that service discovery correctly reflects the external event dependencies.
            List<AggregateServiceDomainEventType> consumedEvents =
                    aggregateRuntime.getExternalDomainEventTypes().stream()
                            .map(et -> new AggregateServiceDomainEventType(
                                    et.typeName(), et.version(), et.create(), et.external(),
                                    "domainevents." + et.typeName()))
                            .toList();

            AggregateServiceRecord serviceRecord = new AggregateServiceRecord(
                    aggregateRuntime.getName(),
                    aggregateRuntime.getName() + COMMANDS_SUFFIX,
                    aggregateRuntime.getName() + DOMAINEVENTS_SUFFIX,
                    AggregateServiceType.AGENTIC,
                    allCommands,
                    allEvents,
                    consumedEvents
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
        AgenticAggregatePartition localPartition = this.partition;
        if (localPartition != null) {
            try {
                localPartition.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
     * Signals this controller to shut down gracefully and waits up to 30 seconds for completion.
     *
     * <p>This method counts down the {@code shutdownLatch} (waking the {@link #run()} loop)
     * and then awaits the {@code doneLatch} (which is counted down only after {@link #run()}
     * has finished cleaning up).
     *
     * @throws Exception if waiting is interrupted
     */
    @Override
    public void close() throws Exception {
        logger.info("Shutting down AkcesAgenticAggregateController for {}Aggregate",
                aggregateRuntime.getName());
        // Signal the run() loop to exit
        shutdownLatch.countDown();
        // Wait for run() to actually finish
        try {
            if (!doneLatch.await(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("AkcesAgenticAggregateController for {}Aggregate did not shutdown within {} seconds",
                        aggregateRuntime.getName(), SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called by Spring to inject the {@link ApplicationContext}. The context is used to
     * publish {@link AvailabilityChangeEvent} with {@link LivenessState#BROKEN} when this
     * controller stops.
     *
     * @param ctx the Spring application context
     */
    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        this.applicationContext = ctx;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called by Spring to inject the {@link Environment}.
     *
     * @param env the Spring environment
     */
    @Override
    public void setEnvironment(Environment env) {
        this.environment = env;
    }

    // -------------------------------------------------------------------------
    // AkcesRegistry implementation
    // -------------------------------------------------------------------------

    /**
     * Resolves the {@link CommandType} for the given command class.
     *
     * <p>Command types are resolved from the underlying
     * {@link AgenticAggregateRuntime}'s local command types.
     *
     * @param commandClass the command class to resolve
     * @return the corresponding {@link CommandType}
     * @throws IllegalStateException if the command type cannot be resolved
     */
    @Override
    @Nonnull
    public CommandType<?> resolveType(@Nonnull Class<? extends Command> commandClass) {
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
     * Resolves the domain-event topic for the given {@link DomainEventType} by looking up the
     * registered {@link AggregateServiceRecord}s loaded from the {@code Akces-Control} topic.
     *
     * <p>If no matching record is found, falls back to this aggregate's own domain-event topic
     * and logs a warning.
     *
     * @param externalDomainEventType the domain event type to resolve
     * @return the domain-event topic name for the aggregate that produces this event type
     */
    @Override
    @Nonnull
    public String resolveTopic(@Nonnull DomainEventType<?> externalDomainEventType) {
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(s -> s.producedEvents().stream().anyMatch(
                        e -> e.typeName().equals(externalDomainEventType.typeName())
                                && e.version() == externalDomainEventType.version()))
                .toList();
        if (!services.isEmpty()) {
            return services.getFirst().domainEventTopic();
        }
        logger.warn("Cannot resolve domain event topic for type '{}' version {}; falling back to own topic",
                externalDomainEventType.typeName(), externalDomainEventType.version());
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
     * @return {@code true} when the partition has been created and is in the PROCESSING state
     */
    public boolean isRunning() {
        AgenticAggregatePartition localPartition = this.partition;
        return localPartition != null && localPartition.isProcessing();
    }
}
