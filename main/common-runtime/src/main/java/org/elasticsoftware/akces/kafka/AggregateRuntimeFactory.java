package org.elasticsoftware.akces.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.annotations.*;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.EventHandlerFunction;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;

import static java.lang.Boolean.FALSE;

@Factory
public class AggregateRuntimeFactory {
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final SchemaRegistryClient schemaRegistryClient;

    public AggregateRuntimeFactory(ApplicationContext applicationContext,
                                   ObjectMapper objectMapper,
                                   SchemaRegistryClient schemaRegistryClient) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.schemaRegistryClient = schemaRegistryClient;
    }

    @EachBean(Aggregate.class)
    @Singleton
    <S extends AggregateState> KafkaAggregateRuntime createRuntime(Aggregate<S> aggregate) {
        KafkaAggregateRuntime.Builder runtimeBuilder = new KafkaAggregateRuntime.Builder();

        BeanIntrospection<?> aggregateState = BeanIntrospector.SHARED.findIntrospection(aggregate.getStateClass())
                .orElseThrow(() -> new IllegalStateException("Class implementing AggregateState must be annotated with @AggregateStateInfo"));

        runtimeBuilder.setStateType(new AggregateStateType<>(
            aggregateState.getAnnotation(AggregateStateInfo.class).stringValue("type").orElseThrow(),
            aggregateState.getAnnotation(AggregateStateInfo.class).intValue("version").orElse(1),
            aggregate.getStateClass()
        ));

        runtimeBuilder.setObjectMapper(objectMapper);

        applicationContext.getBeanDefinitions(CommandHandlerFunction.class).stream()
                // TODO: we should filter only for methods contained in the current aggregateBeanDefinition class
                //.filter(beanDefinition -> beanDefinition.getDeclaringType().get().equals(aggregate.getClass()))
                .forEach(beanDefinition -> {
                    // see if this is a create handler
                    beanDefinition.findAnnotation(CommandHandler.class)
                            .ifPresent(av -> {
                                if (av.booleanValue("create").orElse(FALSE)) {
                                    runtimeBuilder.setCommandCreateHandler(applicationContext.getBean(beanDefinition));
                                    runtimeBuilder.addCommand(resolveCommandType(beanDefinition, true));
                                } else {
                                    CommandType<?> type = resolveCommandType(beanDefinition, false);
                                    runtimeBuilder.addCommandHandler(type , applicationContext.getBean(beanDefinition));
                                    runtimeBuilder.addCommand(type);
                                }
                            });
                });
        // get ExternalEventHandlers for this Aggregate
        applicationContext.getBeanDefinitions(EventHandlerFunction.class).stream()
                //.filter
                .forEach(beanDefinition -> {
                    beanDefinition.findAnnotation(EventHandler.class)
                            .ifPresent(av -> {
                                if (av.booleanValue("create").orElse(FALSE)) {
                                    runtimeBuilder.setEventCreateHandler(applicationContext.getBean(beanDefinition));
                                    runtimeBuilder.addDomainEvent(resolveExternalDomainEventType(beanDefinition, true));
                                } else {
                                    DomainEventType<?> type = resolveExternalDomainEventType(beanDefinition, false);
                                    runtimeBuilder.addExternalEventHandler(type, applicationContext.getBean(beanDefinition));
                                    runtimeBuilder.addDomainEvent(type);
                                }
                            });
                });
        // EventSourcingHandlers
        applicationContext.getBeanDefinitions(EventSourcingHandlerFunction.class).stream()
                //.filter
                .forEach(beanDefinition -> {
                    beanDefinition.findAnnotation(EventSourcingHandler.class)
                            .ifPresent(av -> {
                                if (av.booleanValue("create").orElse(FALSE)) {
                                    runtimeBuilder.setEventSourcingCreateHandler(applicationContext.getBean(beanDefinition));
                                    runtimeBuilder.addDomainEvent(resolveInternalDomainEventType(beanDefinition, true));
                                } else {
                                    DomainEventType<?> type = resolveInternalDomainEventType(beanDefinition, false);
                                    runtimeBuilder.addEventSourcingHandler(type, applicationContext.getBean(beanDefinition));
                                    runtimeBuilder.addDomainEvent(type);
                                }
                            });
                });

        return runtimeBuilder.setSchemaRegistryClient(schemaRegistryClient).build();
    }

    private <C extends Command> CommandType<C> resolveCommandType(BeanDefinition<CommandHandlerFunction> beanDefinition, boolean create) {
        Class<C> commandClass = (Class<C>) beanDefinition.getTypeArguments(CommandHandlerFunction.class.getName()).get(0).getType();
        BeanIntrospection<?> commandInfo = BeanIntrospector.SHARED.findIntrospection(commandClass)
                .orElseThrow(() -> new IllegalStateException("Command class not annotated with CommandInfo"));
        return new CommandType<>(
                commandInfo.getAnnotation(CommandInfo.class).stringValue("type").orElseThrow(),
                commandInfo.getAnnotation(CommandInfo.class).intValue("version").orElse(1),
                commandClass,
                create);
    }

    private <E extends DomainEvent> DomainEventType<E> resolveExternalDomainEventType(BeanDefinition<EventHandlerFunction> beanDefinition, boolean create) {
        Class<E> eventClass = (Class<E>) beanDefinition.getTypeArguments(EventHandlerFunction.class.getName()).get(0).getType();
        BeanIntrospection<?> domainEventInfo = BeanIntrospector.SHARED.findIntrospection(eventClass)
                .orElseThrow(() -> new IllegalStateException("DomainEvent class not annotated with DomainEventInfo"));
        return new DomainEventType<>(
                domainEventInfo.getAnnotation(DomainEventInfo.class).stringValue("type").orElseThrow(),
                domainEventInfo.getAnnotation(DomainEventInfo.class).intValue("version").orElse(1),
                eventClass,
                create,
                true);
    }

    private <E extends DomainEvent> DomainEventType<E> resolveInternalDomainEventType(BeanDefinition<EventSourcingHandlerFunction> beanDefinition, boolean create) {
        Class<E> eventClass = (Class<E>) beanDefinition.getTypeArguments(EventSourcingHandlerFunction.class.getName()).get(0).getType();
        BeanIntrospection<?> domainEventInfo = BeanIntrospector.SHARED.findIntrospection(eventClass)
                .orElseThrow(() -> new IllegalStateException("DomainEvent class not annotated with DomainEventInfo"));
        return new DomainEventType<>(
                domainEventInfo.getAnnotation(DomainEventInfo.class).stringValue("type").orElseThrow(),
                domainEventInfo.getAnnotation(DomainEventInfo.class).intValue("version").orElse(1),
                eventClass,
                create,
                false);
    }
}
