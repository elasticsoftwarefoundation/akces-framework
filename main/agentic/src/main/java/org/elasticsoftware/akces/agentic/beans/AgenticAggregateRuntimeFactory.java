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

package org.elasticsoftware.akces.agentic.beans;

import com.embabel.agent.core.AgentPlatform;
import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.agentic.runtime.AgenticCommandHandlerFunctionAdapter;
import org.elasticsoftware.akces.agentic.runtime.AgenticEventHandlerFunctionAdapter;
import org.elasticsoftware.akces.agentic.runtime.KafkaAgenticAggregateRuntime;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.aggregate.AgenticAggregate;
import org.elasticsoftware.akces.annotations.AgenticAggregateInfo;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.elasticsoftware.akces.annotations.CommandInfo;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.akces.kafka.KafkaAggregateRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.elasticsoftware.akces.agentic.AgenticAggregateRuntime.AGENT_TASK_ASSIGNED_TYPE;
import static org.elasticsoftware.akces.agentic.AgenticAggregateRuntime.ASSIGN_TASK_COMMAND_TYPE;
import static org.elasticsoftware.akces.agentic.AgenticAggregateRuntime.MEMORY_REVOKED_TYPE;
import static org.elasticsoftware.akces.agentic.AgenticAggregateRuntime.MEMORY_STORED_TYPE;

/**
 * Spring {@link FactoryBean} that creates a {@link AgenticAggregateRuntime} for an
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate} annotated with
 * {@link AgenticAggregateInfo}.
 *
 * <p>Analogous to
 * {@link org.elasticsoftware.akces.kafka.AggregateRuntimeFactory} but adapted for
 * the agentic module: agentic aggregates are never GDPR-keyed, never indexed, and
 * never have PII-annotated state.</p>
 *
 * <p>In addition to wiring standard command, event, and event-sourcing handler function
 * adapters, this factory also processes the three agentic annotation properties:
 * <ul>
 *   <li>{@code agentHandledCommands} — creates {@link AgenticCommandHandlerFunctionAdapter}
 *       instances registered as command handlers.</li>
 *   <li>{@code agentHandledEvents} — creates {@link AgenticEventHandlerFunctionAdapter}
 *       instances registered as external event handlers.</li>
 *   <li>{@code agentProducedErrors} — registers error event types for schema validation
 *       and service discovery.</li>
 * </ul>
 *
 * <p>Resolves the {@link AgentPlatform} from the {@link ApplicationContext} and fails
 * fast if it is not available — agentic aggregates cannot start without a configured
 * Embabel agent platform.</p>
 *
 * <p>When {@code agentHandledCommands} or {@code agentHandledEvents} are declared, the
 * factory creates adapter instances that resolve the matching agent at runtime from
 * the {@link AgentPlatform} by aggregate name. No eager bean lookup is performed at
 * startup — agent resolution is deferred to command/event processing time.</p>
 *
 * <p>Produces a {@link KafkaAgenticAggregateRuntime} wrapping the internally built
 * {@link KafkaAggregateRuntime}, adding memory-aware and agent-platform operations.</p>
 *
 * @param <S> the aggregate state type
 */
