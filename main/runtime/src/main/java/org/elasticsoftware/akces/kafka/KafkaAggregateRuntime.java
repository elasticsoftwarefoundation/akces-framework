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

package org.elasticsoftware.akces.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.json.JsonSchema;
import org.apache.kafka.common.errors.SerializationException;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils;
import org.elasticsoftware.akces.protocol.AggregateStateRecord;
import org.elasticsoftware.akces.protocol.CommandRecord;
import org.elasticsoftware.akces.protocol.DomainEventRecord;
import org.elasticsoftware.akces.protocol.PayloadEncoding;
import org.elasticsoftware.akces.schemas.KafkaSchemaRegistry;
import org.elasticsoftware.akces.schemas.SchemaException;
import org.everit.json.schema.ValidationException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KafkaAggregateRuntime extends AggregateRuntimeBase {
    private final KafkaSchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends DomainEvent>, JsonSchema> domainEventSchemas = new HashMap<>();
    private final Map<Class<? extends Command>, JsonSchema> commandSchemas = new HashMap<>();

    private KafkaAggregateRuntime(KafkaSchemaRegistry schemaRegistry,
                                  ObjectMapper objectMapper,
                                  AggregateStateType<?> stateType,
                                  Class<? extends Aggregate> aggregateClass,
                                  CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler,
                                  EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler,
                                  EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler,
                                  Map<Class<?>, DomainEventType<?>> domainEvents,
                                  Map<String, List<CommandType<?>>> commandTypes,
                                  Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers,
                                  Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers,
                                  Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers,
                                  Map<DomainEventType<?>, EventBridgeHandlerFunction<AggregateState, DomainEvent>> eventBridgeHandlers,
                                  boolean generateGDPRKeyOnCreate,
                                  boolean shouldHandlePIIData) {
        super(stateType,
                aggregateClass,
                commandCreateHandler,
                eventCreateHandler,
                createStateHandler,
                domainEvents,
                commandTypes,
                commandHandlers,
                eventHandlers,
                eventSourcingHandlers,
                eventBridgeHandlers,
                generateGDPRKeyOnCreate,
                shouldHandlePIIData);
        this.schemaRegistry = schemaRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void registerAndValidate(DomainEventType<?> domainEventType, boolean forceRegisterOnIncompatible) throws SchemaException {
        // generate the local schema version
        JsonSchema localSchema = schemaRegistry.registerAndValidate(domainEventType, forceRegisterOnIncompatible);
        // schema is fine, add to map
        domainEventSchemas.put(domainEventType.typeClass(), localSchema);
    }

    @Override
    public void registerAndValidate(CommandType<?> commandType,  boolean forceRegisterOnIncompatible) throws SchemaException {
        if (!commandSchemas.containsKey(commandType.typeClass())) {
            JsonSchema localSchema = schemaRegistry.registerAndValidate(commandType, forceRegisterOnIncompatible);
            commandSchemas.put(commandType.typeClass(), localSchema);
            if (commandType.external()) addCommand(commandType);
        }
    }

    @Override
    public Command materialize(CommandType<?> type, CommandRecord commandRecord) throws IOException {
        return objectMapper.readValue(commandRecord.payload(), type.typeClass());
    }

    @Override
    protected DomainEvent materialize(DomainEventType<?> domainEventType, DomainEventRecord eventRecord) throws IOException {
        return objectMapper.readValue(eventRecord.payload(), domainEventType.typeClass());
    }

    @Override
    protected AggregateState materialize(AggregateStateRecord stateRecord) throws IOException {
        return objectMapper.readValue(stateRecord.payload(), getAggregateStateType(stateRecord).typeClass());
    }

    @Override
    protected byte[] serialize(AggregateState state) throws IOException {
        return objectMapper.writeValueAsBytes(state);
    }

    @Override
    protected byte[] serialize(DomainEvent domainEvent) throws SerializationException {
        JsonNode jsonNode = objectMapper.convertValue(domainEvent, JsonNode.class);
        try {
            domainEventSchemas.get(domainEvent.getClass()).validate(jsonNode);
            return objectMapper.writeValueAsBytes(jsonNode);
        } catch (ValidationException e) {
            throw new SerializationException("Validation Failed while Serializing DomainEventClass " + domainEvent.getClass().getName(), e);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Serialization Failed while Serializing DomainEventClass " + domainEvent.getClass().getName(), e);
        }
    }

    @Override
    public byte[] serialize(Command command) throws SerializationException {
        JsonNode jsonNode = objectMapper.convertValue(command, JsonNode.class);
        try {
            commandSchemas.get(command.getClass()).validate(jsonNode);
            return objectMapper.writeValueAsBytes(command);
        } catch (ValidationException e) {
            throw new SerializationException("Validation Failed while Serializing CommandClass " + command.getClass().getName(), e);
        } catch (JsonProcessingException e) {
            throw new SerializationException("Serialization Failed while Serializing CommandClass " + command.getClass().getName(), e);
        }

    }

    @Override
    protected PayloadEncoding getEncoding(CommandType<?> type) {
        return PayloadEncoding.JSON;
    }

    @Override
    protected PayloadEncoding getEncoding(DomainEventType<?> type) {
        return PayloadEncoding.JSON;
    }

    @Override
    protected PayloadEncoding getEncoding(AggregateStateType<?> type) {
        return PayloadEncoding.JSON;
    }

    public static class Builder {
        private KafkaSchemaRegistry schemaRegistry;
        private ObjectMapper objectMapper;
        private AggregateStateType<?> stateType;
        private Class<? extends Aggregate> aggregateClass;
        private CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler;
        private EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler;
        private EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler;
        private Map<Class<?>, DomainEventType<?>> domainEvents = new HashMap<>();
        private Map<String, List<CommandType<?>>> commandTypes = new HashMap<>();
        private Map<CommandType<?>, CommandHandlerFunction<AggregateState, Command, DomainEvent>> commandHandlers = new HashMap<>();
        private Map<DomainEventType<?>, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent>> eventHandlers = new HashMap<>();
        private Map<DomainEventType<?>, EventSourcingHandlerFunction<AggregateState, DomainEvent>> eventSourcingHandlers = new HashMap<>();
        private Map<DomainEventType<?>, EventBridgeHandlerFunction<AggregateState, DomainEvent>> eventBridgeHandlers = new HashMap<>();
        private boolean generateGDPRKeyOnCreate = false;

        public Builder setSchemaRegistry(KafkaSchemaRegistry schemaRegistry) {
            this.schemaRegistry = schemaRegistry;
            return this;
        }

        public Builder setObjectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder setStateType(AggregateStateType<?> stateType) {
            this.stateType = stateType;
            return this;
        }

        public Builder setAggregateClass(Class<? extends Aggregate> aggregateClass) {
            this.aggregateClass = aggregateClass;
            return this;
        }

        public Builder setCommandCreateHandler(CommandHandlerFunction<AggregateState, Command, DomainEvent> commandCreateHandler) {
            this.commandCreateHandler = commandCreateHandler;
            return this;
        }

        public Builder setEventCreateHandler(EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventCreateHandler) {
            this.eventCreateHandler = eventCreateHandler;
            return this;
        }

        public Builder setEventSourcingCreateHandler(EventSourcingHandlerFunction<AggregateState, DomainEvent> createStateHandler) {
            this.createStateHandler = createStateHandler;
            return this;
        }

        public Builder addDomainEvent(DomainEventType<?> domainEvent) {
            this.domainEvents.put(domainEvent.typeClass(), domainEvent);
            return this;
        }

        public Builder addCommand(CommandType<?> commandType) {
            this.commandTypes.computeIfAbsent(commandType.typeName(), s -> new ArrayList<>()).add(commandType);
            return this;
        }

        public Builder addCommandHandler(CommandType<?> commandType, CommandHandlerFunction<AggregateState, Command, DomainEvent> commandHandler) {
            this.commandHandlers.put(commandType, commandHandler);
            return this;
        }

        public Builder addExternalEventHandler(DomainEventType<?> eventType, EventHandlerFunction<AggregateState, DomainEvent, DomainEvent> eventHandler) {
            this.eventHandlers.put(eventType, eventHandler);
            return this;
        }

        public Builder addEventSourcingHandler(DomainEventType<?> eventType, EventSourcingHandlerFunction<AggregateState, DomainEvent> eventSourcingHandler) {
            this.eventSourcingHandlers.put(eventType, eventSourcingHandler);
            return this;
        }

        public Builder addEventBridgeHandler(DomainEventType<?> eventType, EventBridgeHandlerFunction<AggregateState, DomainEvent> eventBridgeHandler) {
            this.eventBridgeHandlers.put(eventType, eventBridgeHandler);
            return this;
        }


        public Builder setGenerateGDPRKeyOnCreate(boolean generateGDPRKeyOnCreate) {
            this.generateGDPRKeyOnCreate = generateGDPRKeyOnCreate;
            return this;
        }

        public KafkaAggregateRuntime build() {
            // see if we have a command and/or domain event with PIIData
            final boolean shouldHandlePIIData = domainEvents.values().stream().map(DomainEventType::typeClass)
                    .anyMatch(GDPRAnnotationUtils::hasPIIDataAnnotation) ||
                    commandTypes.values().stream().flatMap(List::stream).map(CommandType::typeClass)
                            .anyMatch(GDPRAnnotationUtils::hasPIIDataAnnotation);

            return new KafkaAggregateRuntime(
                    schemaRegistry,
                    objectMapper,
                    stateType,
                    aggregateClass,
                    commandCreateHandler,
                    eventCreateHandler,
                    createStateHandler,
                    domainEvents,
                    commandTypes,
                    commandHandlers,
                    eventHandlers,
                    eventSourcingHandlers,
                    eventBridgeHandlers,
                    generateGDPRKeyOnCreate,
                    shouldHandlePIIData);
        }
    }
}
