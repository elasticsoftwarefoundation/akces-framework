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

package org.elasticsoftware.akces.query.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.annotations.DomainEventInfo;
import org.elasticsoftware.akces.annotations.QueryModelEventHandler;
import org.elasticsoftware.akces.annotations.QueryModelInfo;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.query.QueryModelState;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class QueryModelBeanFactoryPostProcessor implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (beanFactory instanceof BeanDefinitionRegistry bdr) {
            Arrays.asList(beanFactory.getBeanNamesForAnnotation(QueryModelInfo.class)).forEach(beanName -> {
                BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
                try {
                    Class<?> queryModelClass = Class.forName(bd.getBeanClassName());
                    List<Method> queryModelEventHandlers = Arrays.stream(queryModelClass.getMethods())
                            .filter(method -> method.isAnnotationPresent(QueryModelEventHandler.class))
                            .toList();
                    queryModelEventHandlers.forEach(eventHandlerMethod -> processQueryModelEventHandler(beanName, eventHandlerMethod, bdr));
                } catch (ClassNotFoundException e) {
                    throw new ApplicationContextException("Unable to load class for bean " + beanName, e);
                }
                // now we need to add a bean definition for the AggregateRuntimeFactory
                bdr.registerBeanDefinition(beanName + "QueryModelRuntime",
                        BeanDefinitionBuilder.genericBeanDefinition(QueryModelRuntimeFactory.class)
                                .addConstructorArgValue(beanFactory)
                                .addConstructorArgReference(beanFactory.getBeanNamesForType(ObjectMapper.class)[0])
                                .addConstructorArgReference("akcesQueryModelSchemaRegistryClient")
                                .addConstructorArgReference(beanName)
                                .getBeanDefinition());
            });
        } else {
            throw new ApplicationContextException("BeanFactory is not a BeanDefinitionRegistry");
        }
    }

    private void processQueryModelEventHandler(String aggregateBeanName, Method queryModelEventHandlerMethod, BeanDefinitionRegistry bdr) {
        QueryModelEventHandler queryModelEventHandler = queryModelEventHandlerMethod.getAnnotation(QueryModelEventHandler.class);
        if (queryModelEventHandlerMethod.getParameterCount() == 2 &&
                DomainEvent.class.isAssignableFrom(queryModelEventHandlerMethod.getParameterTypes()[0]) &&
                QueryModelState.class.isAssignableFrom(queryModelEventHandlerMethod.getParameterTypes()[1]) &&
                QueryModelState.class.isAssignableFrom(queryModelEventHandlerMethod.getReturnType())) {
            DomainEventInfo eventInfo = queryModelEventHandlerMethod.getParameterTypes()[0].getAnnotation(DomainEventInfo.class);
            // need to generate a bean name for this based on the method name and params
            String beanName = aggregateBeanName + "_qmeh_" + queryModelEventHandlerMethod.getName() + "_" + eventInfo.type() + "_" + eventInfo.version();
            bdr.registerBeanDefinition(beanName,
                    BeanDefinitionBuilder.genericBeanDefinition(QueryModelEventHandlerFunctionAdapter.class)
                            .addConstructorArgReference(aggregateBeanName)
                            .addConstructorArgValue(queryModelEventHandlerMethod.getName())
                            .addConstructorArgValue(queryModelEventHandlerMethod.getParameterTypes()[0])
                            .addConstructorArgValue(queryModelEventHandlerMethod.getParameterTypes()[1])
                            .addConstructorArgValue(queryModelEventHandler.create())
                            .addConstructorArgValue(eventInfo)
                            .setInitMethodName("init")
                            .getBeanDefinition());
        } else {
            throw new ApplicationContextException("Invalid QueryModelEventHandler method signature: " + queryModelEventHandlerMethod);
        }
    }
}
