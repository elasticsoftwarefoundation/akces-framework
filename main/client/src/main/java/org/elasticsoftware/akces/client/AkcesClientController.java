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

package org.elasticsoftware.akces.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.WakeupException;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.AggregateServiceCommandType;
import org.elasticsoftware.akces.control.AggregateServiceDomainEventType;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.akces.gdpr.EncryptingGDPRContext;
import org.elasticsoftware.akces.gdpr.GDPRKeyUtils;
import org.elasticsoftware.akces.protocol.*;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.elasticsoftware.akces.util.HostUtils;
import org.elasticsoftware.akces.util.KafkaSender;
import org.everit.json.schema.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdminOperations;
import org.springframework.kafka.core.ProducerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.elasticsoftware.akces.client.AkcesClientControllerState.*;
import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;
import static org.elasticsoftware.akces.gdpr.GDPRContextHolder.resetCurrentGDPRContext;
import static org.elasticsoftware.akces.gdpr.GDPRContextHolder.setCurrentGDPRContext;

public class AkcesClientController extends Thread implements AutoCloseable, AkcesClient, ApplicationContextAware {
    private static final Logger logger = LoggerFactory.getLogger(AkcesClientController.class);
    private static final TopicPartition AKCES_CONTROL_PARTITION = new TopicPartition("Akces-Control", 0);
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory;
    private final ConsumerFactory<String, ProtocolRecord> commandResponseConsumerFactory;
    private final KafkaAdminOperations kafkaAdmin;
    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private final Map<String, AggregateServiceRecord> aggregateServices = new ConcurrentHashMap<>();
    private final BlockingQueue<CommandRequest> commandQueue = new LinkedBlockingQueue<>();
    private final Map<String, PendingCommandResponse> pendingCommandResponseMap = new HashMap<>();
    private final KafkaSchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends Command>, AggregateServiceCommandType> commandTypes = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Integer, DomainEventType<? extends DomainEvent>>> domainEventClasses = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ParsedSchema>> commandSchemas = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ParsedSchema>> domainEventSchemas = new ConcurrentHashMap<>();
    private final Map<Class<? extends Command>, ParsedSchema> commandSchemasLookup = new ConcurrentHashMap<>();
    private final ClassPathScanningCandidateComponentProvider domainEventScanner;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private Integer partitions = null;
    private volatile AkcesClientControllerState processState = INITIALIZING;
    private TopicPartition commandResponsePartition;
    private ApplicationContext applicationContext;

    public AkcesClientController(ProducerFactory<String, ProtocolRecord> producerFactory,
                                 ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory,
                                 ConsumerFactory<String, ProtocolRecord> commandResponseConsumerFactory,
                                 KafkaAdminOperations kafkaAdmin,
                                 KafkaSchemaRegistry schemaRegistry,
                                 ObjectMapper objectMapper,
                                 ClassPathScanningCandidateComponentProvider domainEventScanner,
                                 String basePackage) {
        super("AkcesClientController");
        this.producerFactory = producerFactory;
        this.controlRecordConsumerFactory = controlRecordConsumerFactory;
        this.commandResponseConsumerFactory = commandResponseConsumerFactory;
        this.kafkaAdmin = kafkaAdmin;
        this.schemaRegistry = schemaRegistry;
        this.objectMapper = objectMapper;
        this.domainEventScanner = domainEventScanner;
        loadSupportedDomainEvents(basePackage);
        // load the built-in error events as well
        loadSupportedDomainEvents("org.elasticsoftware.akces.errors");
    }

    @VisibleForTesting
    public TopicPartition getCommandResponsePartition() {
        return commandResponsePartition;
    }

