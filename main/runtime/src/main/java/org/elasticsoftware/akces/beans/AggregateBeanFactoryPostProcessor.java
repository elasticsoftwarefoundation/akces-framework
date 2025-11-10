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

package org.elasticsoftware.akces.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.AkcesAggregateController;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.annotations.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandBus;
import org.elasticsoftware.akces.errors.AggregateAlreadyExistsErrorEvent;
import org.elasticsoftware.akces.errors.CommandExecutionErrorEvent;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.elasticsoftware.akces.kafka.AggregateRuntimeFactory;
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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

public class AggregateBeanFactoryPostProcessor implements BeanFactoryPostProcessor, BeanFactoryInitializationAotProcessor, BeanRegistrationExcludeFilter {
    private static final Logger logger = LoggerFactory.getLogger(AggregateBeanFactoryPostProcessor.class);
    public static final List<DomainEventType<? extends DomainEvent>> COMMAND_HANDLER_CREATE_SYSTEM_ERRORS = List.of(
            new DomainEventType<>("AggregateAlreadyExistsError", 1, AggregateAlreadyExistsErrorEvent.class, false, false, true, false),
            new DomainEventType<>("CommandExecutionError", 1, CommandExecutionErrorEvent.class, false, false, true, false)
    );
    public static final List<DomainEventType<? extends DomainEvent>> COMMAND_HANDLER_SYSTEM_ERRORS = List.of(
            new DomainEventType<>("CommandExecutionError", 1, CommandExecutionErrorEvent.class, false, false, true, false)
    );
    public static final List<DomainEventType<? extends DomainEvent>> EVENT_HANDLER_CREATE_SYSTEM_ERRORS = List.of(
            new DomainEventType<>("AggregateAlreadyExistsError", 1, AggregateAlreadyExistsErrorEvent.class, false, false, true, false)
    );

