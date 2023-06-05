/*
 * Copyright 2022 - 2023 The Original Authors
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.control.AggregateServiceCommandType;
import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AkcesControlRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.elasticsoftware.akces.protocol.ProtocolRecord;
import org.elasticsoftware.akces.util.HostUtils;
import org.elasticsoftware.akces.util.KafkaSender;
import org.everit.json.schema.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

public class AkcesClientController extends Thread implements AutoCloseable, AkcesClient {
    private static final Logger logger = LoggerFactory.getLogger(AkcesClientController.class);
    private final ProducerFactory<String, ProtocolRecord> producerFactory;
    private final ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory;
    private final KafkaAdminOperations kafkaAdmin;
    private final HashFunction hashFunction = Hashing.murmur3_32_fixed();
    private Integer partitions = null;
    private volatile AkcesClientControllerState processState = INITIALIZING;
    private final Map<String, AggregateServiceRecord> aggregateServices = new ConcurrentHashMap<>();
    private final BlockingQueue<CommandRequest> commandQueue = new LinkedBlockingQueue<>();
    private final SchemaRegistryClient schemaRegistryClient;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends Command>, AggregateServiceCommandType> commandTypes = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer,ParsedSchema>> commandSchemas = new ConcurrentHashMap<>();
    private final Map<Class<? extends Command>,ParsedSchema> commandSchemasLookup = new ConcurrentHashMap<>();
    private static final TopicPartition AKCES_CONTROL_PARTITION = new TopicPartition("Akces-Control",0);

    public AkcesClientController(ProducerFactory<String, ProtocolRecord> producerFactory,
                                 ConsumerFactory<String, AkcesControlRecord> controlRecordConsumerFactory,
                                 KafkaAdminOperations kafkaAdmin,
                                 SchemaRegistryClient schemaRegistryClient,
                                 ObjectMapper objectMapper) {
        super("AkcesClientController");
        this.producerFactory = producerFactory;
        this.controlRecordConsumerFactory = controlRecordConsumerFactory;
        this.kafkaAdmin = kafkaAdmin;
        this.schemaRegistryClient = schemaRegistryClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run() {
        try (final Consumer<String, AkcesControlRecord> controlConsumer = controlRecordConsumerFactory.createConsumer(
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
            while (processState != SHUTTING_DOWN) {
                process(controlConsumer, producer);
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

    private void process(Consumer<String, AkcesControlRecord> controlConsumer, Producer<String, ProtocolRecord> producer) {
        if(processState == RUNNING) {
            try {
                // load any updated control data
                ConsumerRecords<String, AkcesControlRecord> consumerRecords = controlConsumer.poll(Duration.ofMillis(1000));
                processControlRecords(consumerRecords);
                // process commands
                processCommands(producer);
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
                final CompletableFuture<String> completableFuture = commandRequest.completableFuture();
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
                            completableFuture.complete(commandRecord.correlationId());
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

    @Override
    public CompletionStage<String> send(@Nonnull String tenantId, @Nullable String correlationId,@Nonnull Command command) {
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
                correlationId != null ? correlationId : UUID.randomUUID().toString());
        // then schedule it for execution
        CompletableFuture<String> completableFuture = new CompletableFuture<>();
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
                correlationId != null ? correlationId : UUID.randomUUID().toString());
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

    private Integer resolvePartition(@Nonnull String aggregateId) {
        return Math.abs(hashFunction.hashString(aggregateId, UTF_8).asInt()) % partitions;
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

    private byte[] serialize(Command command) {
        try {
            // get the schema
            ParsedSchema schema = commandSchemasLookup.get(command.getClass());
            if(schema instanceof JsonSchema jsonSchema) {
                jsonSchema.validate(command);
            }
            return objectMapper.writeValueAsBytes(command);
        } catch (JsonProcessingException e) {
            throw new CommandSerializationException(command.getClass(), e);
        } catch (ValidationException e) {
            throw new CommandValidationException(command.getClass(), e);
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

    private record CommandRequest(@Nonnull Command command, @Nonnull CommandRecord commandRecord, @Nonnull String topic, CompletableFuture<String> completableFuture) { }

}
