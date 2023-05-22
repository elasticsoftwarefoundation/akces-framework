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

package org.elasticsoftware.akces.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.elasticsoftware.akces.AkcesController;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.annotations.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.kafka.AggregateRuntimeFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextException;
import org.springframework.kafka.core.KafkaAdminOperations;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class AggregateBeanFactoryPostProcessor implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if(beanFactory instanceof BeanDefinitionRegistry bdr) {
            Arrays.asList(beanFactory.getBeanNamesForAnnotation(AggregateInfo.class)).forEach(beanName -> {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                try {
                    Class<?> aggregateClass = Class.forName(bd.getBeanClassName());
                    List<Method> commandHandlers = Arrays.stream(aggregateClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(CommandHandler.class))
                            .toList();
                    List<Method> eventHandlers = Arrays.stream(aggregateClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(EventHandler.class))
                            .toList();
                    List<Method> eventSourcingHandlers = Arrays.stream(aggregateClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(EventSourcingHandler.class))
                            .toList();
                    commandHandlers.forEach(commandHandlerMethod -> processCommandHandler(beanName, commandHandlerMethod, bdr));
                    eventHandlers.forEach(eventHandlerMethod -> processEventHandler(beanName, eventHandlerMethod, bdr));
                    eventSourcingHandlers.forEach(eventSourcingHandlerMethod -> processEventSourcingHandler(beanName, eventSourcingHandlerMethod, bdr));
                } catch (ClassNotFoundException e) {
                    throw new ApplicationContextException("Unable to load class for bean " + beanName, e);
                }
                // now we need to add a bean definition for the AggregateRuntimeFactory
                bdr.registerBeanDefinition(beanName + "AggregateRuntimeFactory",
                        BeanDefinitionBuilder.genericBeanDefinition(AggregateRuntimeFactory.class)
                                .addConstructorArgValue(beanFactory)
                                .addConstructorArgReference(beanFactory.getBeanNamesForType(ObjectMapper.class)[0])
                                .addConstructorArgReference(beanFactory.getBeanNamesForType(SchemaRegistryClient.class)[0])
                                .addConstructorArgReference(beanName)
                                .getBeanDefinition());
                // and create a AckesControl bean to kickstart kafka (if kafka is configured)
                // TODO: this is a bit crude, but it works for now
                if(beanFactory.containsBeanDefinition("consumerFactory") &&
                        beanFactory.containsBeanDefinition("producerFactory") &&
                        beanFactory.containsBeanDefinition("controlConsumerFactory") &&
                        beanFactory.containsBeanDefinition("controlProducerFactory")) {
                    bdr.registerBeanDefinition(beanName + "AkcesController",
                            BeanDefinitionBuilder.genericBeanDefinition(AkcesController.class)
                                    .addConstructorArgReference("consumerFactory")
                                    .addConstructorArgReference("producerFactory")
                                    .addConstructorArgReference("controlConsumerFactory")
                                    .addConstructorArgReference("controlProducerFactory")
                                    .addConstructorArgReference("aggregateStateRepositoryFactory")
                                    .addConstructorArgReference(beanName + "AggregateRuntimeFactory")
                                    .addConstructorArgReference(beanFactory.getBeanNamesForType(KafkaAdminOperations.class)[0])
                                    .setInitMethodName("start")
                                    .getBeanDefinition());
                }
            });
        } else {
            throw new ApplicationContextException("BeanFactory is not a BeanDefinitionRegistry");
        }
    }

    private void processEventSourcingHandler(String aggregateBeanName, Method eventSourcingHandlerMethod, BeanDefinitionRegistry bdr) {
        EventSourcingHandler eventSourcingHandler = eventSourcingHandlerMethod.getAnnotation(EventSourcingHandler.class);
        if(eventSourcingHandlerMethod.getParameterCount() == 2 &&
                DomainEvent.class.isAssignableFrom(eventSourcingHandlerMethod.getParameterTypes()[0]) &&
                AggregateState.class.isAssignableFrom(eventSourcingHandlerMethod.getParameterTypes()[1]) &&
                AggregateState.class.isAssignableFrom(eventSourcingHandlerMethod.getReturnType())) {
            DomainEventInfo eventInfo = eventSourcingHandlerMethod.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            // need to generate a bean name for this based on the method name and params
            String beanName = aggregateBeanName + "_esh_" + eventSourcingHandlerMethod.getName() + "_" + eventInfo.type() + "_" + eventInfo.version();
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(EventSourcingHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(eventSourcingHandlerMethod.getName())
                            .addConstructorArgValue(eventSourcingHandlerMethod.getParameterTypes()[0])
                            .addConstructorArgValue(eventSourcingHandlerMethod.getParameterTypes()[1])
                            .addConstructorArgValue(eventSourcingHandler.create())
                            .addConstructorArgValue(eventInfo)
                            .setInitMethodName("init")
                            .getBeanDefinition());
        } else {
            throw new ApplicationContextException("Invalid EventSourcingHandler method signature: " + eventSourcingHandlerMethod);
        }
    }

    private void processEventHandler(String aggregateBeanName, Method eventHandlerMethod, BeanDefinitionRegistry bdr) {
        EventHandler eventHandler = eventHandlerMethod.getAnnotation(EventHandler.class);
        if(eventHandlerMethod.getParameterCount() == 2 &&
                DomainEvent.class.isAssignableFrom(eventHandlerMethod.getParameterTypes()[0]) &&
                AggregateState.class.isAssignableFrom(eventHandlerMethod.getParameterTypes()[1]) &&
                Stream.class.isAssignableFrom(eventHandlerMethod.getReturnType())) {
            DomainEventInfo eventInfo = eventHandlerMethod.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            // need to generate a bean name for this based on the method name and params
            String beanName = aggregateBeanName + "_eh_" + eventHandlerMethod.getName() + "_" + eventInfo.type() + "_" + eventInfo.version();
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(EventHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(eventHandlerMethod.getName())
                            .addConstructorArgValue(eventHandlerMethod.getParameterTypes()[0])
                            .addConstructorArgValue(eventHandlerMethod.getParameterTypes()[1])
                            .addConstructorArgValue(eventHandler.create())
                            .addConstructorArgValue(generateDomainEventTypes(eventHandler.produces()))
                            .addConstructorArgValue(generateDomainEventTypes(eventHandler.errors()))
                            .addConstructorArgValue(eventInfo)
                            .setInitMethodName("init")
                            .getBeanDefinition());
        } else {
            throw new ApplicationContextException("Invalid EventHandler method signature: " + eventHandlerMethod);
        }
    }

    private void processCommandHandler(String aggregateBeanName, Method commandHandlerMethod, BeanDefinitionRegistry bdr) {
        // the method signature should match CommandHandlerFunction
        CommandHandler commandHandler = commandHandlerMethod.getAnnotation(CommandHandler.class);
        if(commandHandlerMethod.getParameterCount() == 2 &&
                Command.class.isAssignableFrom(commandHandlerMethod.getParameterTypes()[0]) &&
                AggregateState.class.isAssignableFrom(commandHandlerMethod.getParameterTypes()[1]) &&
                Stream.class.isAssignableFrom(commandHandlerMethod.getReturnType())) {
            CommandInfo commandInfo = commandHandlerMethod.getParameterTypes()[0].getAnnotation(CommandInfo.class);
            // need to generate a bean name for this based on the method name and params
            String beanName = aggregateBeanName + "_ch_" + commandHandlerMethod.getName() + "_" + commandInfo.type() + "_" + commandInfo.version();
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(CommandHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(commandHandlerMethod.getName())
                            .addConstructorArgValue(commandHandlerMethod.getParameterTypes()[0])
                            .addConstructorArgValue(commandHandlerMethod.getParameterTypes()[1])
                            .addConstructorArgValue(commandHandler.create())
                            .addConstructorArgValue(generateDomainEventTypes(commandHandler.produces()))
                            .addConstructorArgValue(generateDomainEventTypes(commandHandler.errors()))
                            .addConstructorArgValue(commandInfo)
                            .setInitMethodName("init").getBeanDefinition()
            );
        } else {
            throw new ApplicationContextException("Invalid CommandHandler method signature: " + commandHandlerMethod);
        }
    }

    private List<DomainEventType<?>> generateDomainEventTypes(Class<? extends DomainEvent>[] domainEventClasses) {
        return Arrays.stream(domainEventClasses).map(eventClass -> {
            DomainEventInfo eventInfo = eventClass.getAnnotation(DomainEventInfo.class);
            return new DomainEventType<>(eventInfo.type(), eventInfo.version(), eventClass, false, false, true);
        }).collect(Collectors.toList());
    }

}
