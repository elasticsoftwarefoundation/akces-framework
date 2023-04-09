package org.elasticsoftware.akces.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.elasticsoftware.akces.annotations.*;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.kafka.KafkaAggregateRuntime;
import org.elasticsoftware.akces.protocol.*;
import org.elasticsoftware.akces.schemas.AccountCreatedEventV2;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


@MicronautTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WalletTests {
    @Inject
    ApplicationContext applicationContext;
    @Inject
    ObjectMapper objectMapper;
    @Inject
    SchemaRegistryClient schemaRegistryClient;

    @Test
    public void testFindBeans() {
        Assertions.assertTrue(!applicationContext.getBeansOfType(CommandHandlerFunction.class).isEmpty());
        applicationContext.getBeansOfType(CommandHandlerFunction.class).forEach(commandHandlerFunction -> System.out.println(commandHandlerFunction.getClass()));
        applicationContext.getBeanDefinitions(CommandHandlerFunction.class).forEach(beanDefinition -> {
            System.out.println(beanDefinition.toString());
            AnnotationValue<CommandHandler> annotationValue = beanDefinition.findAnnotation(CommandHandler.class).orElse(null);
            Boolean create = annotationValue.booleanValue("create").orElse(annotationValue.get("create", ConversionContext.of(Boolean.class)).orElse(null));
            System.out.println("Create = "+create.toString());
        });
        applicationContext.getBeanDefinitions(Aggregate.class).forEach(beanDefinition -> System.out.println(beanDefinition.toString()));
    }

    @Test
    @Order(1)
    public void testValidateDomainEventsWithMissingExternalDomainEventSchema() throws Exception {
        KafkaAggregateRuntime walletAggregate = applicationContext.getBean(KafkaAggregateRuntime.class);
        assertThrows(IllegalStateException.class, () -> {
            for (DomainEventType<?> domainEventType : walletAggregate.getDomainEventTypes()) {
                walletAggregate.registerAndValidate(domainEventType);
            }
        });
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    public void testValidateDomainEvents() throws Exception {
        KafkaAggregateRuntime walletAggregate = applicationContext.getBean(KafkaAggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, AccountCreatedEventV2.class, true, true)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    public void testValidateDomainEventsWithExistingSchemas() throws Exception {
        KafkaAggregateRuntime walletAggregate = applicationContext.getBean(KafkaAggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, AccountCreatedEventV2.class, true, true)),
                1,
                -1);
        schemaRegistryClient.register("WalletCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCreated", 1, WalletCreatedEvent.class, true, false)),
                1,
                -1);
        schemaRegistryClient.register("WalletCredited",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCredited", 1, WalletCreditedEvent.class, false, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    public void testCreateWalletByCommand() throws IOException {
        KafkaAggregateRuntime walletAggregate = applicationContext.getBean(KafkaAggregateRuntime.class);
        String aggregateId = "d43a3afc-3e5a-11ed-b878-0242ac120002";
        String correlationId = "01e04622-3e5b-11ed-b878-0242ac120002";
        List<ProtocolRecord> producedRecords = new ArrayList<>();
        walletAggregate.handleCommandRecord(
                new CommandRecord("CreateWallet",
                        1,
                        objectMapper.writeValueAsBytes(
                                new CreateWalletCommand(aggregateId, "EUR")),
                        PayloadEncoding.JSON,
                        aggregateId,
                        correlationId),
                producedRecords::add,
                () -> null
        );
        assertEquals(2, producedRecords.size());
        AggregateStateRecord actualRecord = (AggregateStateRecord) producedRecords.get(0);
        AggregateStateRecord expectedRecord = new AggregateStateRecord("Wallet",
                1,
                objectMapper.writeValueAsBytes(new WalletState(aggregateId, "EUR", BigDecimal.ZERO)),
                PayloadEncoding.JSON,
                aggregateId,
                correlationId,
                1);
        assertEquals(expectedRecord.generation(), actualRecord.generation());
        assertEquals(expectedRecord.aggregateId(), actualRecord.aggregateId());
        assertEquals(expectedRecord.correlationId(), actualRecord.correlationId());
        assertArrayEquals(expectedRecord.payload(), actualRecord.payload());
        assertEquals(expectedRecord.encoding(), actualRecord.encoding());
        assertEquals(expectedRecord.name(), actualRecord.name());
        assertEquals(expectedRecord.version(), actualRecord.version());

        DomainEventRecord actual = (DomainEventRecord) producedRecords.get(1);

        assertEquals(1, actual.generation());
        assertEquals(aggregateId, actual.aggregateId());
        assertEquals(correlationId, actual.correlationId());
        assertArrayEquals(objectMapper.writeValueAsBytes(new WalletCreatedEvent(aggregateId, "EUR", BigDecimal.ZERO)), actual.payload());
        assertEquals(PayloadEncoding.JSON, actual.encoding());
        assertEquals("WalletCreated", actual.name());
        assertEquals(1, actual.version());
    }

    @Test
    public void testCreateWalletByExternalDomainEvent() throws IOException {
        KafkaAggregateRuntime walletAggregate = applicationContext.getBean(KafkaAggregateRuntime.class);
        String aggregateId = "d43a3afc-3e5a-11ed-b878-0242ac120002";
        String correlationId = "01e04622-3e5b-11ed-b878-0242ac120002";
        List<ProtocolRecord> producedRecords = new ArrayList<>();
        walletAggregate.handleExternalDomainEventRecord(
                new DomainEventRecord("AccountCreated",
                        1,
                        objectMapper.writeValueAsBytes(
                                new AccountCreatedEvent(aggregateId, "NL")),
                        PayloadEncoding.JSON,
                        aggregateId,
                        correlationId,
                        1),
                producedRecords::add,
                () -> null
        );
        assertEquals(2, producedRecords.size());
        AggregateStateRecord actualRecord = (AggregateStateRecord) producedRecords.get(0);
        AggregateStateRecord expectedRecord = new AggregateStateRecord("Wallet",
                1,
                objectMapper.writeValueAsBytes(new WalletState(aggregateId, "EUR", BigDecimal.ZERO)),
                PayloadEncoding.JSON,
                aggregateId,
                correlationId,
                1);
        assertEquals(expectedRecord.generation(), actualRecord.generation());
        assertEquals(expectedRecord.aggregateId(), actualRecord.aggregateId());
        assertEquals(expectedRecord.correlationId(), actualRecord.correlationId());
        assertArrayEquals(expectedRecord.payload(), actualRecord.payload());
        assertEquals(expectedRecord.encoding(), actualRecord.encoding());
        assertEquals(expectedRecord.name(), actualRecord.name());
        assertEquals(expectedRecord.version(), actualRecord.version());

        DomainEventRecord actual = (DomainEventRecord) producedRecords.get(1);

        assertEquals(1, actual.generation());
        assertEquals(aggregateId, actual.aggregateId());
        assertEquals(correlationId, actual.correlationId());
        assertArrayEquals(objectMapper.writeValueAsBytes(new WalletCreatedEvent(aggregateId, "EUR", BigDecimal.ZERO)), actual.payload());
        assertEquals(PayloadEncoding.JSON, actual.encoding());
        assertEquals("WalletCreated", actual.name());
        assertEquals(1, actual.version());
    }
}
