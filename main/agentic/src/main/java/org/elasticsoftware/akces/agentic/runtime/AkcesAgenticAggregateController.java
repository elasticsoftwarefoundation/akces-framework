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

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
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
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
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

import static org.elasticsoftware.akces.kafka.KafkaAggregateRuntime.normalizeDescription;
import static org.elasticsoftware.akces.kafka.PartitionUtils.COMMANDS_SUFFIX;
import static org.elasticsoftware.akces.kafka.PartitionUtils.DOMAINEVENTS_SUFFIX;

import static java.nio.charset.StandardCharsets.UTF_8;

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
 * topics and partitions without any external registry. For commands targeting
 * {@link AggregateServiceType#AGENTIC AGENTIC} services,
 * {@link #resolvePartition(CommandType, Command)} returns {@code 0}; for
 * {@link AggregateServiceType#STANDARD STANDARD} services hash-based partitioning
 * is used, consistent with
 * {@link org.elasticsoftware.akces.AkcesAggregateController}.
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
            AgenticAggregateRuntime.MEMORY_REVOKED_TYPE,
            AgenticAggregateRuntime.AGENT_TASK_ASSIGNED_TYPE,
            AgenticAggregateRuntime.AGENT_TASK_FINISHED_TYPE
    );

    /** Built-in command types provided by the agentic framework. */
    @SuppressWarnings("unchecked")
    private static final List<CommandType<?>> BUILTIN_COMMAND_TYPES = List.of(
            AgenticAggregateRuntime.ASSIGN_TASK_COMMAND_TYPE
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
    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();

    /** The partition instance created in {@link #run()}; {@code null} until then. */
    private volatile AgenticAggregatePartition partition;

    private volatile SchemaRegistry schemaRegistry;

    /** Number of partitions on the Akces-Control topic, used for hash-based command routing. */
    private Integer partitions = null;

    /** Spring {@link ApplicationContext} injected by {@link #setApplicationContext(ApplicationContext)}. */
    private ApplicationContext applicationContext;

    /** Spring {@link Environment} injected by {@link #setEnvironment(Environment)}.
     *  Retained for Spring Boot health-check semantics consistency with
     *  {@link org.elasticsoftware.akces.AkcesAggregateController}; available for
     *  subclasses or future extensions that may need environment properties. */
    private Environment environment;

    /** Whether to force-register built-in schemas when they are incompatible with existing ones.
     *  Configured via the {@code akces.aggregate.schemas.forceRegister} environment property. */
    private boolean forceRegisterOnIncompatible = false;

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
     * Registers the built-in agentic schemas with the schema registry: the memory events
     * ({@link org.elasticsoftware.akces.agentic.events.MemoryStoredEvent},
     * {@link org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent}),
     * the {@link org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent}, and
     * the {@link org.elasticsoftware.akces.agentic.commands.AssignTaskCommand}.
     *
     * <p>If a schema is incompatible with the existing registered schema, the behaviour
     * depends on the {@code akces.aggregate.schemas.forceRegister} environment property:
     * when {@code true}, the schema is force-registered; otherwise the incompatibility is
     * propagated as a fatal error.
     */
    private void registerBuiltinSchemas() {
        logger.info("Registering built-in agentic schemas for {}Aggregate",
                aggregateRuntime.getName());
        for (DomainEventType<?> eventType : BUILTIN_EVENT_TYPES) {
            try {
                aggregateRuntime.registerAndValidate(eventType, schemaRegistry);
            } catch (IncompatibleSchemaException e) {
                if (forceRegisterOnIncompatible) {
                    logger.warn("Built-in event schema {} is incompatible — force-registering (forceRegister=true)",
                            eventType.typeName(), e);
                    try {
                        aggregateRuntime.registerAndValidate(eventType, schemaRegistry, true);
                    } catch (Exception ex) {
                        logger.error("Failed to force-register built-in event schema {}",
                                eventType.typeName(), ex);
                        throw new RuntimeException("Failed to register built-in event schema: "
                                + eventType.typeName(), ex);
                    }
                } else {
                    throw new RuntimeException("Built-in event schema " + eventType.typeName()
                            + " is incompatible. Set akces.aggregate.schemas.forceRegister=true to override.", e);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to register built-in event schema: "
                        + eventType.typeName(), e);
            }
        }
        for (CommandType<?> commandType : BUILTIN_COMMAND_TYPES) {
            try {
                aggregateRuntime.registerAndValidate(commandType, schemaRegistry);
            } catch (IncompatibleSchemaException e) {
                if (forceRegisterOnIncompatible) {
                    logger.warn("Built-in command schema {} is incompatible — force-registering (forceRegister=true)",
                            commandType.typeName(), e);
                    try {
                        aggregateRuntime.registerAndValidate(commandType, schemaRegistry, true);
                    } catch (Exception ex) {
                        logger.error("Failed to force-register built-in command schema {}",
                                commandType.typeName(), ex);
                        throw new RuntimeException("Failed to register built-in command schema: "
                                + commandType.typeName(), ex);
                    }
                } else {
                    throw new RuntimeException("Built-in command schema " + commandType.typeName()
                            + " is incompatible. Set akces.aggregate.schemas.forceRegister=true to override.", e);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to register built-in command schema: "
                        + commandType.typeName(), e);
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
            partitions = controlDesc.partitions().size();
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
            // Collect aggregate command types for the service record (built-in + aggregate-specific)
            List<AggregateServiceCommandType> allCommands = new ArrayList<>();
            BUILTIN_COMMAND_TYPES.forEach(ct ->
                    allCommands.add(new AggregateServiceCommandType(
                            ct.typeName(), ct.version(), ct.create(),
                            "commands." + ct.typeName(),
                            normalizeDescription(ct.typeClass().getAnnotation(CommandInfo.class).description()))));
            aggregateRuntime.getLocalCommandTypes().forEach(ct ->
                    allCommands.add(new AggregateServiceCommandType(
                            ct.typeName(), ct.version(), ct.create(),
                            "commands." + ct.typeName(),
                            normalizeDescription(ct.typeClass().getAnnotation(CommandInfo.class).description()))));

            // Combine built-in + aggregate event types
            List<AggregateServiceDomainEventType> allEvents = new ArrayList<>();
            BUILTIN_EVENT_TYPES.forEach(et ->
                    allEvents.add(new AggregateServiceDomainEventType(
                            et.typeName(), et.version(), et.create(), et.external(),
                            "domainevents." + et.typeName(),
                            normalizeDescription(et.typeClass().getAnnotation(DomainEventInfo.class).description()))));
            aggregateRuntime.getProducedDomainEventTypes().forEach(et ->
                    allEvents.add(new AggregateServiceDomainEventType(
                            et.typeName(), et.version(), et.create(), et.external(),
                            "domainevents." + et.typeName(),
                            normalizeDescription(et.typeClass().getAnnotation(DomainEventInfo.class).description()))));

            // Consumed external events — mirrors the pattern in AkcesAggregateController so
            // that service discovery correctly reflects the external event dependencies.
            List<AggregateServiceDomainEventType> consumedEvents =
                    aggregateRuntime.getExternalDomainEventTypes().stream()
                            .map(et -> new AggregateServiceDomainEventType(
                                    et.typeName(), et.version(), et.create(), et.external(),
                                    "domainevents." + et.typeName(),
                                    normalizeDescription(et.typeClass().getAnnotation(DomainEventInfo.class).description())))
                            .toList();

            AggregateServiceRecord serviceRecord = new AggregateServiceRecord(
                    aggregateRuntime.getName(),
                    aggregateRuntime.getName() + COMMANDS_SUFFIX,
                    aggregateRuntime.getName() + DOMAINEVENTS_SUFFIX,
                    AggregateServiceType.AGENTIC,
                    allCommands,
                    allEvents,
                    consumedEvents,
                    aggregateRuntime.getDescription()
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
        this.forceRegisterOnIncompatible = env.getProperty("akces.aggregate.schemas.forceRegister", Boolean.class, false);
    }

    // -------------------------------------------------------------------------
    // AkcesRegistry implementation
    // -------------------------------------------------------------------------

    /**
     * Resolves the {@link CommandType} for the given command class.
     *
     * <p>First checks this aggregate's own local command types; if not found, consults
     * the {@link AggregateServiceRecord}s loaded from the {@code Akces-Control} topic.
     * This mirrors the resolution logic of
     * {@link org.elasticsoftware.akces.AkcesAggregateController#resolveType(Class)}.
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
            // Check local command types first
            CommandType<?> localType = aggregateRuntime.getLocalCommandType(
                    commandInfo.type(), commandInfo.version());
            if (localType != null) {
                return localType;
            }
            // Fall back to external aggregate services
            List<AggregateServiceRecord> services = aggregateServices.values().stream()
                    .filter(s -> supportsCommand(s.supportedCommands(), commandInfo))
                    .toList();
            if (!services.isEmpty()) {
                return new CommandType<>(commandInfo.type(),
                        commandInfo.version(),
                        commandClass,
                        false,
                        true,
                        false);
            }
            throw new IllegalStateException(
                    "Cannot determine where to send command " + commandClass.getName());
        } else {
            throw new IllegalStateException(
                    "Command class " + commandClass.getName()
                            + " is not annotated with @CommandInfo");
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>When multiple services advertise the same command type (typically built-in agentic
     * commands), the command's {@link Command#getAggregateId() aggregateId} is matched
     * against the {@link AggregateServiceRecord#aggregateName()} of
     * {@link AggregateServiceType#AGENTIC AGENTIC} services to select the correct target.
     */
    @Override
    @Nonnull
    public String resolveTopic(@Nonnull CommandType<?> commandType, @Nonnull Command command) {
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(s -> supportsCommand(s.supportedCommands(), commandType))
                .toList();
        if (services.size() == 1) {
            return services.getFirst().commandTopic();
        } else if (services.size() > 1) {
            AggregateServiceRecord target = AggregateServiceRecord.resolveAgenticTarget(
                    services, command.getAggregateId());
            if (target != null) {
                return target.commandTopic();
            }
            throw new IllegalStateException("Cannot determine where to send command "
                    + commandType.typeName() + " v" + commandType.version()
                    + " for aggregateId " + command.getAggregateId());
        } else {
            throw new IllegalStateException("Cannot determine where to send command "
                    + commandType.typeName() + " v" + commandType.version());
        }
    }

    /**
     * Resolves the domain-event topic(s) for the given {@link DomainEventType} by looking up the
     * registered {@link AggregateServiceRecord}s loaded from the {@code Akces-Control} topic.
     *
     * <p>Built-in agentic events may be produced by multiple agentic aggregates, so more
     * than one topic can match. If no matching record is found, falls back to this
     * aggregate's own domain-event topic and logs a warning.
     *
     * @param externalDomainEventType the domain event type to resolve
     * @return a list of domain-event topic names that produce this event type
     */
    @Override
    @Nonnull
    public List<String> resolveTopics(@Nonnull DomainEventType<?> externalDomainEventType) {
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(s -> producesDomainEvent(s.producedEvents(), externalDomainEventType))
                .toList();
        if (!services.isEmpty()) {
            return services.stream().map(AggregateServiceRecord::domainEventTopic).toList();
        }
        logger.warn("Cannot resolve domain event topic for type '{}' version {}; falling back to own topic",
                externalDomainEventType.typeName(), externalDomainEventType.version());
        return List.of(aggregateRuntime.getName() + DOMAINEVENTS_SUFFIX);
    }

    /**
     * {@inheritDoc}
     *
     * <p>For {@link AggregateServiceType#AGENTIC AGENTIC} services (which are always
     * single-partition), this method returns {@code 0}. For
     * {@link AggregateServiceType#STANDARD STANDARD} services the partition is determined
     * by hashing the aggregate identifier.
     */
    @Override
    @Nonnull
    public Integer resolvePartition(@Nonnull CommandType<?> commandType, @Nonnull Command command) {
        String aggregateId = command.getAggregateId();
        // Check whether the target service is AGENTIC; if so, always route to partition 0.
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(s -> supportsCommand(s.supportedCommands(), commandType))
                .toList();
        if (services.size() == 1) {
            if (services.getFirst().effectiveType() == AggregateServiceType.AGENTIC) {
                return 0;
            }
        } else if (services.size() > 1) {
            AggregateServiceRecord target = AggregateServiceRecord.resolveAgenticTarget(
                    services, aggregateId);
            if (target != null && target.effectiveType() == AggregateServiceType.AGENTIC) {
                return 0;
            }
        }
        // Default: hash-based partitioning for STANDARD services
        if (partitions == null) {
            throw new IllegalStateException("Partition count not yet initialised; "
                    + "Akces-Control topic may not have been loaded");
        }
        return Math.abs(hashFunction.hashString(aggregateId, UTF_8).asInt()) % partitions;
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

    // -------------------------------------------------------------------------
    // Helper methods for AkcesRegistry
    // -------------------------------------------------------------------------

    private boolean supportsCommand(List<AggregateServiceCommandType> supportedCommands,
                                    CommandInfo commandInfo) {
        for (AggregateServiceCommandType supportedCommand : supportedCommands) {
            if (supportedCommand.typeName().equals(commandInfo.type())
                    && supportedCommand.version() == commandInfo.version()) {
                return true;
            }
        }
        return false;
    }

    private boolean supportsCommand(List<AggregateServiceCommandType> supportedCommands,
                                    CommandType<?> commandType) {
        for (AggregateServiceCommandType supportedCommand : supportedCommands) {
            if (supportedCommand.typeName().equals(commandType.typeName())
                    && supportedCommand.version() == commandType.version()) {
                return true;
            }
        }
        return false;
    }

    private boolean producesDomainEvent(List<AggregateServiceDomainEventType> producedEvents,
                                        DomainEventType<?> externalDomainEventType) {
        for (AggregateServiceDomainEventType producedEvent : producedEvents) {
            if (producedEvent.typeName().equals(externalDomainEventType.typeName())
                    && producedEvent.version() == externalDomainEventType.version()) {
                return true;
            }
        }
        return false;
    }
}