    @Override
    public void run() {
        try (final Consumer<String, AkcesControlRecord> controlConsumer = controlRecordConsumerFactory.createConsumer(
                HostUtils.getHostName() + "-AkcesClientController-Control",
                HostUtils.getHostName() + "-AkcesClientController-Control",
                null);
             final Consumer<String, ProtocolRecord> commandResponseConsumer = commandResponseConsumerFactory.createConsumer(
                     HostUtils.getHostName() + "-AkcesClientController-CommandResponses",
                     HostUtils.getHostName() + "-AkcesClientController-CommandResponses",
                     null);
             final Producer<String, ProtocolRecord> producer = producerFactory.createProducer(HostUtils.getHostName() + "-AkcesClientController")) {
            // find out about the partitions
            partitions = kafkaAdmin.describeTopics("Akces-Control").get("Akces-Control").partitions().size();
            // always assign the first partition since all control data exists on every partition
            controlConsumer.assign(singletonList(AKCES_CONTROL_PARTITION));
            // seek to the beginning
            controlConsumer.seekToBeginning(singletonList(AKCES_CONTROL_PARTITION));
            // setup the command response consumer
            int commandResponsePartitions = kafkaAdmin.describeTopics("Akces-CommandResponses").get("Akces-CommandResponses").partitions().size();
            commandResponsePartition = new TopicPartition("Akces-CommandResponses",
                    resolveCommandResponsePartition(HostUtils.getHostName(), commandResponsePartitions));
            commandResponseConsumer.assign(singletonList(commandResponsePartition));
            // we are only interested in the latest messages
            commandResponseConsumer.seekToEnd(singletonList(commandResponsePartition));
            while (processState != SHUTTING_DOWN) {
                process(controlConsumer, commandResponseConsumer, producer);
            }
            // we need to make sure any pending CommandRequests are properly handled
            List<CommandRequest> pendingRequests = new ArrayList<>();
            commandQueue.drainTo(pendingRequests);
            for (CommandRequest pendingRequest : pendingRequests) {
                pendingRequest.completableFuture().completeExceptionally(new CommandRefusedException(pendingRequest.command().getClass(), SHUTTING_DOWN));
            }
            // raise an error for the liveness check
            applicationContext.publishEvent(new AvailabilityChangeEvent<>(this, LivenessState.BROKEN));
            // signal that we are done
            shutdownLatch.countDown();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadSupportedDomainEvents(String basePackage) {
        for (BeanDefinition beanDefinition : domainEventScanner.findCandidateComponents(basePackage)) {
            try {
                Class<? extends DomainEvent> domainEventClass = (Class<? extends DomainEvent>) Class.forName(beanDefinition.getBeanClassName());
                DomainEventInfo domainEventInfo = domainEventClass.getAnnotation(DomainEventInfo.class);
                TreeMap<Integer, DomainEventType<? extends DomainEvent>> versionMap = domainEventClasses.computeIfAbsent(domainEventInfo.type(), k -> new TreeMap<>());
                versionMap.put(domainEventInfo.version(), new DomainEventType<>(domainEventInfo.type(), domainEventInfo.version(), domainEventClass, false, true, ErrorEvent.class.isAssignableFrom(domainEventClass), hasPIIDataAnnotation(domainEventClass)));
            } catch (ClassNotFoundException e) {
                // ignore, cannot happen
            } catch (ClassCastException e) {
                // TODO: this can happen if the class is not a DomainEvent but is annotated with @DomainEventInfo
            }
        }
    }

    private void process(Consumer<String, AkcesControlRecord> controlConsumer,
                         Consumer<String, ProtocolRecord> commandResponseConsumer,
                         Producer<String, ProtocolRecord> producer) {
        if (processState == RUNNING) {
            try {
                // load any updated control data
                processControlRecords(controlConsumer.poll(Duration.ofMillis(10)));
                // process commands
                processCommands(producer);
                // process command responses
                processCommandResponses(commandResponseConsumer.poll(Duration.ofMillis(10)));
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesController", e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            } catch (IOException | RestClientException e) {
                // TODO: make failing on these errors optional (there might not be proper validation however)
                logger.error("Exception while loading Command (JSON)Schemas from SchemaRegistry", e);
                processState = SHUTTING_DOWN;
            }
        } else if (processState == INITIALIZING) {
            try {
                Map<TopicPartition, Long> endOffsets = controlConsumer.endOffsets(singletonList(AKCES_CONTROL_PARTITION));
                ConsumerRecords<String, AkcesControlRecord> consumerRecords = controlConsumer.poll(Duration.ofMillis(10));
                processControlRecords(consumerRecords);
                // stop condition
                if (consumerRecords.isEmpty() && endOffsets.getOrDefault(AKCES_CONTROL_PARTITION, 0L) <= controlConsumer.position(AKCES_CONTROL_PARTITION)) {
                    processState = RUNNING;
                }
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesController", e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            } catch (IOException | RestClientException e) {
                // TODO: make failing on these errors optional (there might not be proper validation however)
                logger.error("Exception while loading Command (JSON)Schemas from SchemaRegistry", e);
                processState = SHUTTING_DOWN;
            }
        }
    }

    private void processControlRecords(ConsumerRecords<String, AkcesControlRecord> consumerRecords) throws RestClientException, IOException {
        if (!consumerRecords.isEmpty()) {
            for (ConsumerRecord<String, AkcesControlRecord> record : consumerRecords) {
                AkcesControlRecord controlRecord = record.value();
                if (controlRecord instanceof AggregateServiceRecord aggregateServiceRecord) {
                    if (!aggregateServices.containsKey(record.key())) {
                        logger.info("Discovered service: {}", aggregateServiceRecord.aggregateName());
                    }
                    // always overwrite the record
                    aggregateServices.put(record.key(), aggregateServiceRecord);
                } else {
                    logger.warn("Received unknown AkcesControlRecord type: {}", controlRecord.getClass().getSimpleName());
                }
            }
        }
    }

    private void processCommands(Producer<String, ProtocolRecord> producer) {
        Map<ProducerRecord<String, ProtocolRecord>, CommandRequest> commandRecords = new HashMap<>();
        while(!commandQueue.isEmpty()) {
            // at initial startup it could be that aggregate service are not done publishing their service records
            // so it could be that we have commands that we cannot route yet
            CommandRequest headRequest = commandQueue.peek();
            if (headRequest != null) {
                try {
                    registerCommand(
                            headRequest.commandType(),
                            headRequest.commandVersion(),
                            headRequest.command().getClass());
                } catch (SchemaException e) {
                    // the command type is know but the SchemaRegistry is not updated yet
                    // we will try again next time
                    return;
                } catch (UnroutableCommandException e) {
                    // command not found, assume it is unroutable. we will fail in the next step
                }
            }
            CommandRequest commandRequest = commandQueue.poll();
            try {
                registerCommand(
                        commandRequest.commandType(),
                        commandRequest.commandVersion(),
                        commandRequest.command().getClass());
                // then determine the topic to produce on
                String topic = resolveTopic(
                        commandRequest.commandType(),
                        commandRequest.commandVersion(),
                        commandRequest.command());
                // then create a CommandRecord (this can cause an exception if the command is not serializable)
                CommandRecord commandRecord = new CommandRecord(
                        commandRequest.tenantId(),
                        commandRequest.commandType(),
                        commandRequest.commandVersion(),
                        serialize(commandRequest.command()),
                        PayloadEncoding.JSON,
                        commandRequest.command().getAggregateId(),
                        commandRequest.correlationId() != null ? commandRequest.correlationId() : UUID.randomUUID().toString(),
                        commandResponsePartition.toString());
                // create the ProducerRecord
                ProducerRecord<String, ProtocolRecord> producerRecord =
                        new ProducerRecord<>(
                                topic,
                                resolvePartition(commandRecord.aggregateId()),
                                commandRecord.aggregateId(),
                                commandRecord);
                commandRecords.put(producerRecord, commandRequest);
                // if this was sendAndForget we should complete the CompletableFuture here
                if (commandRequest.completeAfterValidation()) {
                    commandRequest.completableFuture().complete(Collections.emptyList());
                }
            } catch (AkcesClientCommandException | SchemaException e) {
                commandRequest.completableFuture().completeExceptionally(e);
            }
        }
        if(!commandRecords.isEmpty()) {
            // start a transaction
            producer.beginTransaction();
            for (Map.Entry<ProducerRecord<String, ProtocolRecord>, CommandRequest> entry : commandRecords.entrySet()) {
                final CompletableFuture<List<DomainEvent>> completableFuture = entry.getValue().completableFuture();
                final CommandRecord commandRecord = (CommandRecord) entry.getKey().value();
                final Class<? extends Command> commandClass = entry.getValue().command().getClass();
                if (!completableFuture.isDone()) {
                    KafkaSender.send(producer, entry.getKey(), (metadata, exception) -> {
                        if (exception != null) {
                            completableFuture.completeExceptionally(new CommandSendingFailedException(commandClass, exception));
                        } else {
                            // completion will happen when we get the CommandResponseRecord
                            pendingCommandResponseMap.put(commandRecord.id(), new PendingCommandResponse(commandRecord, completableFuture));
                        }
                    });
                } else {
                    // just send the command
                    KafkaSender.send(producer, entry.getKey());
                }
            }
            producer.commitTransaction();
        }
    }

    // process command responses
    private void processCommandResponses(ConsumerRecords<String, ProtocolRecord> consumerRecords) {
        if (!consumerRecords.isEmpty()) {
            for (ConsumerRecord<String, ProtocolRecord> record : consumerRecords) {
                ProtocolRecord protocolRecord = record.value();
                if (protocolRecord instanceof CommandResponseRecord commandResponseRecord) {
                    PendingCommandResponse pendingCommandResponse = pendingCommandResponseMap.remove(commandResponseRecord.commandId());
                    if (pendingCommandResponse == null) {
                        // The Akces-CommandResponses topic is shared between services so this is a normal occurrence
                        logger.trace("Received CommandResponseRecord for unknown commandId: {}", commandResponseRecord.commandId());
                    } else {
                        // deserialize the DomainEvents
                        try {
                            List<DomainEvent> domainEvents = new ArrayList<>();
                            for (DomainEventRecord domainEventRecord : commandResponseRecord.events()) {
                                domainEvents.add(deserialize(domainEventRecord, commandResponseRecord.encryptionKey()));
                            }
                            // TODO: maybe we should handle ErrorEvent instances differentlu
                            pendingCommandResponse.completableFuture().complete(domainEvents);
                        } catch (IOException e) {
                            // TODO: generate a Framework specific exception
                            pendingCommandResponse.completableFuture().completeExceptionally(e);
                        }
                    }
                } else {
                    logger.warn("Received unknown ProtocolRecord type: {}", protocolRecord.getClass().getSimpleName());
                }
            }
        }
    }

    @Override
    public CompletionStage<List<DomainEvent>> send(@Nonnull String tenantId, @Nullable String correlationId, @Nonnull Command command) {
        // only allow when the process is in the RUNNING state
        checkRunning(command);
        CommandInfo commandInfo = command.getClass().getAnnotation(CommandInfo.class);
        if(commandInfo == null) {
            // a Command class must be annotated with @CommandInfo, this is a programmer error
            throw new IllegalArgumentException("Command class " + command.getClass().getName() + " is not annotated with @CommandInfo");
        }
        // then schedule it for execution
        CompletableFuture<List<DomainEvent>> completableFuture = new CompletableFuture<>();
        commandQueue.add(new CommandRequest(
                tenantId,
                correlationId,
                commandInfo.type(),
                commandInfo.version(),
                command,
                completableFuture,
                false));
        // return the CompletableFuture
        return completableFuture;
    }

    @Override
    public void sendAndForget(@Nonnull String tenantId, @Nullable String correlationId, @Nonnull Command command) {
        // only allow when the process is in the RUNNING state
        checkRunning(command);
        // first validate the command with the registry
        CommandInfo commandInfo = command.getClass().getAnnotation(CommandInfo.class);
        if(commandInfo == null) {
            // a Command class must be annotated with @CommandInfo, this is a programmer error
            throw new IllegalArgumentException("Command class " + command.getClass().getName() + " is not annotated with @CommandInfo");
        }
        // then schedule it for execution
        CompletableFuture<List<DomainEvent>> completableFuture = new CompletableFuture<>();
        commandQueue.add(new CommandRequest(
                tenantId,
                correlationId,
                commandInfo.type(),
                commandInfo.version(),
                command,
                completableFuture,
                true));
        // we need to wait for any validation errors to be thrown
        try {
            completableFuture.get();
        } catch (InterruptedException | CancellationException e) {
            // ignore
        } catch (ExecutionException e) {
            // we need to rethrow the exception
            if(e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            } else {
                throw new RuntimeException(e.getCause());
            }
        }
    }

    @Override
    public void close() throws Exception {
        this.processState = SHUTTING_DOWN;
        // wait maximum of 10 seconds for the shutdown to complete
        try {
            if (shutdownLatch.await(10, TimeUnit.SECONDS)) {
                logger.info("AkcesClientController has been shutdown");
            } else {
                logger.warn("AkcesClientController did not shutdown within 10 seconds");
            }
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private AggregateServiceCommandType resolveCommandType(String type, int version) {
        return aggregateServices.values().stream()
                .filter(commandServiceRecord -> supportsCommand(commandServiceRecord.supportedCommands(), type, version))
                .findFirst().flatMap(commandServiceRecord -> commandServiceRecord.supportedCommands().stream()
                        .filter(commandType -> commandType.typeName().equals(type) && commandType.version() == version)
                        .findFirst()).orElse(null);

    }

    private AggregateServiceRecord resolveAggregateService(AggregateServiceCommandType commandType) {
        return aggregateServices.values().stream()
                .filter(commandServiceRecord -> supportsCommand(commandServiceRecord.supportedCommands(), commandType.typeName(), commandType.version()))
                .findFirst().orElse(null);

    }

    private String resolveTopic(String commandType, int version, Command command) {
        List<AggregateServiceRecord> services = aggregateServices.values().stream()
                .filter(commandServiceRecord -> supportsCommand(commandServiceRecord.supportedCommands(), commandType, version))
                .toList();
        if (services.size() == 1) {
            return services.getFirst().commandTopic();
        } else if (services.isEmpty()) {
            throw new UnroutableCommandException(command.getClass());
        } else {
            // TODO: make more specific exception
            throw new UnroutableCommandException(command.getClass());
        }
    }

    private boolean supportsCommand(List<AggregateServiceCommandType> supportedCommands, String commandType, int version) {
        for (AggregateServiceCommandType supportedCommand : supportedCommands) {
            if (supportedCommand.typeName().equals(commandType) &&
                    supportedCommand.version() == version) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    public Integer resolvePartition(@Nonnull String aggregateId) {
        return Math.abs(hashFunction.hashString(aggregateId, UTF_8).asInt()) % partitions;
    }

    private Integer resolveCommandResponsePartition(String hostname, int partitions) {
        return Math.abs(hashFunction.hashString(hostname, UTF_8).asInt()) % partitions;
    }

    private void registerCommand(String type, int version, Class<? extends Command> commandClass) {
        // see if we already know the command class
        if (!commandTypes.containsKey(commandClass)) {
            // we need to add it to the map
            // fetch the metadata from the aggregateServices
            AggregateServiceCommandType commandType = resolveCommandType(type, version);
            if (commandType != null) {
                // see if we have a schema for this command
                ParsedSchema schema = schemaRegistry.validate(commandType.toExternalCommandType(commandClass));
                commandSchemas.computeIfAbsent(
                        commandType.typeName(),
                        k -> new ConcurrentHashMap<>()).put(commandType.version(), schema);
                logger.trace("Stored schema: {} v{}", commandType.schemaName(), commandType.version());
                // add to index for quick lookup
                commandSchemasLookup.put(commandClass, schema);
                // we have a match, add it to the map
                commandTypes.put(commandClass, commandType);
                // now we need to process the produced DomainEvents
                // TODO: we only know this for the whole service, not for the specific command
                for (AggregateServiceDomainEventType domainEventType : resolveAggregateService(commandType).producedEvents()) {
                    processDomainEvent(commandClass, domainEventType);
                }
            } else {
                // the command is not known within the system, there is no way to route the command
                throw new UnroutableCommandException(commandClass);
            }
        }
    }

    private void processDomainEvent(Class<? extends Command> commandClass, AggregateServiceDomainEventType aggregateServiceDomainEventType) throws SchemaException {
        // first we need to find the local class
        DomainEventType<? extends DomainEvent> domainEventType =
                domainEventClasses.get(aggregateServiceDomainEventType.typeName()).floorEntry(aggregateServiceDomainEventType.version()).getValue();
        if (domainEventType != null) {
            // we have the local event, now we need to ensure it's valid according to the schema
            domainEventSchemas.computeIfAbsent(
                    domainEventType.typeName(),
                    k -> new ConcurrentHashMap<>()).put(
                    domainEventType.version(),
                    schemaRegistry.validate(domainEventType));
            logger.trace("Stored schema for: {} v{}", domainEventType.getSchemaName(), domainEventType.version());
        } else {
            throw new MissingDomainEventException(
                    commandClass,
                    aggregateServiceDomainEventType.typeName(),
                    aggregateServiceDomainEventType.version());
        }
    }

    private byte[] serialize(Command command) {
        try {
            // get the schema
            ParsedSchema schema = commandSchemasLookup.get(command.getClass());
            if (schema instanceof JsonSchema jsonSchema) {
                JsonNode jsonNode = objectMapper.valueToTree(command);
                jsonSchema.validate(jsonNode);
                return objectMapper.writeValueAsBytes(jsonNode);
            } else {
                return objectMapper.writeValueAsBytes(command);
            }
        } catch (IOException e) {
            throw new CommandSerializationException(command.getClass(), e);
        } catch (ValidationException e) {
            throw new CommandValidationException(command.getClass(), e);
        }
    }

    private DomainEvent deserialize(DomainEventRecord der, @Nullable byte[] encryptionKey) throws IOException {
        try {
            setCurrentGDPRContext(encryptionKey != null ? new EncryptingGDPRContext(der.aggregateId(), encryptionKey, GDPRKeyUtils.isUUID(der.aggregateId())) : null);
            // find the correct deserializer
            DomainEventType<? extends DomainEvent> domainEventType = domainEventClasses.get(der.name()).floorEntry(der.version()).getValue();
            // NOTE: the domainevent was validated when it was produced, so no need to validate it here
            return objectMapper.readValue(der.payload(), domainEventType.typeClass());
        } finally {
            resetCurrentGDPRContext();
        }

    }

    private void checkRunning(Command command) {
        if (processState == SHUTTING_DOWN) {
            throw new CommandRefusedException(command.getClass(), processState);
        }
    }

    public boolean isRunning() {
        return processState == RUNNING;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    private record CommandRequest(
            @Nonnull String tenantId,
            @Nullable String correlationId,
            @Nonnull String commandType,
            int commandVersion,
            @Nonnull Command command,
            @Nonnull CompletableFuture<List<DomainEvent>> completableFuture,
            boolean completeAfterValidation
    ) {

    }

    private record PendingCommandResponse(@Nonnull CommandRecord commandRecord,
                                          @Nonnull CompletableFuture<List<DomainEvent>> completableFuture) {
    }

}
