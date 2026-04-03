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

package org.elasticsoftware.akces.query.reflector.beans;

import tools.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.ReflectorEventHandler;
import org.elasticsoftware.akces.annotations.ReflectorInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.query.reflector.AkcesReflectorController;
import org.elasticsoftware.akces.query.reflector.ReflectorRuntimeFactory;
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

public class ReflectorBeanFactoryPostProcessor implements BeanFactoryPostProcessor, BeanFactoryInitializationAotProcessor, BeanRegistrationExcludeFilter {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof BeanDefinitionRegistry bdr) {
            Arrays.asList(beanFactory.getBeanNamesForAnnotation(ReflectorInfo.class)).forEach(beanName -> {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                try {
                    Class<?> reflectorClass = Class.forName(bd.getBeanClassName());
                    List<Method> reflectorEventHandlers = Arrays.stream(reflectorClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(ReflectorEventHandler.class))
                            .toList();
                    reflectorEventHandlers.forEach(eventHandlerMethod -> processReflectorEventHandler(beanName, eventHandlerMethod, bdr));
                } catch (ClassNotFoundException e) {
                    throw new ApplicationContextException("Unable to load class for bean " + beanName, e);
                }
                // register the ReflectorRuntimeFactory bean
                bdr.registerBeanDefinition(beanName + "ReflectorRuntime",
                        BeanDefinitionBuilder.genericBeanDefinition(ReflectorRuntimeFactory.class)
                                .addConstructorArgReference(beanFactory.getBeanNamesForType(ObjectMapper.class)[0])
                                .addConstructorArgReference(beanName)
                                .getBeanDefinition());
                // register the AkcesReflectorController bean
                bdr.registerBeanDefinition(beanName + "AkcesReflectorController",
                        BeanDefinitionBuilder.genericBeanDefinition(AkcesReflectorController.class)
                                .addConstructorArgReference("akcesReflectorConsumerFactory")
                                .addConstructorArgReference("akcesReflectorProducerFactory")
                                .addConstructorArgReference("akcesReflectorControlConsumerFactory")
                                .addConstructorArgReference("akcesReflectorSchemaConsumerFactory")
                                .addConstructorArgReference(beanFactory.getBeanNamesForType(ObjectMapper.class)[0])
                                .addConstructorArgReference("akcesReflectorGDPRContextRepositoryFactory")
                                .addConstructorArgReference(beanName + "ReflectorRuntime")
                                .setInitMethodName("start")
                                .setDestroyMethodName("close")
                                .getBeanDefinition());
            });
        } else {
            throw new ApplicationContextException("BeanFactory is not a BeanDefinitionRegistry");
        }
    }

    @SuppressWarnings("unchecked")
    private void processReflectorEventHandler(String reflectorBeanName, Method reflectorEventHandlerMethod, BeanDefinitionRegistry bdr) {
        if (reflectorEventHandlerMethod.getParameterCount() == 1 &&
                DomainEvent.class.isAssignableFrom(reflectorEventHandlerMethod.getParameterTypes()[0]) &&
                !void.class.equals(reflectorEventHandlerMethod.getReturnType())) {
            DomainEventInfo eventInfo = reflectorEventHandlerMethod.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            Class<? extends DomainEvent> eventClass = (Class<? extends DomainEvent>) reflectorEventHandlerMethod.getParameterTypes()[0];
            Class<?> returnType = reflectorEventHandlerMethod.getReturnType();
            // generate a bean name for this adapter based on the method name and params
            String beanName = reflectorBeanName + "_reh_" + reflectorEventHandlerMethod.getName() + "_" + eventInfo.type() + "_" + eventInfo.version();
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(ReflectorEventHandlerFunctionAdapter.class)
                            .addConstructorArgReference(reflectorBeanName)
                            .addConstructorArgValue(reflectorEventHandlerMethod.getName())
                            .addConstructorArgValue(eventClass)
                            .addConstructorArgValue(returnType)
                            .addConstructorArgValue(eventInfo.type())
                            .addConstructorArgValue(eventInfo.version())
                            .setInitMethodName("init")
                            .getBeanDefinition());
        } else {
            throw new ApplicationContextException("Invalid ReflectorEventHandler method signature: " + reflectorEventHandlerMethod +
                    ". Method must have exactly one DomainEvent parameter and a non-void return type.");
        }
    }

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        return null;
    }

    @Override
    public boolean isExcludedFromAotProcessing(RegisteredBean registeredBean) {
        return false;
    }
}
