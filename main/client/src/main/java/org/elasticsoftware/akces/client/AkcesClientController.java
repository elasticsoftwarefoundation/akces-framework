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
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
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
import org.elasticsoftware.akces.util.HostUtils;
import org.elasticsoftware.akces.util.KafkaSender;
import org.everit.json.schema.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
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
import static org.elasticsoftware.akces.gdpr.GDPRContextHolder.resetCurrentGDPRContext;
import static org.elasticsoftware.akces.gdpr.GDPRContextHolder.setCurrentGDPRContext;

public class AkcesClientController extends Thread implements AutoCloseable, AkcesClient {
    private static final Logger logger = LoggerFactory.getLogger(AkcesClientController.class);
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory;
    private final ConsumerFactory<String, ProtocolRecord> commandResponseConsumerFactory;
    private final KafkaAdminOperations kafkaAdmin;
    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private Integer partitions = null;
    private volatile AkcesClientControllerState processState = INITIALIZING;
    private final Map<String, AggregateServiceRecord> aggregateServices = new ConcurrentHashMap<>();
    private final BlockingQueue<CommandRequest> commandQueue = new LinkedBlockingQueue<>();
    private final Map<String,PendingCommandResponse> pendingCommandResponseMap = new HashMap<>();
    private final SchemaRegistryClient schemaRegistryClient;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends Command>, AggregateServiceCommandType> commandTypes = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Integer, DomainEventType<? extends DomainEvent>>> domainEventClasses = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer,ParsedSchema>> commandSchemas = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer,ParsedSchema>> domainEventSchemas = new ConcurrentHashMap<>();
    private final Map<Class<? extends Command>,ParsedSchema> commandSchemasLookup = new ConcurrentHashMap<>();
    private static final TopicPartition AKCES_CONTROL_PARTITION = new TopicPartition("Akces-Control",0);
    private TopicPartition commandResponsePartition;
    private final ClassPathScanningCandidateComponentProvider domainEventScanner;

