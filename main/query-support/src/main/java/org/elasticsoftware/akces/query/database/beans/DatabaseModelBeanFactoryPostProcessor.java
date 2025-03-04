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

package org.elasticsoftware.akces.query.database.beans;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.annotations.DatabaseModelEventHandler;
import org.elasticsoftware.akces.annotations.DatabaseModelInfo;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.query.database.AkcesDatabaseModelController;
import org.elasticsoftware.akces.query.database.DatabaseModelRuntimeFactory;
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

public class DatabaseModelBeanFactoryPostProcessor implements BeanFactoryPostProcessor, BeanFactoryInitializationAotProcessor, BeanRegistrationExcludeFilter {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof BeanDefinitionRegistry bdr) {
            Arrays.asList(beanFactory.getBeanNamesForAnnotation(DatabaseModelInfo.class)).forEach(beanName -> {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                try {
                    Class<?> queryModelClass = Class.forName(bd.getBeanClassName());
                    List<Method> queryModelEventHandlers = Arrays.stream(queryModelClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(DatabaseModelEventHandler.class))
                            .toList();
                    queryModelEventHandlers.forEach(eventHandlerMethod -> processDatabaseModelEventHandler(beanName, eventHandlerMethod, bdr));
                } catch (ClassNotFoundException e) {
                    throw new ApplicationContextException("Unable to load class for bean " + beanName, e);
                }
                // now we need to add a bean definition for the DatabaseNodelRuntimeFactory
                bdr.registerBeanDefinition(beanName + "DatabaseModelRuntime",
                        BeanDefinitionBuilder.genericBeanDefinition(DatabaseModelRuntimeFactory.class)
                                .addConstructorArgReference(beanFactory.getBeanNamesForType(ObjectMapper.class)[0])
                                .addConstructorArgReference("akcesDatabaseModelSchemaRegistry")
                                .addConstructorArgReference(beanName)
                                .getBeanDefinition());
                bdr.registerBeanDefinition(beanName + "AkcesDatabaseModelController",
                        BeanDefinitionBuilder.genericBeanDefinition(AkcesDatabaseModelController.class)
                                .addConstructorArgReference("akcesDatabaseModelConsumerFactory")
                                .addConstructorArgReference("akcesDatabaseModelControlConsumerFactory")
                                .addConstructorArgReference("akcesDatabaseModelGDPRContextRepositoryFactory")
                                .addConstructorArgReference(beanName + "DatabaseModelRuntime")
                                .setInitMethodName("start")
                                .setDestroyMethodName("close")
                                .getBeanDefinition());
            });
        } else {
            throw new ApplicationContextException("BeanFactory is not a BeanDefinitionRegistry");
        }
    }

    private void processDatabaseModelEventHandler(String aggregateBeanName, Method databaseModelEventHandlerMethod, BeanDefinitionRegistry bdr) {
        if (databaseModelEventHandlerMethod.getParameterCount() == 1 &&
                DomainEvent.class.isAssignableFrom(databaseModelEventHandlerMethod.getParameterTypes()[0]) &&
                void.class.equals(databaseModelEventHandlerMethod.getReturnType())) {
            DomainEventInfo eventInfo = databaseModelEventHandlerMethod.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            // need to generate a bean name for this based on the method name and params
            String beanName = aggregateBeanName + "_dmeh_" + databaseModelEventHandlerMethod.getName() + "_" + eventInfo.type() + "_" + eventInfo.version();
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(DatabaseModelEventHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(databaseModelEventHandlerMethod.getName())
                            .addConstructorArgValue(databaseModelEventHandlerMethod.getParameterTypes()[0])
                            .addConstructorArgValue(eventInfo.type())
                            .addConstructorArgValue(eventInfo.version())
                            .setInitMethodName("init")
                            .getBeanDefinition());
        } else {
            throw new ApplicationContextException("Invalid DatabaseModelEventHandler method signature: " + databaseModelEventHandlerMethod);
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
