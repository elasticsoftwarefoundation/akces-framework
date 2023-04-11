package org.elasticsoftware.akces.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.elasticsoftware.akces.aggregate.*;
import org.elasticsoftware.akces.annotations.AggregateInfo;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.events.EventHandlerFunction;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
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

    @Bean
    <S extends AggregateState> KafkaAggregateRuntime createRuntime(Aggregate<S> aggregate) {
        KafkaAggregateRuntime.Builder runtimeBuilder = new KafkaAggregateRuntime.Builder();

        AggregateInfo aggregateInfo = aggregate.getClass().getAnnotation(AggregateInfo.class);

        if (aggregateInfo != null) {
            runtimeBuilder.setStateType(new AggregateStateType<>(
                    aggregateInfo.value(),
                    aggregateInfo.version(),
                    aggregate.getStateClass()
            ));
        } else {
            throw new IllegalStateException("Class implementing Aggregate must be annotated with @AggregateInfo");
        }

        runtimeBuilder.setObjectMapper(objectMapper);

        applicationContext.getBeansOfType(CommandHandlerFunction.class).values().stream()
                // we only want the adapters for this Aggregate
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    CommandType<?> type = adapter.getCommandType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setCommandCreateHandler(adapter);
                        runtimeBuilder.addCommand(type);
                    } else {
                        runtimeBuilder.addCommandHandler(type, adapter);
                        runtimeBuilder.addCommand(type);
                    }
                });
        // get ExternalEventHandlers for this Aggregate
        applicationContext.getBeansOfType(EventHandlerFunction.class).values().stream()
                // we only want the adapters for this Aggregate
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setEventCreateHandler(adapter);
                        runtimeBuilder.addDomainEvent(type);
                    } else {
                        runtimeBuilder.addExternalEventHandler(type, adapter);
                        runtimeBuilder.addDomainEvent(type);
                    }
                });
        // EventSourcingHandlers
        applicationContext.getBeansOfType(EventSourcingHandlerFunction.class).values().stream()
                .filter(adapter -> adapter.getAggregate().equals(aggregate))
                .forEach(adapter -> {
                    DomainEventType<?> type = adapter.getEventType();
                    if (adapter.isCreate()) {
                        runtimeBuilder.setEventSourcingCreateHandler(adapter);
                        runtimeBuilder.addDomainEvent(type);
                    } else {
                        runtimeBuilder.addEventSourcingHandler(type, adapter);
                        runtimeBuilder.addDomainEvent(type);
                    }
                });

        return runtimeBuilder.setSchemaRegistryClient(schemaRegistryClient).build();
    }
}