    @Override
    @SuppressWarnings("unchecked")
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof BeanDefinitionRegistry bdr) {
            logger.info("Processing Aggregate beans");
            Arrays.asList(beanFactory.getBeanNamesForAnnotation(AggregateInfo.class)).forEach(beanName -> {
                logger.info("Processing Aggregate bean {}", beanName);
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                try {
                    final Class<? extends Aggregate<?>> aggregateClass = (Class<? extends Aggregate<?>>) Class.forName(bd.getBeanClassName());
                    AggregateInfo aggregateInfo = aggregateClass.getAnnotation(AggregateInfo.class);
                    AggregateValidator validator = new AggregateValidator(aggregateClass, aggregateInfo.stateClass());
                    List<Method> commandHandlers = Arrays.stream(aggregateClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(CommandHandler.class))
                            .toList();
                    List<Method> eventHandlers = Arrays.stream(aggregateClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(EventHandler.class))
                            .toList();
                    List<Method> eventSourcingHandlers = Arrays.stream(aggregateClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(EventSourcingHandler.class))
                            .toList();
                    List<Method> eventBridgeHandlers = Arrays.stream(aggregateClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(EventBridgeHandler.class))
                            .toList();
                    List<Method> upcastingHandlers = Arrays.stream(aggregateClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(UpcastingHandler.class))
                            .toList();
                    commandHandlers.forEach(commandHandlerMethod ->
                            processCommandHandler(beanName,
                                    aggregateInfo.stateClass(),
                                    commandHandlerMethod,
                                    bdr,
                                    validator));
                    eventHandlers.forEach(eventHandlerMethod ->
                            processEventHandler(beanName,
                                    aggregateInfo.stateClass(),
                                    eventHandlerMethod,
                                    bdr,
                                    validator));
                    eventSourcingHandlers.forEach(eventSourcingHandlerMethod ->
                            processEventSourcingHandler(beanName,
                                    aggregateInfo.stateClass(),
                                    eventSourcingHandlerMethod,
                                    bdr,
                                    validator));
                    eventBridgeHandlers.forEach(eventBridgeHandlerMethod ->
                            processEventBridgeHandler(beanName,
                                    eventBridgeHandlerMethod,
                                    bdr,
                                    validator));
                    upcastingHandlers.forEach(upcastingHandlerMethod ->
                            processUpcastingHandler(beanName, aggregateClass, upcastingHandlerMethod, bdr, validator));
                    // make sure the Aggregate is correctly configured
                    validator.validate();
                } catch (ClassNotFoundException e) {
                    throw new ApplicationContextException("Unable to load class for bean " + beanName, e);
                } catch (IllegalStateException e) {
                    throw new ApplicationContextException("Invalid Aggregate configuration for bean " + beanName, e);
                }
                // now we need to add a bean definition for the AggregateRuntimeFactory
                bdr.registerBeanDefinition(beanName + "AggregateRuntimeFactory",
                        BeanDefinitionBuilder.genericBeanDefinition(AggregateRuntimeFactory.class)
                                .addConstructorArgReference(beanFactory.getBeanNamesForType(ObjectMapper.class)[0])
                                .addConstructorArgReference(beanName)
                                .getBeanDefinition());
                // and create a AkcesController bean to kickstart kafka (if kafka is configured)
                // TODO: this is a bit crude, but it works for now
                if (beanFactory.containsBeanDefinition("aggregateServiceConsumerFactory") &&
                        beanFactory.containsBeanDefinition("aggregateServiceProducerFactory") &&
                        beanFactory.containsBeanDefinition("aggregateServiceControlProducerFactory") &&
                        beanFactory.containsBeanDefinition("aggregateServiceAggregateStateRepositoryFactory")) {
                    bdr.registerBeanDefinition(beanName + "AkcesController",
                            BeanDefinitionBuilder.genericBeanDefinition(AkcesAggregateController.class)
                                    .addConstructorArgReference("aggregateServiceConsumerFactory")
                                    .addConstructorArgReference("aggregateServiceProducerFactory")
                                    .addConstructorArgReference("aggregateServiceControlConsumerFactory")
                                    .addConstructorArgReference("aggregateServiceControlProducerFactory")
                                    .addConstructorArgReference("aggregateServiceAggregateStateRepositoryFactory")
                                    .addConstructorArgReference("aggregateServiceGDPRContextRepositoryFactory")
                                    .addConstructorArgReference(beanName + "AggregateRuntimeFactory")
                                    .addConstructorArgReference("aggregateServiceKafkaAdmin")
                                    .addConstructorArgReference("aggregateServiceSchemaRegistry")
                                    .setInitMethodName("start")
                                    .setDestroyMethodName("close")
                                    .getBeanDefinition());
                }
            });
        } else {
            throw new ApplicationContextException("BeanFactory is not a BeanDefinitionRegistry");
        }
    }

    @SuppressWarnings("unchecked")
    private void processEventSourcingHandler(String aggregateBeanName,
                                             Class<? extends AggregateState> stateClass,
                                             Method eventSourcingHandlerMethod,
                                             BeanDefinitionRegistry bdr,
                                             AggregateValidator validator) {
        EventSourcingHandler eventSourcingHandler = eventSourcingHandlerMethod.getAnnotation(EventSourcingHandler.class);
        if (eventSourcingHandlerMethod.getParameterCount() == 2 &&
                DomainEvent.class.isAssignableFrom(eventSourcingHandlerMethod.getParameterTypes()[0]) &&
                stateClass.equals(eventSourcingHandlerMethod.getParameterTypes()[1]) &&
                stateClass.equals(eventSourcingHandlerMethod.getReturnType())) {
            DomainEventInfo eventInfo = eventSourcingHandlerMethod.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            // need to generate a bean name for this based on the method name and params
            String beanName = aggregateBeanName + "_esh_" + eventSourcingHandlerMethod.getName() + "_" + eventInfo.type() + "_" + eventInfo.version();
            Class<? extends DomainEvent> domainEventClass = (Class<? extends DomainEvent>) eventSourcingHandlerMethod.getParameterTypes()[0];
            DomainEventType<?> domainEventType = new DomainEventType<>(
                    eventInfo.type(),
                    eventInfo.version(),
                    domainEventClass,
                    eventSourcingHandler.create(),
                    false,
                    false,
                    hasPIIDataAnnotation(domainEventClass));
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(EventSourcingHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(eventSourcingHandlerMethod.getName())
                            .addConstructorArgValue(domainEventType)
                            .addConstructorArgValue(stateClass)
                            .setInitMethodName("init")
                            .getBeanDefinition());
            validator.detectEventSourcingHandler(domainEventType);
        } else {
            throw new ApplicationContextException("Invalid EventSourcingHandler method signature: " + eventSourcingHandlerMethod);
        }
    }

    @SuppressWarnings("unchecked")
    private void processEventHandler(String aggregateBeanName,
                                     Class<? extends AggregateState> stateClass,
                                     Method eventHandlerMethod,
                                     BeanDefinitionRegistry bdr,
                                     AggregateValidator validator) {
        EventHandler eventHandler = eventHandlerMethod.getAnnotation(EventHandler.class);
        if (eventHandlerMethod.getParameterCount() == 2 &&
                DomainEvent.class.isAssignableFrom(eventHandlerMethod.getParameterTypes()[0]) &&
                stateClass.equals(eventHandlerMethod.getParameterTypes()[1]) &&
                Stream.class.isAssignableFrom(eventHandlerMethod.getReturnType())) {
            DomainEventInfo eventInfo = eventHandlerMethod.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            // need to generate a bean name for this based on the method name and params
            String beanName = aggregateBeanName + "_eh_" + eventHandlerMethod.getName() + "_" + eventInfo.type() + "_" + eventInfo.version();
            Class<? extends DomainEvent> inputEventClass = (Class<? extends DomainEvent>) eventHandlerMethod.getParameterTypes()[0];
            DomainEventType<?> inputEventType = new DomainEventType<>(
                    eventInfo.type(),
                    eventInfo.version(),
                    inputEventClass,
                    eventHandler.create(),
                    true,
                    ErrorEvent.class.isAssignableFrom(inputEventClass),
                    hasPIIDataAnnotation(inputEventClass));
            List<DomainEventType<?>> producedDomainEventTypes = generateDomainEventTypes(eventHandler.produces(), eventHandler.create());
            List<DomainEventType<?>> errorEventTypes = generateEventHandlerErrorEventTypes(eventHandler.errors(), eventHandler.create());
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(EventHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(eventHandlerMethod.getName())
                            .addConstructorArgValue(inputEventType)
                            .addConstructorArgValue(stateClass)
                            .addConstructorArgValue(producedDomainEventTypes)
                            .addConstructorArgValue(errorEventTypes)
                            .setInitMethodName("init")
                            .getBeanDefinition());
            validator.detectEventHandler(inputEventType, producedDomainEventTypes, errorEventTypes);
        } else {
            throw new ApplicationContextException("Invalid EventHandler method signature: " + eventHandlerMethod);
        }
    }

    @SuppressWarnings("unchecked")
    private void processCommandHandler(String aggregateBeanName,
                                       Class<? extends AggregateState> stateClass,
                                       Method commandHandlerMethod,
                                       BeanDefinitionRegistry bdr,
                                       AggregateValidator validator) {
        // the method signature should match CommandHandlerFunction
        CommandHandler commandHandler = commandHandlerMethod.getAnnotation(CommandHandler.class);
        if (commandHandlerMethod.getParameterCount() == 2 &&
                Command.class.isAssignableFrom(commandHandlerMethod.getParameterTypes()[0]) &&
                stateClass.equals(commandHandlerMethod.getParameterTypes()[1]) &&
                Stream.class.isAssignableFrom(commandHandlerMethod.getReturnType())) {
            CommandInfo commandInfo = commandHandlerMethod.getParameterTypes()[0].getAnnotation(CommandInfo.class);
            // need to generate a bean name for this based on the method name and params
            String beanName = aggregateBeanName + "_ch_" + commandHandlerMethod.getName() + "_" + commandInfo.type() + "_" + commandInfo.version();
            Class<? extends Command> commandClass = (Class<? extends Command>) commandHandlerMethod.getParameterTypes()[0];
            CommandType<?> commandType = new CommandType<>(
                    commandInfo.type(),
                    commandInfo.version(),
                    commandClass,
                    commandHandler.create(),
                    false,
                    hasPIIDataAnnotation(commandClass));
            List<DomainEventType<?>> producedDomainEventTypes = generateDomainEventTypes(commandHandler.produces(), commandHandler.create());
            List<DomainEventType<?>> errorEventTypes = generateCommandHandlerErrorEventTypes(commandHandler.errors(), commandHandler.create());
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(CommandHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(commandHandlerMethod.getName())
                            .addConstructorArgValue(commandType)
                            .addConstructorArgValue(stateClass)
                            .addConstructorArgValue(producedDomainEventTypes)
                            .addConstructorArgValue(errorEventTypes)
                            .setInitMethodName("init").getBeanDefinition()
            );
            validator.detectCommandHandler(commandType, producedDomainEventTypes, errorEventTypes);
        } else {
            throw new ApplicationContextException("Invalid CommandHandler method signature: " + commandHandlerMethod);
        }
    }

    @SuppressWarnings("unchecked")
    private void processEventBridgeHandler(String aggregateBeanName,
                                           Method eventBridgeHandlerMethod,
                                           BeanDefinitionRegistry bdr,
                                           AggregateValidator validator) {
        if (eventBridgeHandlerMethod.getParameterCount() == 2 &&
                DomainEvent.class.isAssignableFrom(eventBridgeHandlerMethod.getParameterTypes()[0]) &&
                eventBridgeHandlerMethod.getParameterTypes()[1].equals(CommandBus.class) &&
                void.class.equals(eventBridgeHandlerMethod.getReturnType())) {
            DomainEventInfo eventInfo = eventBridgeHandlerMethod.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            // Generate bean name based on method name and event info
            String beanName = aggregateBeanName + "_ebh_" + eventBridgeHandlerMethod.getName() + "_" + eventInfo.type() + "_" + eventInfo.version();
            Class<? extends DomainEvent> domainEventClass = (Class<? extends DomainEvent>) eventBridgeHandlerMethod.getParameterTypes()[0];
            DomainEventType<?> inputEventType = new DomainEventType<>(
                    eventInfo.type(),
                    eventInfo.version(),
                    domainEventClass,
                    false,
                    true,
                    ErrorEvent.class.isAssignableFrom(domainEventClass),
                    hasPIIDataAnnotation(domainEventClass));
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(EventBridgeHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(eventBridgeHandlerMethod.getName())
                            .addConstructorArgValue(inputEventType)
                            .setInitMethodName("init")
                            .getBeanDefinition());
            validator.detectEventBridgeHandler(inputEventType);
        } else {
            throw new ApplicationContextException("Invalid EventBridgeHandler method signature: " + eventBridgeHandlerMethod);
        }
    }

    @SuppressWarnings("unchecked")
    private void processUpcastingHandler(String aggregateBeanName,
                                         Class<?> aggregateClass,
                                         Method upcastingHandlerMethod,
                                         BeanDefinitionRegistry bdr,
                                         AggregateValidator validator) {
        // Handle domain event upcasting
        if (upcastingHandlerMethod.getParameterCount() == 1 &&
                DomainEvent.class.isAssignableFrom(upcastingHandlerMethod.getParameterTypes()[0]) &&
                DomainEvent.class.isAssignableFrom(upcastingHandlerMethod.getReturnType())) {

            Class<? extends DomainEvent> inputEventClass = (Class<? extends DomainEvent>) upcastingHandlerMethod.getParameterTypes()[0];
            Class<? extends DomainEvent> outputEventClass = (Class<? extends DomainEvent>) upcastingHandlerMethod.getReturnType();

            DomainEventInfo inputEventInfo = inputEventClass.getAnnotation(DomainEventInfo.class);
            if (inputEventInfo == null) {
                throw new IllegalArgumentException("Input event class " + inputEventClass.getName() +
                        " must be annotated with @DomainEventInfo");
            }

            DomainEventInfo outputEventInfo = outputEventClass.getAnnotation(DomainEventInfo.class);
            if (outputEventInfo == null) {
                throw new IllegalArgumentException("Output event class " + outputEventClass.getName() +
                        " must be annotated with @DomainEventInfo");
            }
            // see if this is a valid upcaster configuration
            if (!inputEventInfo.type().equals(outputEventInfo.type())) {
                throw new IllegalArgumentException("Input event type " + inputEventInfo.type() +
                        " does not match output event type " + outputEventInfo.type());
            }
            if(outputEventInfo.version() - inputEventInfo.version() != 1) {
                throw new IllegalArgumentException("Output event version " + outputEventInfo.version() +
                        " must be one greater than input event version " + inputEventInfo.version());
            }

            // Generate bean name based on method name and event info
            String beanName = aggregateBeanName + "_duh_" + upcastingHandlerMethod.getName() +
                    "_" + inputEventInfo.type() + "_" + inputEventInfo.version() +
                    "_to_" + outputEventInfo.version();

            // we need to figure out if the event is internal or external
            // we need to find the DomainEvent that is actually handled by either an @EventHandler or @EventSourcingHandler
            boolean externalEvent = Arrays.stream(aggregateClass.getMethods())
                    .filter(method -> method.isAnnotationPresent(EventSourcingHandler.class))
                    .map(method -> method.getParameterTypes()[0])
                    .noneMatch(eventClass -> eventClass.getAnnotation(DomainEventInfo.class).type().equals(outputEventInfo.type()));

            DomainEventType<?> inputEventType = new DomainEventType<>(
                    inputEventInfo.type(),
                    inputEventInfo.version(),
                    inputEventClass,
                    false,
                    externalEvent,
                    ErrorEvent.class.isAssignableFrom(inputEventClass),
                    hasPIIDataAnnotation(inputEventClass));

            DomainEventType<?> outputEventType = new DomainEventType<>(
                    outputEventInfo.type(),
                    outputEventInfo.version(),
                    outputEventClass,
                    false,
                    externalEvent,
                    ErrorEvent.class.isAssignableFrom(outputEventClass),
                    hasPIIDataAnnotation(outputEventClass));

            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(DomainEventUpcastingHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(upcastingHandlerMethod.getName())
                            .addConstructorArgValue(inputEventType)
                            .addConstructorArgValue(outputEventType)
                            .setInitMethodName("init")
                            .getBeanDefinition());
            validator.detectUpcastingHandler(inputEventType, outputEventType);
            // Handle aggregate state upcasting
        } else if (upcastingHandlerMethod.getParameterCount() == 1 &&
                AggregateState.class.isAssignableFrom(upcastingHandlerMethod.getParameterTypes()[0]) &&
                AggregateState.class.isAssignableFrom(upcastingHandlerMethod.getReturnType())) {

            Class<? extends AggregateState> inputStateClass = (Class<? extends AggregateState>) upcastingHandlerMethod.getParameterTypes()[0];
            Class<? extends AggregateState> outputStateClass = (Class<? extends AggregateState>) upcastingHandlerMethod.getReturnType();

            AggregateStateInfo inputStateInfo = inputStateClass.getAnnotation(AggregateStateInfo.class);
            if (inputStateInfo == null) {
                throw new IllegalArgumentException("Input state class " + inputStateClass.getName() +
                        " must be annotated with @AggregateStateInfo");
            }

            AggregateStateInfo outputStateInfo = outputStateClass.getAnnotation(AggregateStateInfo.class);
            if (outputStateInfo == null) {
                throw new IllegalArgumentException("Output state class " + outputStateClass.getName() +
                        " must be annotated with @AggregateStateInfo");
            }

            AggregateInfo aggregateInfo = aggregateClass.getAnnotation(AggregateInfo.class);
            if (aggregateInfo == null) {
                throw new IllegalArgumentException("Aggregate class " + aggregateClass.getName() +
                        " must be annotated with @AggregateInfo");
            }

            // see if this is a valid upcaster configuration
            if (!inputStateInfo.type().equals(outputStateInfo.type())) {
                throw new IllegalArgumentException("Input state type " + inputStateInfo.type() +
                        " does not match output state type " + outputStateInfo.type());
            }
            if(outputStateInfo.version() - inputStateInfo.version() != 1) {
                throw new IllegalArgumentException("Output state version " + outputStateInfo.version() +
                        " must be one greater than input state version " + inputStateInfo.version());
            }

            // Generate bean name based on method name and state info
            String beanName = aggregateBeanName + "_suh_" + upcastingHandlerMethod.getName() +
                    "_" + inputStateInfo.type() + "_" + inputStateInfo.version() +
                    "_to_" + outputStateInfo.version();

            AggregateStateType<?> inputStateType = new AggregateStateType<>(
                    inputStateInfo.type(),
                    inputStateInfo.version(),
                    inputStateClass,
                    aggregateInfo.generateGDPRKeyOnCreate(),
                    aggregateInfo.indexed(),
                    aggregateInfo.indexName(),
                    hasPIIDataAnnotation(inputStateClass)
            );

            AggregateStateType<?> outputStateType = new AggregateStateType<>(
                    outputStateInfo.type(),
                    outputStateInfo.version(),
                    outputStateClass,
                    aggregateInfo.generateGDPRKeyOnCreate(),
                    aggregateInfo.indexed(),
                    aggregateInfo.indexName(),
                    hasPIIDataAnnotation(outputStateClass)
            );

            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(AggregateStateUpcastingHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(upcastingHandlerMethod.getName())
                            .addConstructorArgValue(inputStateType)
                            .addConstructorArgValue(outputStateType)
                            .setInitMethodName("init")
                            .getBeanDefinition());

            validator.detectUpcastingHandler(inputStateType, outputStateType);
        } else {
            throw new ApplicationContextException("Invalid UpcastingHandler method signature: " + upcastingHandlerMethod);
        }
    }

    private List<DomainEventType<?>> generateDomainEventTypes(Class<? extends DomainEvent>[] domainEventClasses,
                                                              boolean isCreate) {
        return Arrays.stream(domainEventClasses).map(eventClass -> {
            DomainEventInfo eventInfo = eventClass.getAnnotation(DomainEventInfo.class);
            return new DomainEventType<>(eventInfo.type(), eventInfo.version(), eventClass, isCreate, false, false, hasPIIDataAnnotation(eventClass));
        }).collect(Collectors.toList());
    }

    private List<DomainEventType<?>> generateEventHandlerErrorEventTypes(Class<? extends DomainEvent>[] domainEventClasses, boolean isCreate) {
        Stream<DomainEventType<? extends DomainEvent>> systemErrorEvents = (isCreate) ? EVENT_HANDLER_CREATE_SYSTEM_ERRORS.stream() : Stream.empty();
        return Stream.concat(Arrays.stream(domainEventClasses).map(eventClass -> {
            DomainEventInfo eventInfo = eventClass.getAnnotation(DomainEventInfo.class);
            return new DomainEventType<>(eventInfo.type(), eventInfo.version(), eventClass, false, false, true, hasPIIDataAnnotation(eventClass));
        }), systemErrorEvents).collect(Collectors.toList());
    }

    private List<DomainEventType<?>> generateCommandHandlerErrorEventTypes(Class<? extends DomainEvent>[] domainEventClasses, boolean isCreate) {
        Stream<DomainEventType<? extends DomainEvent>> systemErrorEvents = (isCreate) ? COMMAND_HANDLER_CREATE_SYSTEM_ERRORS.stream() : COMMAND_HANDLER_SYSTEM_ERRORS.stream();
        return Stream.concat(Arrays.stream(domainEventClasses).map(eventClass -> {
            DomainEventInfo eventInfo = eventClass.getAnnotation(DomainEventInfo.class);
            return new DomainEventType<>(eventInfo.type(), eventInfo.version(), eventClass, false, false, true, hasPIIDataAnnotation(eventClass));
        }), systemErrorEvents).collect(Collectors.toList());
    }

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        logger.info("Processing Aggregate beans for AOT");
        return null;
    }

    @Override
    public boolean isExcludedFromAotProcessing(RegisteredBean registeredBean) {
        return false;
    }
}
