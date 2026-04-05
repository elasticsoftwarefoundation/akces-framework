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

import org.elasticsoftware.akces.agentic.runtime.AgenticAggregatePartition;
import org.elasticsoftware.akces.agentic.runtime.AgenticAggregateRuntime;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.aggregate.AgenticAggregate;
import org.elasticsoftware.akces.annotations.*;
import org.elasticsoftware.akces.annotations.AgenticAggregateInfo;
import org.elasticsoftware.akces.beans.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.errors.AggregateAlreadyExistsErrorEvent;
import org.elasticsoftware.akces.errors.AggregateNotFoundErrorEvent;
import org.elasticsoftware.akces.errors.CommandExecutionErrorEvent;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanRegistrationExcludeFilter;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.context.ApplicationContextException;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

/**
 * Spring {@link BeanFactoryPostProcessor} that scans the application context for beans
 * annotated with {@link AgenticAggregateInfo}, processes their handler methods, and
 * registers the necessary runtime bean definitions dynamically.
 *
 * <p>This processor is the agentic equivalent of
 * {@link org.elasticsoftware.akces.beans.AggregateBeanFactoryPostProcessor}.
 * Key differences from the regular aggregate processor are:
 * <ul>
 *   <li>Scans for {@link AgenticAggregateInfo} instead of
 *       {@link org.elasticsoftware.akces.annotations.AggregateInfo}.</li>
 *   <li>{@link org.elasticsoftware.akces.annotations.EventBridgeHandler} methods are
 *       <strong>not</strong> supported — agentic aggregates listen to all partitions of
 *       external topics via the standard {@link EventHandler} mechanism.</li>
 *   <li>Registers {@link AgenticAggregateRuntimeFactory} instead of
 *       {@link org.elasticsoftware.akces.kafka.AggregateRuntimeFactory}.</li>
 *   <li>Registers {@link AgenticAggregatePartition} and {@link AgenticAggregateRuntime}
 *       beans when the agentic Kafka factory beans are available.</li>
 * </ul>
 */
