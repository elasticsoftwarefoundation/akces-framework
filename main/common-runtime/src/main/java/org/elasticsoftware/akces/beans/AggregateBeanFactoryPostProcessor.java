package org.elasticsoftware.akces.beans;

import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.events.DomainEvent;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextException;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

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
                DomainEvent.class.isAssignableFrom(eventHandlerMethod.getReturnType())) {
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
                DomainEvent.class.isAssignableFrom(commandHandlerMethod.getReturnType())) {
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
                            .addConstructorArgValue(commandInfo)
                            .setInitMethodName("init").getBeanDefinition()
            );
        } else {
            throw new ApplicationContextException("Invalid CommandHandler method signature: " + commandHandlerMethod);
        }
    }
}
