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

package org.elasticsoftware.akces.beans;

import org.elasticsoftware.akces.aggregate.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AggregateValidator {
    private final Class<? extends Aggregate<?>> aggregateClass;
    private final Class<? extends AggregateState>  stateClass;
    private final List<CommandType<?>> commandHandlers = new ArrayList<>();
    private final List<DomainEventType<?>> eventHandlers = new ArrayList<>();
    private final List<DomainEventType<?>> eventSourcingHandlers = new ArrayList<>();
    private final List<DomainEventType<?>> eventBridgeHandlers = new ArrayList<>();
    private final List<UpcastingHandler> eventUpcastingHandlers = new ArrayList<>();
    private final List<UpcastingHandler> stateUpcastingHandlers = new ArrayList<>();
    private final Set<DomainEventType<?>> producedDomainEventTypes = new HashSet<>();
    private final Set<DomainEventType<?>> errorEventTypes = new HashSet<>();

    public AggregateValidator(Class<? extends Aggregate<?>> aggregateClass, Class<? extends AggregateState> stateClass) {
        this.aggregateClass = aggregateClass;
        this.stateClass = stateClass;
    }

    public void validate() {
        ensureCreateHandler();
        ensureCreateEvent();
        validateCommandHandlers();
        validateEventHandlers();
        validateEventSourcingHandlers();
        validateEventUpcastingHandlers();
        validateStateUpcastingHandlers();
        validateEventBridgeHandlers();
    }

    private void ensureCreateHandler() {
        if (commandHandlers.stream().noneMatch(CommandType::create) && eventHandlers.stream().noneMatch(DomainEventType::create)) {
            throw new IllegalStateException("No create handler registered for aggregate " + aggregateClass.getName());
        }

        // we should have at most one create handler for commands and one for external events
        if (commandHandlers.stream().filter(CommandType::create).count() > 1) {
            throw new IllegalStateException("Multiple command create handlers registered for aggregate " + aggregateClass.getName());
        }
        if (eventHandlers.stream().filter(DomainEventType::create).count() > 1) {
            throw new IllegalStateException("Multiple event create handlers registered for aggregate " + aggregateClass.getName());
        }
    }

    private void ensureCreateEvent() {
        // we should have exactly one create event in the produced events
        if (producedDomainEventTypes.stream().filter(DomainEventType::create).count() != 1) {
            throw new IllegalStateException("Exactly one create event should be produced by aggregate " + aggregateClass.getName());
        }
    }

    private void validateCommandHandlers() {
        // Detect duplicate command handlers
        Set<String> seenCommandTypes = new HashSet<>();
        for (CommandType<?> commandType : commandHandlers) {
            String commandKey = commandType.typeName() + "_v" + commandType.version();
            if (!seenCommandTypes.add(commandKey)) {
                throw new IllegalStateException("Duplicate command handler for command " +
                        commandType.typeName() + " version " + commandType.version() +
                        " in aggregate " + aggregateClass.getName());
            }
        }
    }

    private void validateEventHandlers() {
        // Detect duplicate event handlers
        Set<String> seenEventTypes = new HashSet<>();
        for (DomainEventType<?> eventType : eventHandlers) {
            String eventKey = eventType.typeName() + "_v" + eventType.version();
            if (!seenEventTypes.add(eventKey)) {
                throw new IllegalStateException("Duplicate event handler for event " +
                        eventType.typeName() + " version " + eventType.version() +
                        " in aggregate " + aggregateClass.getName());
            }
        }
    }

    private void validateEventSourcingHandlers() {
        // we should have at least one event sourcing handler (for the create event)
        if (eventSourcingHandlers.isEmpty()) {
            throw new IllegalStateException("No event sourcing handlers registered for aggregate " + aggregateClass.getName());
        }

        // Detect duplicate event sourcing handlers
        Set<String> seenEventTypes = new HashSet<>();
        for (DomainEventType<?> eventType : eventSourcingHandlers) {
            String eventKey = eventType.typeName() + "_v" + eventType.version();
            if (!seenEventTypes.add(eventKey)) {
                throw new IllegalStateException("Duplicate event sourcing handler for event " +
                        eventType.typeName() + " version " + eventType.version() +
                        " in aggregate " + aggregateClass.getName());
            }
        }

        // ensure create event is handled
        if (eventSourcingHandlers.stream().noneMatch(DomainEventType::create)) {
            throw new IllegalStateException("No event sourcing handler for create event in aggregate " + aggregateClass.getName());
        }
        // ensure all produced events have either a handler or an upcasting handler
        for (DomainEventType<?> producedEventType : producedDomainEventTypes) {
            if (eventSourcingHandlers.stream().noneMatch(h -> h.typeName().equals(producedEventType.typeName()) &&
                    h.version() == producedEventType.version()) &&
                    eventUpcastingHandlers.stream().noneMatch(h -> h.inputType().typeName().equals(producedEventType.typeName()) &&
                            h.inputType().version() == producedEventType.version())) {
                throw new IllegalStateException("No event sourcing handler or upcasting handler for produced event " +
                        producedEventType.typeName() + " version " + producedEventType.version() +
                        " in aggregate " + aggregateClass.getName());
            }
        }

    }

    private void validateEventBridgeHandlers() {
        // Event bridge handlers are optional

        // Detect duplicate event bridge handlers
        Set<String> seenEventTypes = new HashSet<>();
        for (DomainEventType<?> eventType : eventBridgeHandlers) {
            String eventKey = eventType.typeName() + "_v" + eventType.version();
            if (!seenEventTypes.add(eventKey)) {
                throw new IllegalStateException("Duplicate event bridge handler for event " +
                        eventType.typeName() + " version " + eventType.version() +
                        " in aggregate " + aggregateClass.getName());
            }
        }
    }

    private void validateEventUpcastingHandlers() {
        // Validate optional event upcasting handlers
        for (UpcastingHandler handler : eventUpcastingHandlers) {
            DomainEventType<?> inputType = (DomainEventType<?>) handler.inputType();
            DomainEventType<?> outputType = (DomainEventType<?>) handler.outputType();

            // Verify type consistency
            if (!inputType.typeName().equals(outputType.typeName())) {
                throw new IllegalArgumentException("Input event type " + inputType.typeName() +
                        " does not match output event type " + outputType.typeName());
            }

            // Verify version increment
            if (outputType.version() - inputType.version() != 1) {
                throw new IllegalArgumentException("Output event version " + outputType.version() +
                        " must be one greater than input event version " + inputType.version());
            }

            // there should either be an event sourcing handler or another upcasting handler for the input event
            if (eventSourcingHandlers.stream().noneMatch(h -> h.typeName().equals(inputType.typeName()) &&
                    h.version() == inputType.version()) &&
                    eventUpcastingHandlers.stream().noneMatch(h -> h.inputType().typeName().equals(inputType.typeName()) &&
                            h.inputType().version() == inputType.version())) {
                throw new IllegalStateException("No event sourcing handler or upcasting handler for input event " +
                        inputType.typeName() + " version " + inputType.version() +
                        " in aggregate " + aggregateClass.getName());
            }
        }
    }

    private void validateStateUpcastingHandlers() {
        // Validate optinal state upcasting handlers
        for (UpcastingHandler handler : stateUpcastingHandlers) {
            AggregateStateType<?> inputType = (AggregateStateType<?>) handler.inputType();
            AggregateStateType<?> outputType = (AggregateStateType<?>) handler.outputType();

            // Verify type consistency
            if (!inputType.typeName().equals(outputType.typeName())) {
                throw new IllegalArgumentException("Input state type " + inputType.typeName() +
                        " does not match output state type " + outputType.typeName());
            }

            // Verify version increment
            if (outputType.version() - inputType.version() != 1) {
                throw new IllegalArgumentException("Output state version " + outputType.version() +
                        " must be one greater than input state version " + inputType.version());
            }

            // the output state class either equals the class or another upcasting handler for the output state
            if (!outputType.typeClass().equals(stateClass) &&
                    stateUpcastingHandlers.stream().noneMatch(h -> h.outputType().typeName().equals(outputType.typeName()) &&
                            h.outputType().version() == outputType.version())) {
                throw new IllegalStateException("No upcasting handler for output state " +
                        outputType.typeName() + " version " + outputType.version() +
                        " in aggregate " + aggregateClass.getName());
            }
        }
    }

    public void detectCommandHandler(CommandType<?> commandType,
                                     List<DomainEventType<?>> producedDomainEventTypes,
                                     List<DomainEventType<?>> errorEventTypes) {
        commandHandlers.add(commandType);
        this.producedDomainEventTypes.addAll(producedDomainEventTypes);
        this.errorEventTypes.addAll(errorEventTypes);
    }

    public void detectEventHandler(DomainEventType<?> inputEventType,
                                   List<DomainEventType<?>> producedDomainEventTypes,
                                   List<DomainEventType<?>> errorEventTypes) {
        eventHandlers.add(inputEventType);
        this.producedDomainEventTypes.addAll(producedDomainEventTypes);
        this.errorEventTypes.addAll(errorEventTypes);
    }

    public void detectEventSourcingHandler(DomainEventType<?> domainEventType) {
        eventSourcingHandlers.add(domainEventType);
    }

    public void detectEventBridgeHandler(DomainEventType<?> inputEventType) {
        eventBridgeHandlers.add(inputEventType);
    }

    public void detectUpcastingHandler(DomainEventType<?> inputEventType, DomainEventType<?> outputEventType) {
        eventUpcastingHandlers.add(new UpcastingHandler(inputEventType, outputEventType));
    }

    public void detectUpcastingHandler(AggregateStateType<?> inputStateType, AggregateStateType<?> outputStateType) {
        stateUpcastingHandlers.add(new UpcastingHandler(inputStateType, outputStateType));
    }

    private record UpcastingHandler(ProtocolRecordType<?> inputType, ProtocolRecordType<?> outputType) {
    }
}