public class AgenticAggregateBeanFactoryPostProcessor
        implements BeanFactoryPostProcessor, BeanFactoryInitializationAotProcessor, BeanRegistrationExcludeFilter {

    private static final Logger logger = LoggerFactory.getLogger(AgenticAggregateBeanFactoryPostProcessor.class);

    /** System error event types added to every create command handler. */
    public static final List<DomainEventType<? extends DomainEvent>> COMMAND_HANDLER_CREATE_SYSTEM_ERRORS = List.of(
            new DomainEventType<>("AggregateAlreadyExistsError", 1, AggregateAlreadyExistsErrorEvent.class, false, false, true, false),
            new DomainEventType<>("CommandExecutionError", 1, CommandExecutionErrorEvent.class, false, false, true, false)
    );
    /** System error event types added to every non-create command handler. */
    public static final List<DomainEventType<? extends DomainEvent>> COMMAND_HANDLER_SYSTEM_ERRORS = List.of(
            new DomainEventType<>("AggregateNotFoundError", 1, AggregateNotFoundErrorEvent.class, false, false, true, false),
            new DomainEventType<>("CommandExecutionError", 1, CommandExecutionErrorEvent.class, false, false, true, false)
    );
    /** System error event types added to every create event handler. */
    public static final List<DomainEventType<? extends DomainEvent>> EVENT_HANDLER_CREATE_SYSTEM_ERRORS = List.of(
            new DomainEventType<>("AggregateAlreadyExistsError", 1, AggregateAlreadyExistsErrorEvent.class, false, false, true, false)
    );
    /** System error event types added to every non-create event handler. */
    public static final List<DomainEventType<? extends DomainEvent>> EVENT_HANDLER_SYSTEM_ERRORS = List.of(
            new DomainEventType<>("AggregateNotFoundError", 1, AggregateNotFoundErrorEvent.class, false, false, true, false)
    );

    @Override
    @SuppressWarnings("unchecked")
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!(beanFactory instanceof BeanDefinitionRegistry bdr)) {
            throw new ApplicationContextException("BeanFactory is not a BeanDefinitionRegistry");
        }
        logger.info("Processing AgenticAggregate beans");
        Arrays.asList(beanFactory.getBeanNamesForAnnotation(AgenticAggregateInfo.class)).forEach(beanName -> {
            logger.info("Processing AgenticAggregate bean {}", beanName);
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            try {
                final Class<? extends AgenticAggregate<?>> aggregateClass =
                        (Class<? extends AgenticAggregate<?>>) Class.forName(bd.getBeanClassName());
                AgenticAggregateInfo agenticInfo = aggregateClass.getAnnotation(AgenticAggregateInfo.class);
                AggregateValidator validator = new AggregateValidator(aggregateClass, agenticInfo.stateClass());

                List<Method> commandHandlers = Arrays.stream(aggregateClass.getMethods())
                        .filter(m -> m.isAnnotationPresent(CommandHandler.class))
                        .toList();
                List<Method> eventHandlers = Arrays.stream(aggregateClass.getMethods())
                        .filter(m -> m.isAnnotationPresent(EventHandler.class))
                        .toList();
                List<Method> eventSourcingHandlers = Arrays.stream(aggregateClass.getMethods())
                        .filter(m -> m.isAnnotationPresent(EventSourcingHandler.class))
                        .toList();
                List<Method> upcastingHandlers = Arrays.stream(aggregateClass.getMethods())
                        .filter(m -> m.isAnnotationPresent(UpcastingHandler.class))
                        .toList();

                commandHandlers.forEach(m ->
                        processCommandHandler(beanName, agenticInfo.stateClass(), m, bdr, validator));
                eventHandlers.forEach(m ->
                        processEventHandler(beanName, agenticInfo.stateClass(), m, bdr, validator));
                eventSourcingHandlers.forEach(m ->
                        processEventSourcingHandler(beanName, agenticInfo.stateClass(), m, bdr, validator));
                upcastingHandlers.forEach(m ->
                        processUpcastingHandler(beanName, aggregateClass, m, bdr, validator));

                validator.validate();
            } catch (ClassNotFoundException e) {
                throw new ApplicationContextException("Unable to load class for bean " + beanName, e);
            } catch (IllegalStateException e) {
                throw new ApplicationContextException(
                        "Invalid AgenticAggregate configuration for bean " + beanName, e);
            }

            // Register the AgenticAggregateRuntimeFactory (FactoryBean producing AggregateRuntime)
            String objectMapperBeanName = beanFactory.getBeanNamesForType(ObjectMapper.class)[0];
            bdr.registerBeanDefinition(beanName + "AggregateRuntimeFactory",
                    BeanDefinitionBuilder.genericBeanDefinition(AgenticAggregateRuntimeFactory.class)
                            .addConstructorArgReference(objectMapperBeanName)
                            .addConstructorArgReference(beanName)
                            .getBeanDefinition());

            // Conditionally register partition and runtime beans when agentic Kafka factories are present
            if (beanFactory.containsBeanDefinition("agenticServiceConsumerFactory") &&
                    beanFactory.containsBeanDefinition("agenticServiceProducerFactory") &&
                    beanFactory.containsBeanDefinition("agenticServiceControlProducerFactory") &&
                    beanFactory.containsBeanDefinition("agenticServiceSchemaConsumerFactory") &&
                    beanFactory.containsBeanDefinition("agenticServiceSchemaProducerFactory") &&
                    beanFactory.containsBeanDefinition("agenticServiceAggregateStateRepositoryFactory")) {

                AgenticAggregateInfo agenticInfo = null;
                try {
                    agenticInfo = Class.forName(
                            beanFactory.getBeanDefinition(beanName).getBeanClassName())
                            .getAnnotation(AgenticAggregateInfo.class);
                } catch (ClassNotFoundException e) {
                    throw new ApplicationContextException("Unable to load class for bean " + beanName, e);
                }

                // AgenticAggregatePartition
                String partitionBeanName = beanName + "AgenticPartition";
                bdr.registerBeanDefinition(partitionBeanName,
                        BeanDefinitionBuilder.genericBeanDefinition(AgenticAggregatePartition.class)
                                .addConstructorArgReference("agenticServiceConsumerFactory")
                                .addConstructorArgReference("agenticServiceProducerFactory")
                                .addConstructorArgReference(beanName + "AggregateRuntimeFactory")
                                .addConstructorArgReference("agenticServiceAggregateStateRepositoryFactory")
                                .addConstructorArgValue(agenticInfo.maxMemories())
                                .getBeanDefinition());

                // AgenticAggregateRuntime (Thread — started via init method, closed via destroy method)
                bdr.registerBeanDefinition(beanName + "AgenticRuntime",
                        BeanDefinitionBuilder.genericBeanDefinition(AgenticAggregateRuntime.class)
                                .addConstructorArgReference("agenticServiceSchemaConsumerFactory")
                                .addConstructorArgReference("agenticServiceSchemaProducerFactory")
                                .addConstructorArgReference("agenticServiceControlProducerFactory")
                                .addConstructorArgReference(beanName + "AggregateRuntimeFactory")
                                .addConstructorArgReference(partitionBeanName)
                                .setInitMethodName("start")
                                .setDestroyMethodName("close")
                                .getBeanDefinition());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void processEventSourcingHandler(String aggregateBeanName,
                                             Class<? extends AggregateState> stateClass,
                                             Method method,
                                             BeanDefinitionRegistry bdr,
                                             AggregateValidator validator) {
        EventSourcingHandler esh = method.getAnnotation(EventSourcingHandler.class);
        if (method.getParameterCount() == 2 &&
                DomainEvent.class.isAssignableFrom(method.getParameterTypes()[0]) &&
                stateClass.equals(method.getParameterTypes()[1]) &&
                stateClass.equals(method.getReturnType())) {
            DomainEventInfo eventInfo = method.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            String beanName = aggregateBeanName + "_esh_" + method.getName()
                    + "_" + eventInfo.type() + "_" + eventInfo.version();
            Class<? extends DomainEvent> eventClass = (Class<? extends DomainEvent>) method.getParameterTypes()[0];
            DomainEventType<?> eventType = new DomainEventType<>(
                    eventInfo.type(), eventInfo.version(), eventClass, esh.create(),
                    false, false, hasPIIDataAnnotation(eventClass));
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(EventSourcingHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(method.getName())
                            .addConstructorArgValue(eventType)
                            .addConstructorArgValue(stateClass)
                            .setInitMethodName("init")
                            .getBeanDefinition());
            validator.detectEventSourcingHandler(eventType);
        } else {
            throw new ApplicationContextException("Invalid EventSourcingHandler method signature: " + method);
        }
    }

    @SuppressWarnings("unchecked")
    private void processEventHandler(String aggregateBeanName,
                                     Class<? extends AggregateState> stateClass,
                                     Method method,
                                     BeanDefinitionRegistry bdr,
                                     AggregateValidator validator) {
        EventHandler eh = method.getAnnotation(EventHandler.class);
        if (method.getParameterCount() == 2 &&
                DomainEvent.class.isAssignableFrom(method.getParameterTypes()[0]) &&
                stateClass.equals(method.getParameterTypes()[1]) &&
                Stream.class.isAssignableFrom(method.getReturnType())) {
            DomainEventInfo eventInfo = method.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            String beanName = aggregateBeanName + "_eh_" + method.getName()
                    + "_" + eventInfo.type() + "_" + eventInfo.version();
            Class<? extends DomainEvent> inputEventClass = (Class<? extends DomainEvent>) method.getParameterTypes()[0];
            DomainEventType<?> inputEventType = new DomainEventType<>(
                    eventInfo.type(), eventInfo.version(), inputEventClass, eh.create(),
                    true, ErrorEvent.class.isAssignableFrom(inputEventClass), hasPIIDataAnnotation(inputEventClass));
            List<DomainEventType<?>> produced = generateDomainEventTypes(eh.produces(), eh.create());
            List<DomainEventType<?>> errors = generateEventHandlerErrorEventTypes(eh.errors(), eh.create());
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(EventHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(method.getName())
                            .addConstructorArgValue(inputEventType)
                            .addConstructorArgValue(stateClass)
                            .addConstructorArgValue(produced)
                            .addConstructorArgValue(errors)
                            .setInitMethodName("init")
                            .getBeanDefinition());
            validator.detectEventHandler(inputEventType, produced, errors);
        } else {
            throw new ApplicationContextException("Invalid EventHandler method signature: " + method);
        }
    }

    @SuppressWarnings("unchecked")
    private void processCommandHandler(String aggregateBeanName,
                                       Class<? extends AggregateState> stateClass,
                                       Method method,
                                       BeanDefinitionRegistry bdr,
                                       AggregateValidator validator) {
        CommandHandler ch = method.getAnnotation(CommandHandler.class);
        if (method.getParameterCount() == 2 &&
                Command.class.isAssignableFrom(method.getParameterTypes()[0]) &&
                stateClass.equals(method.getParameterTypes()[1]) &&
                Stream.class.isAssignableFrom(method.getReturnType())) {
            CommandInfo commandInfo = method.getParameterTypes()[0].getAnnotation(CommandInfo.class);
            String beanName = aggregateBeanName + "_ch_" + method.getName()
                    + "_" + commandInfo.type() + "_" + commandInfo.version();
            Class<? extends Command> commandClass = (Class<? extends Command>) method.getParameterTypes()[0];
            CommandType<?> commandType = new CommandType<>(
                    commandInfo.type(), commandInfo.version(), commandClass, ch.create(),
                    false, hasPIIDataAnnotation(commandClass));
            List<DomainEventType<?>> produced = generateDomainEventTypes(ch.produces(), ch.create());
            List<DomainEventType<?>> errors = generateCommandHandlerErrorEventTypes(ch.errors(), ch.create());
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(CommandHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(method.getName())
                            .addConstructorArgValue(commandType)
                            .addConstructorArgValue(stateClass)
                            .addConstructorArgValue(produced)
                            .addConstructorArgValue(errors)
                            .setInitMethodName("init")
                            .getBeanDefinition());
            validator.detectCommandHandler(commandType, produced, errors);
        } else {
            throw new ApplicationContextException("Invalid CommandHandler method signature: " + method);
        }
    }

    @SuppressWarnings("unchecked")
    private void processUpcastingHandler(String aggregateBeanName,
                                         Class<?> aggregateClass,
                                         Method method,
                                         BeanDefinitionRegistry bdr,
                                         AggregateValidator validator) {
        if (method.getParameterCount() == 1 &&
                DomainEvent.class.isAssignableFrom(method.getParameterTypes()[0]) &&
                DomainEvent.class.isAssignableFrom(method.getReturnType())) {

            Class<? extends DomainEvent> inputClass = (Class<? extends DomainEvent>) method.getParameterTypes()[0];
            Class<? extends DomainEvent> outputClass = (Class<? extends DomainEvent>) method.getReturnType();
            DomainEventInfo inputInfo = inputClass.getAnnotation(DomainEventInfo.class);
            DomainEventInfo outputInfo = outputClass.getAnnotation(DomainEventInfo.class);
            validateUpcastingVersions(inputInfo, outputInfo, inputClass, outputClass);

            boolean external = Arrays.stream(aggregateClass.getMethods())
                    .filter(m -> m.isAnnotationPresent(EventSourcingHandler.class))
                    .map(m -> m.getParameterTypes()[0])
                    .noneMatch(c -> c.getAnnotation(DomainEventInfo.class).type().equals(outputInfo.type()));

            DomainEventType<?> inputType = new DomainEventType<>(
                    inputInfo.type(), inputInfo.version(), inputClass, false, external,
                    ErrorEvent.class.isAssignableFrom(inputClass), hasPIIDataAnnotation(inputClass));
            DomainEventType<?> outputType = new DomainEventType<>(
                    outputInfo.type(), outputInfo.version(), outputClass, false, external,
                    ErrorEvent.class.isAssignableFrom(outputClass), hasPIIDataAnnotation(outputClass));

            String beanName = aggregateBeanName + "_duh_" + method.getName()
                    + "_" + inputInfo.type() + "_" + inputInfo.version()
                    + "_to_" + outputInfo.version();
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(DomainEventUpcastingHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(method.getName())
                            .addConstructorArgValue(inputType)
                            .addConstructorArgValue(outputType)
                            .setInitMethodName("init")
                            .getBeanDefinition());
            validator.detectUpcastingHandler(inputType, outputType);

        } else if (method.getParameterCount() == 1 &&
                AggregateState.class.isAssignableFrom(method.getParameterTypes()[0]) &&
                AggregateState.class.isAssignableFrom(method.getReturnType())) {

            Class<? extends AggregateState> inputStateClass =
                    (Class<? extends AggregateState>) method.getParameterTypes()[0];
            Class<? extends AggregateState> outputStateClass =
                    (Class<? extends AggregateState>) method.getReturnType();
            AggregateStateInfo inputStateInfo = inputStateClass.getAnnotation(AggregateStateInfo.class);
            AggregateStateInfo outputStateInfo = outputStateClass.getAnnotation(AggregateStateInfo.class);
            validateStateUpcastingVersions(inputStateInfo, outputStateInfo, inputStateClass, outputStateClass);

            AgenticAggregateInfo agenticInfo = aggregateClass.getAnnotation(AgenticAggregateInfo.class);

            String beanName = aggregateBeanName + "_suh_" + method.getName()
                    + "_" + inputStateInfo.type() + "_" + inputStateInfo.version()
                    + "_to_" + outputStateInfo.version();

            AggregateStateType<?> inputStateType = new AggregateStateType<>(
                    inputStateInfo.type(), inputStateInfo.version(), inputStateClass,
                    false, false, "", hasPIIDataAnnotation(inputStateClass));
            AggregateStateType<?> outputStateType = new AggregateStateType<>(
                    outputStateInfo.type(), outputStateInfo.version(), outputStateClass,
                    false, false, "", hasPIIDataAnnotation(outputStateClass));

            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(AggregateStateUpcastingHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(method.getName())
                            .addConstructorArgValue(inputStateType)
                            .addConstructorArgValue(outputStateType)
                            .setInitMethodName("init")
                            .getBeanDefinition());
            validator.detectUpcastingHandler(inputStateType, outputStateType);
        } else {
            throw new ApplicationContextException("Invalid UpcastingHandler method signature: " + method);
        }
    }

    private void validateUpcastingVersions(DomainEventInfo inputInfo, DomainEventInfo outputInfo,
                                           Class<?> inputClass, Class<?> outputClass) {
        if (inputInfo == null) {
            throw new IllegalArgumentException(
                    "Input event class " + inputClass.getName() + " must be annotated with @DomainEventInfo");
        }
        if (outputInfo == null) {
            throw new IllegalArgumentException(
                    "Output event class " + outputClass.getName() + " must be annotated with @DomainEventInfo");
        }
        if (!inputInfo.type().equals(outputInfo.type())) {
            throw new IllegalArgumentException(
                    "Input event type " + inputInfo.type() + " does not match output event type " + outputInfo.type());
        }
        if (outputInfo.version() - inputInfo.version() != 1) {
            throw new IllegalArgumentException(
                    "Output event version " + outputInfo.version()
                            + " must be one greater than input event version " + inputInfo.version());
        }
    }

    private void validateStateUpcastingVersions(AggregateStateInfo inputInfo, AggregateStateInfo outputInfo,
                                                Class<?> inputClass, Class<?> outputClass) {
        if (inputInfo == null) {
            throw new IllegalArgumentException(
                    "Input state class " + inputClass.getName() + " must be annotated with @AggregateStateInfo");
        }
        if (outputInfo == null) {
            throw new IllegalArgumentException(
                    "Output state class " + outputClass.getName() + " must be annotated with @AggregateStateInfo");
        }
        if (!inputInfo.type().equals(outputInfo.type())) {
            throw new IllegalArgumentException(
                    "Input state type " + inputInfo.type() + " does not match output state type " + outputInfo.type());
        }
        if (outputInfo.version() - inputInfo.version() != 1) {
            throw new IllegalArgumentException(
                    "Output state version " + outputInfo.version()
                            + " must be one greater than input state version " + inputInfo.version());
        }
    }

    private List<DomainEventType<?>> generateDomainEventTypes(Class<? extends DomainEvent>[] eventClasses,
                                                              boolean isCreate) {
        return Arrays.stream(eventClasses).map(eventClass -> {
            DomainEventInfo info = eventClass.getAnnotation(DomainEventInfo.class);
            return new DomainEventType<>(info.type(), info.version(), eventClass,
                    isCreate, false, false, hasPIIDataAnnotation(eventClass));
        }).collect(Collectors.toList());
    }

    private List<DomainEventType<?>> generateEventHandlerErrorEventTypes(
            Class<? extends DomainEvent>[] eventClasses, boolean isCreate) {
        Stream<DomainEventType<? extends DomainEvent>> systemErrors =
                isCreate ? EVENT_HANDLER_CREATE_SYSTEM_ERRORS.stream() : EVENT_HANDLER_SYSTEM_ERRORS.stream();
        return Stream.concat(Arrays.stream(eventClasses).map(eventClass -> {
            DomainEventInfo info = eventClass.getAnnotation(DomainEventInfo.class);
            return new DomainEventType<>(info.type(), info.version(), eventClass,
                    false, false, true, hasPIIDataAnnotation(eventClass));
        }), systemErrors).collect(Collectors.toList());
    }

    private List<DomainEventType<?>> generateCommandHandlerErrorEventTypes(
            Class<? extends DomainEvent>[] eventClasses, boolean isCreate) {
        Stream<DomainEventType<? extends DomainEvent>> systemErrors =
                isCreate ? COMMAND_HANDLER_CREATE_SYSTEM_ERRORS.stream() : COMMAND_HANDLER_SYSTEM_ERRORS.stream();
        return Stream.concat(Arrays.stream(eventClasses).map(eventClass -> {
            DomainEventInfo info = eventClass.getAnnotation(DomainEventInfo.class);
            return new DomainEventType<>(info.type(), info.version(), eventClass,
                    false, false, true, hasPIIDataAnnotation(eventClass));
        }), systemErrors).collect(Collectors.toList());
    }

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        logger.info("Processing AgenticAggregate beans for AOT");
        return null;
    }

    @Override
    public boolean isExcludedFromAotProcessing(RegisteredBean registeredBean) {
        return false;
    }
}