public class AgenticAggregateRuntimeFactory<S extends AggregateState>
        implements FactoryBean<AgenticAggregateRuntime>, ApplicationContextAware {

    private static final Logger logger =
            LoggerFactory.getLogger(AgenticAggregateRuntimeFactory.class);

    /** System error types added to every non-create command handler (including agent-handled). */
    private static final List<DomainEventType<?>> COMMAND_HANDLER_SYSTEM_ERRORS =
            AgenticAggregateBeanFactoryPostProcessor.COMMAND_HANDLER_SYSTEM_ERRORS;

    /** System error types added to every non-create event handler (including agent-handled). */
    private static final List<DomainEventType<?>> EVENT_HANDLER_SYSTEM_ERRORS =
            AgenticAggregateBeanFactoryPostProcessor.EVENT_HANDLER_SYSTEM_ERRORS;

    private ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final AgenticAggregate<S> aggregate;

    /**
     * Creates a new factory.
     *
     * @param objectMapper the Jackson {@link ObjectMapper} used for JSON schema generation
     * @param aggregate    the agentic aggregate instance whose handlers will be discovered
     */
    public AgenticAggregateRuntimeFactory(ObjectMapper objectMapper, AgenticAggregate<S> aggregate) {
        this.objectMapper = objectMapper;
        this.aggregate = aggregate;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public AgenticAggregateRuntime getObject() {
        AgenticAggregateInfo agenticInfo =
                aggregate.getClass().getAnnotation(AgenticAggregateInfo.class);
        if (agenticInfo == null) {
            throw new IllegalStateException(
                    "Class implementing AgenticAggregate must be annotated with @AgenticAggregateInfo");
        }

        // Resolve AgentPlatform — mandatory for all agentic aggregates.
        AgentPlatform agentPlatform = resolveAgentPlatform(agenticInfo.value());

        KafkaAggregateRuntime kafkaRuntime = createRuntime(agenticInfo, aggregate, agentPlatform);
        return new KafkaAgenticAggregateRuntime(
                kafkaRuntime, objectMapper, agenticInfo.stateClass(), agentPlatform, aggregate);
    }

    @Override
    public Class<?> getObjectType() {
        return AgenticAggregateRuntime.class;
    }

    /**
     * Resolves the {@link AgentPlatform} from the {@link ApplicationContext}.
     *
     * @param aggregateName the aggregate name (for error messages)
     * @return the resolved {@link AgentPlatform}; never {@code null}
     * @throws IllegalStateException if no {@link AgentPlatform} bean is available
     */
    private AgentPlatform resolveAgentPlatform(String aggregateName) {
        try {
            return applicationContext.getBean(AgentPlatform.class);
        } catch (BeansException e) {
            throw new IllegalStateException(
                    "AgentPlatform is required for agentic aggregate '" + aggregateName
                            + "' but was not found in the ApplicationContext. "
                            + "Please ensure embabel-agent-starter is configured.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private KafkaAggregateRuntime createRuntime(AgenticAggregateInfo agenticInfo,
                                                AgenticAggregate<S> aggregate,
                                                AgentPlatform agentPlatform) {
        KafkaAggregateRuntime.Builder runtimeBuilder = new KafkaAggregateRuntime.Builder();

        AggregateStateInfo stateInfo = agenticInfo.stateClass().getAnnotation(AggregateStateInfo.class);
        if (stateInfo == null) {
            throw new IllegalStateException(
                    "Agentic aggregate state class " + agenticInfo.stateClass().getName()
                            + " must be annotated with @AggregateStateInfo");
        }

        // Agentic aggregates: no GDPR, not indexed, no PIIData on state
        runtimeBuilder.setStateType(new AggregateStateType<>(
                        agenticInfo.value(),
                        stateInfo.version(),
                        agenticInfo.stateClass(),
                        false,   // generateGDPRKeyOnCreate – not applicable
                        false,   // indexed – singleton, not indexed
                        "",      // indexName – not applicable
                        false))  // shouldHandlePIIData – not applicable
                .setAggregateClass((Class<? extends Aggregate<?>>) aggregate.getClass())
                .setObjectMapper(objectMapper);

        // CommandHandlerFunctions (deterministic, from @CommandHandler methods)
        applicationContext.getBeansOfType(CommandHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    CommandType<?> type = adapter.getCommandType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setCommandCreateHandler(adapter).addCommand(type);
                    } else {
                        runtimeBuilder.addCommandHandler(type, adapter).addCommand(type);
                    }
                    for (Object produced : adapter.getProducedDomainEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) produced);
                    }
                    for (Object error : adapter.getErrorEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) error);
                    }
                });

        // EventHandlerFunctions (external domain events — no EventBridgeHandlers for agentic)
        applicationContext.getBeansOfType(EventHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setEventCreateHandler(adapter).addDomainEvent(type);
                    } else {
                        runtimeBuilder.addExternalEventHandler(type, adapter).addDomainEvent(type);
                    }
                    for (Object produced : adapter.getProducedDomainEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) produced);
                    }
                    for (Object error : adapter.getErrorEventTypes()) {
                        runtimeBuilder.addDomainEvent((DomainEventType<?>) error);
                    }
                });

        // EventSourcingHandlerFunctions
        applicationContext.getBeansOfType(EventSourcingHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setEventSourcingCreateHandler(adapter).addDomainEvent(type);
                    } else {
                        runtimeBuilder.addEventSourcingHandler(type, adapter).addDomainEvent(type);
                    }
                });

        // UpcastingHandlerFunctions
        applicationContext.getBeansOfType(UpcastingHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    if (adapter.getInputType() instanceof AggregateStateType<?> stateType) {
                        runtimeBuilder.addStateUpcastingHandler(stateType, adapter);
                    } else if (adapter.getInputType() instanceof DomainEventType<?> eventType) {
                        runtimeBuilder.addEventUpcastingHandler(eventType, adapter)
                                .addDomainEvent(eventType);
                    }
                });

        // Register built-in event-sourcing handlers for memory management and task assignment.
        // These handle the framework-owned MemoryStored, MemoryRevoked, and AgentTaskAssigned
        // events, using a unified single-dispatch handler.
        runtimeBuilder
                .addEventSourcingHandler(MEMORY_STORED_TYPE, KafkaAgenticAggregateRuntime::handleBuiltInEvent)
                .addDomainEvent(MEMORY_STORED_TYPE);
        runtimeBuilder
                .addEventSourcingHandler(MEMORY_REVOKED_TYPE, KafkaAgenticAggregateRuntime::handleBuiltInEvent)
                .addDomainEvent(MEMORY_REVOKED_TYPE);
        runtimeBuilder
                .addEventSourcingHandler(AGENT_TASK_ASSIGNED_TYPE, KafkaAgenticAggregateRuntime::handleBuiltInEvent)
                .addDomainEvent(AGENT_TASK_ASSIGNED_TYPE);

        // Register built-in AssignTask command handler using a single-dispatch handler on
        // KafkaAgenticAggregateRuntime. The handler resolves the agent from the platform,
        // creates an AgentProcess, and emits AgentTaskAssignedEvent.
        runtimeBuilder
                .addCommandHandler(ASSIGN_TASK_COMMAND_TYPE,
                        KafkaAgenticAggregateRuntime.builtInCommandHandler(agentPlatform, agenticInfo.value()))
                .addCommand(ASSIGN_TASK_COMMAND_TYPE);

        // Collect agent-produced error types for registration and inclusion in adapters.
        List<DomainEventType<?>> agentProducedErrorTypes =
                buildAgentProducedErrorTypes(agenticInfo.agentProducedErrors());
        agentProducedErrorTypes.forEach(runtimeBuilder::addDomainEvent);

        // Process agentHandledCommands — register AgenticCommandHandlerFunctionAdapter
        for (Class<? extends Command> commandClass : agenticInfo.agentHandledCommands()) {
            processAgentHandledCommand(
                    commandClass, aggregate, agenticInfo.value(), agentPlatform,
                    agentProducedErrorTypes, runtimeBuilder);
        }

        // Process agentHandledEvents — register AgenticEventHandlerFunctionAdapter
        for (Class<? extends DomainEvent> eventClass : agenticInfo.agentHandledEvents()) {
            processAgentHandledEvent(
                    eventClass, aggregate, agenticInfo.value(), agentPlatform,
                    agentProducedErrorTypes, runtimeBuilder);
        }

        return runtimeBuilder.validateAndBuild();
    }

    /**
     * Builds the list of {@link DomainEventType} entries for all classes declared in
     * {@code agentProducedErrors}.
     *
     * @param errorClasses the error event classes from the annotation
     * @return a list of {@code DomainEventType(error=true)} entries
     * @throws IllegalArgumentException if any class is not annotated with {@code @DomainEventInfo}
     *                                  or does not implement {@link ErrorEvent}
     */
    private List<DomainEventType<?>> buildAgentProducedErrorTypes(
            Class<? extends ErrorEvent>[] errorClasses) {
        List<DomainEventType<?>> result = new ArrayList<>(errorClasses.length);
        for (Class<? extends ErrorEvent> errorClass : errorClasses) {
            DomainEventInfo info = errorClass.getAnnotation(DomainEventInfo.class);
            if (info == null) {
                throw new IllegalArgumentException(
                        "Agent-produced error class " + errorClass.getName()
                                + " must be annotated with @DomainEventInfo");
            }
            result.add(new DomainEventType<>(
                    info.type(), info.version(), errorClass,
                    false,  // create
                    false,  // external
                    true,   // error
                    false)); // piiData
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Validates and registers an agent-handled command, creating an
     * {@link AgenticCommandHandlerFunctionAdapter} and registering it with the runtime builder.
     *
     * @param commandClass           the command class to register
     * @param aggregate              the owning aggregate instance
     * @param aggregateName          the aggregate name used for agent inference at runtime
     * @param agentPlatform          the Embabel platform
     * @param agentProducedErrors    the error types the agent may produce
     * @param runtimeBuilder         the runtime builder to register with
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void processAgentHandledCommand(
            Class<? extends Command> commandClass,
            AgenticAggregate<S> aggregate,
            String aggregateName,
            AgentPlatform agentPlatform,
            List<DomainEventType<?>> agentProducedErrors,
            KafkaAggregateRuntime.Builder runtimeBuilder) {

        CommandInfo commandInfo = commandClass.getAnnotation(CommandInfo.class);
        if (commandInfo == null) {
            throw new IllegalArgumentException(
                    "Agent-handled command class " + commandClass.getName()
                            + " must be annotated with @CommandInfo");
        }

        // Agent-handled commands always have create=false (design constraint).
        CommandType commandType = new CommandType<>(
                commandInfo.type(), commandInfo.version(), commandClass,
                false,  // create — agent-handled commands cannot create state
                false,  // external
                false); // piiData

        // Error types: system errors + agent-produced errors
        List<DomainEventType<?>> errorTypes = new ArrayList<>(COMMAND_HANDLER_SYSTEM_ERRORS);
        errorTypes.addAll(agentProducedErrors);

        AgenticCommandHandlerFunctionAdapter adapter = new AgenticCommandHandlerFunctionAdapter<>(
                (AgenticAggregate) aggregate,
                aggregateName,
                commandType,
                agentPlatform,
                (List) List.of(),       // producedDomainEventTypes: empty — events are registered via EventSourcingHandler adapters
                (List) errorTypes,
                // TODO: wire aggregateServicesSupplier from AkcesAgenticAggregateController (Phase 3)
                Collections::emptyList);

        runtimeBuilder.addCommandHandler(commandType, adapter).addCommand(commandType);
        errorTypes.forEach(runtimeBuilder::addDomainEvent);

        logger.debug("Registered agent-handled command {} v{} for aggregate {}",
                commandInfo.type(), commandInfo.version(),
                aggregate.getClass().getSimpleName());
    }

    /**
     * Validates and registers an agent-handled external event, creating an
     * {@link AgenticEventHandlerFunctionAdapter} and registering it with the runtime builder.
     *
     * @param eventClass          the external domain event class to register
     * @param aggregate           the owning aggregate instance
     * @param aggregateName       the aggregate name used for agent inference at runtime
     * @param agentPlatform       the Embabel platform
     * @param agentProducedErrors the error types the agent may produce
     * @param runtimeBuilder      the runtime builder to register with
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void processAgentHandledEvent(
            Class<? extends DomainEvent> eventClass,
            AgenticAggregate<S> aggregate,
            String aggregateName,
            AgentPlatform agentPlatform,
            List<DomainEventType<?>> agentProducedErrors,
            KafkaAggregateRuntime.Builder runtimeBuilder) {

        if (ErrorEvent.class.isAssignableFrom(eventClass)) {
            throw new IllegalArgumentException(
                    "Agent-handled event class " + eventClass.getName()
                            + " must not implement ErrorEvent. "
                            + "Error events should be declared in agentProducedErrors instead.");
        }

        DomainEventInfo eventInfo = eventClass.getAnnotation(DomainEventInfo.class);
        if (eventInfo == null) {
            throw new IllegalArgumentException(
                    "Agent-handled event class " + eventClass.getName()
                            + " must be annotated with @DomainEventInfo");
        }

        DomainEventType eventType = new DomainEventType<>(
                eventInfo.type(), eventInfo.version(), eventClass,
                false,  // create
                true,   // external — agent-handled events are always external
                false,  // error
                false); // piiData

        // Error types: system errors + agent-produced errors
        List<DomainEventType<?>> errorTypes = new ArrayList<>(EVENT_HANDLER_SYSTEM_ERRORS);
        errorTypes.addAll(agentProducedErrors);

        AgenticEventHandlerFunctionAdapter adapter = new AgenticEventHandlerFunctionAdapter<>(
                (AgenticAggregate) aggregate,
                aggregateName,
                eventType,
                agentPlatform,
                (List) List.of(),       // producedDomainEventTypes: empty — events are registered via EventSourcingHandler adapters
                (List) errorTypes,
                // TODO: wire aggregateServicesSupplier from AkcesAgenticAggregateController (Phase 3)
                Collections::emptyList);

        runtimeBuilder.addExternalEventHandler(eventType, adapter).addDomainEvent(eventType);
        errorTypes.forEach(runtimeBuilder::addDomainEvent);

        logger.debug("Registered agent-handled external event {} v{} for aggregate {}",
                eventInfo.type(), eventInfo.version(),
                aggregate.getClass().getSimpleName());
    }
}