    public AkcesClientController(ProducerFactory<String, ProtocolRecord> producerFactory,
                                 ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory,
                                 ConsumerFactory<String, ProtocolRecord> commandResponseConsumerFactory,
                                 KafkaAdminOperations kafkaAdmin,
                                 SchemaRegistryClient schemaRegistryClient,
                                 ObjectMapper objectMapper,
                                 ClassPathScanningCandidateComponentProvider domainEventScanner,
                                 String basePackage) {
        super("AkcesClientController");
        this.producerFactory = producerFactory;
        this.controlRecordConsumerFactory = controlRecordConsumerFactory;
        this.commandResponseConsumerFactory = commandResponseConsumerFactory;
        this.kafkaAdmin = kafkaAdmin;
        this.schemaRegistryClient = schemaRegistryClient;
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
                    "AkcesClientController",
                    HostUtils.getHostName() + "-AkcesClientController",
                    null);
             final Consumer<String, ProtocolRecord> commandResponseConsumer = commandResponseConsumerFactory.createConsumer(
                     "AkcesClientController",
                     HostUtils.getHostName() + "-AkcesClientController",
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
            for(CommandRequest pendingRequest : pendingRequests) {
                if(pendingRequest.completableFuture() != null) {
                    pendingRequest.completableFuture().completeExceptionally(new CommandRefusedException(pendingRequest.command().getClass(), SHUTTING_DOWN));
                }
            }
        }
    }

    private void loadSupportedDomainEvents(String basePackage) {
        for (BeanDefinition beanDefinition : domainEventScanner.findCandidateComponents(basePackage)) {
            try {
                Class<? extends DomainEvent> domainEventClass = (Class<? extends DomainEvent>) Class.forName(beanDefinition.getBeanClassName());
                DomainEventInfo domainEventInfo = domainEventClass.getAnnotation(DomainEventInfo.class);
                TreeMap<Integer, DomainEventType<? extends DomainEvent>> versionMap = domainEventClasses.computeIfAbsent(domainEventInfo.type(), k -> new TreeMap<>());
                versionMap.put(domainEventInfo.version(), new DomainEventType<>(domainEventInfo.type(), domainEventInfo.version(), domainEventClass, false, true, ErrorEvent.class.isAssignableFrom(domainEventClass)));
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
        if(processState == RUNNING) {
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
            } catch(IOException | RestClientException e) {
                // TODO: make failing on these errors optional (there might not be proper validation however)
                logger.error("Exception while loading Command (JSON)Schemas from SchemaRegistry", e);
                processState = SHUTTING_DOWN;
            }
        } else if(processState == INITIALIZING) {
            try {
                Map<TopicPartition,Long> endOffsets = controlConsumer.endOffsets(singletonList(AKCES_CONTROL_PARTITION));
                ConsumerRecords<String, AkcesControlRecord> consumerRecords = controlConsumer.poll(Duration.ZERO);
                processControlRecords(consumerRecords);
                // stop condition
                if(consumerRecords.isEmpty() && endOffsets.getOrDefault(AKCES_CONTROL_PARTITION,0L) <= controlConsumer.position(AKCES_CONTROL_PARTITION)) {
                    processState = RUNNING;
                }
            } catch (WakeupException | InterruptException e) {
                // ignore
            } catch (KafkaException e) {
                // this is an unrecoverable exception
                logger.error("Unrecoverable exception in AkcesController", e);
                // drop out of the control loop, this will shut down all resources
                processState = SHUTTING_DOWN;
            } catch(IOException | RestClientException e) {
                // TODO: make failing on these errors optional (there might not be proper validation however)
                logger.error("Exception while loading Command (JSON)Schemas from SchemaRegistry", e);
                processState = SHUTTING_DOWN;
            }
        }
    }

    private void processControlRecords(ConsumerRecords<String, AkcesControlRecord> consumerRecords) throws RestClientException, IOException {
        if (!consumerRecords.isEmpty()) {
            for(ConsumerRecord<String, AkcesControlRecord> record : consumerRecords) {
                AkcesControlRecord controlRecord = record.value();
                if (controlRecord instanceof AggregateServiceRecord aggregateServiceRecord) {
                    logger.info("Discovered service: {}", aggregateServiceRecord.aggregateName());
                    aggregateServices.put(record.key(), aggregateServiceRecord);
                    registerSchemas(aggregateServiceRecord);
                } else {
                    logger.info("Received unknown AkcesControlRecord type: {}", controlRecord.getClass().getSimpleName());
                }
            }
        }
    }

    private void processCommands(Producer<String, ProtocolRecord> producer) {
        CommandRequest commandRequest = commandQueue.poll();
        if(commandRequest != null) {
            // start a transaction
            producer.beginTransaction();
            while(commandRequest != null) {
                final CompletableFuture<List<DomainEvent>> completableFuture = commandRequest.completableFuture();
                final CommandRecord commandRecord = commandRequest.commandRecord();
                final Class<? extends Command> commandClass = commandRequest.command().getClass();
                // create the ProducerRecord
                ProducerRecord<String, ProtocolRecord> producerRecord =
                        new ProducerRecord<>(
                                commandRequest.topic(),
                                resolvePartition(commandRequest.commandRecord.aggregateId()),
                                commandRequest.commandRecord.aggregateId(),
                                commandRequest.commandRecord);
                if (completableFuture != null) {
                    KafkaSender.send(producer, producerRecord, (metadata, exception) -> {
                        if (exception != null) {
                            completableFuture.completeExceptionally(new CommandSendingFailedException(commandClass, exception));
                        } else {
                            // completion will happen when we get the CommandResponseRecord
                            pendingCommandResponseMap.put(commandRecord.id(), new PendingCommandResponse(commandRecord, completableFuture));
                        }
                    });
                } else {
                    // just send the command
                    KafkaSender.send(producer, producerRecord);
                }

                commandRequest = commandQueue.poll();
            }
            producer.commitTransaction();
        }
    }

    // process command responses
    private void processCommandResponses(ConsumerRecords<String, ProtocolRecord> consumerRecords) {
        if(!consumerRecords.isEmpty()) {
            for(ConsumerRecord<String, ProtocolRecord> record : consumerRecords) {
                ProtocolRecord protocolRecord = record.value();
                if(protocolRecord instanceof CommandResponseRecord commandResponseRecord) {
                    PendingCommandResponse pendingCommandResponse = pendingCommandResponseMap.remove(commandResponseRecord.commandId());
                    if(pendingCommandResponse == null) {
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
                        } catch(IOException e) {
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
        // first validate the command with the registry
        AggregateServiceCommandType commandType = registerCommand(command);
        // then determine the topic to produce on
        String topic = resolveTopic(commandType.typeName(), commandType.version(), command);
        // then create a CommandRecord (this can cause an exception if the command is not serializable)
        CommandRecord commandRecord = new CommandRecord(
                tenantId,
                commandType.typeName(),
                commandType.version(),
                serialize(command),
                PayloadEncoding.JSON,
                command.getAggregateId(),
                correlationId != null ? correlationId : UUID.randomUUID().toString(), commandResponsePartition.toString());
        // then schedule it for execution
        CompletableFuture<List<DomainEvent>> completableFuture = new CompletableFuture<>();
        commandQueue.add(new CommandRequest(command, commandRecord, topic, completableFuture));
        // return the CompletableFuture
        return completableFuture;
    }

    @Override
    public void sendAndForget(@Nonnull String tenantId, @Nullable String correlationId, @Nonnull Command command) {
        // only allow when the process is in the RUNNING state
        checkRunning(command);
        // first validate the command with the registry
        AggregateServiceCommandType commandType = registerCommand(command);
        // then determine the topic to produce on
        String topic = resolveTopic(commandType.typeName(), commandType.version(), command);
        // then create a CommandRecord (this can cause an exception if the command is not serializable)
        CommandRecord commandRecord = new CommandRecord(
                tenantId,
                commandType.typeName(),
                commandType.version(),
                serialize(command),
                PayloadEncoding.JSON,
                command.getAggregateId(),
                correlationId != null ? correlationId : UUID.randomUUID().toString(), null);
        // then schedule it for execution
        commandQueue.add(new CommandRequest(command, commandRecord, topic, null));
    }

    @Override
    public void close() throws Exception {

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
        if(services.size() == 1) {
            return services.get(0).commandTopic();
        } else if(services.isEmpty()) {
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

    private void registerSchemas(AggregateServiceRecord aggregateServiceRecord) throws RestClientException, IOException {
        // process the commands to retrieve the schemas
        for (AggregateServiceCommandType commandType : aggregateServiceRecord.supportedCommands()) {
            // check if we already have the schema
            if (!commandSchemas.containsKey(commandType.typeName()) ||
                    !commandSchemas.get(commandType.typeName()).containsKey(commandType.version())) {
                // just register all versions at once
                List<ParsedSchema> registeredSchemas = schemaRegistryClient.getSchemas(commandType.schemaName(), false, false);
                // ensure we have an ordered list of schemas
                registeredSchemas.sort(Comparator.comparingInt(ParsedSchema::version));
                // ensure we have a Map
                commandSchemas.computeIfAbsent(commandType.typeName(), k -> new ConcurrentHashMap<>());
                Map<Integer, ParsedSchema> schemaMap = commandSchemas.get(commandType.typeName());
                for (ParsedSchema parsedSchema : registeredSchemas) {
                    schemaMap.put(schemaRegistryClient.getVersion(commandType.schemaName(), parsedSchema), parsedSchema);
                }
            }
        }
        // process the events that can be produced by this service
        for (AggregateServiceDomainEventType domainEventType : aggregateServiceRecord.producedEvents()) {
            // check if we already have the domainevent schema
            if (!domainEventSchemas.containsKey(domainEventType.typeName()) ||
                    !domainEventSchemas.get(domainEventType.typeName()).containsKey(domainEventType.version())) {
                // just register all versions at once
                List<ParsedSchema> registeredSchemas = schemaRegistryClient.getSchemas(domainEventType.schemaName(), false, false);
                // ensure we have an ordered list of schemas
                registeredSchemas.sort(Comparator.comparingInt(ParsedSchema::version));
                // ensure we have a Map
                domainEventSchemas.computeIfAbsent(domainEventType.typeName(), k -> new ConcurrentHashMap<>());
                Map<Integer, ParsedSchema> schemaMap = domainEventSchemas.get(domainEventType.typeName());
                for (ParsedSchema parsedSchema : registeredSchemas) {
                    schemaMap.put(schemaRegistryClient.getVersion(domainEventType.schemaName(), parsedSchema), parsedSchema);
                }
            }
        }

    }

    private AggregateServiceCommandType registerCommand(Command command) {
        // see if we already know the command class
        if(!commandTypes.containsKey(command.getClass())) {
            // we need to add it to the map
            CommandInfo commandInfo = command.getClass().getAnnotation(CommandInfo.class);
            if(commandInfo != null) {
                // fetch the metadata from the aggregateServices
                AggregateServiceCommandType commandType = resolveCommandType(commandInfo.type(), commandInfo.version());
                if(commandType != null) {
                    // see if we have a schema for this command
                    ParsedSchema schema = commandSchemas.getOrDefault(commandType.typeName(), Collections.emptyMap()).get(commandType.version());
                    if(schema != null) {
                        commandSchemasLookup.put(command.getClass(), schema);
                    } else {
                        throw new UnknownSchemaException(command.getClass(), "commands."+commandType.typeName());
                    }
                    // we have a match, add it to the map
                    commandTypes.put(command.getClass(), commandType);
                    // now we need to process the produced DomainEvents
                    // TODO: we only know this for the whole service, not for the specific command
                    for(AggregateServiceDomainEventType domainEventType : resolveAggregateService(commandType).producedEvents()) {
                        processDomainEvent(domainEventType);
                    }
                } else {
                    // the command is not known within the system, there is no way to route the command
                    throw new UnroutableCommandException(command.getClass());
                }
            } else {
                // a Command class must be annotated with @CommandInfo, this is a programmer error
                throw new IllegalArgumentException("Command class "+command.getClass().getName()+" is not annotated with @CommandInfo");
            }
        }
        // return the command type metadata
        return commandTypes.get(command.getClass());
    }

    private void processDomainEvent(AggregateServiceDomainEventType domainEventType) {
        // create a mapping for each domainevent type that can be return
    }

    private byte[] serialize(Command command) {
        try {
            // get the schema
            ParsedSchema schema = commandSchemasLookup.get(command.getClass());
            if(schema instanceof JsonSchema jsonSchema) {
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

    private DomainEvent deserialize(DomainEventRecord der,@Nullable byte[] encryptionKey) throws IOException {
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
        if(processState != RUNNING) {
            throw new CommandRefusedException(command.getClass(), processState);
        }
    }

    public boolean isRunning() {
        return processState == RUNNING;
    }

    private record CommandRequest(@Nonnull Command command, @Nonnull CommandRecord commandRecord, @Nonnull String topic, CompletableFuture<List<DomainEvent>> completableFuture) { }
    private record PendingCommandResponse(@Nonnull CommandRecord commandRecord, @Nonnull CompletableFuture<List<DomainEvent>> completableFuture) { }

}
