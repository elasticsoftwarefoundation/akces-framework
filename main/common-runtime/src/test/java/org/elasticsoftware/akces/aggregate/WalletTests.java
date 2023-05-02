package org.elasticsoftware.akces.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import jakarta.inject.Inject;
import org.elasticsoftware.akces.commands.CommandHandlerFunction;
import org.elasticsoftware.akces.events.EventHandlerFunction;
import org.elasticsoftware.akces.events.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.protocol.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


@SpringBootTest(classes = WalletConfiguration.class)
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
        assertEquals(2, applicationContext.getBeansOfType(CommandHandlerFunction.class).size());
        assertEquals(1, applicationContext.getBeansOfType(EventHandlerFunction.class).size());
        assertEquals(2, applicationContext.getBeansOfType(EventSourcingHandlerFunction.class).size());
        Assertions.assertNotNull(applicationContext.getBean("Wallet_ch_create_CreateWallet_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_ch_credit_CreditWallet_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_eh_create_AccountCreated_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_esh_create_WalletCreated_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_esh_credit_WalletCredited_1"));
    }

    @Test
    @Order(1)
    public void testValidateDomainEventsWithMissingExternalDomainEventSchema() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        assertThrows(IllegalStateException.class, () -> {
            for (DomainEventType<?> domainEventType : walletAggregate.getDomainEventTypes()) {
                walletAggregate.registerAndValidate(domainEventType);
            }
        });
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    public void testValidateDomainEvents() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, AccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    public void testValidateDomainEventsWithExistingSchemas() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, AccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        schemaRegistryClient.register("WalletCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCreated", 1, WalletCreatedEvent.class, true, false, false)),
                1,
                -1);
        schemaRegistryClient.register("WalletCredited",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCredited", 1, WalletCreditedEvent.class, false, false, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    public void testValidateDomainEventsWithExistingSchemasAndExternalEventSubset() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, ExternalAccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        schemaRegistryClient.register("WalletCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCreated", 1, WalletCreatedEvent.class, true, false, false)),
                1,
                -1);
        schemaRegistryClient.register("WalletCredited",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCredited", 1, WalletCreditedEvent.class, false, false, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
    }

    @Test
    public void testValidateDomainEventsWithExistingSchemasAndInvalidExternalEvent() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, ExternalAccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        schemaRegistryClient.register("WalletCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCreated", 1, WalletCreatedEvent.class, true, false, false)),
                1,
                -1);
        schemaRegistryClient.register("WalletCredited",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCredited", 1, WalletCreditedEvent.class, false, false, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        Assertions.assertThrows(IllegalStateException.class, () ->
                walletAggregate.registerAndValidate(new DomainEventType<>("AccountCreated", 1, InvalidAccountCreatedEvent.class, true, true, false)));
    }

    @Test
    public void testRegisterAndValidateMultipleVersionsOfEvent() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        walletAggregate.registerAndValidate(new DomainEventType<>("TestAccountCreated", 1, org.elasticsoftware.akces.schemas.AccountCreatedEvent.class, true, false, false));
        walletAggregate.registerAndValidate(new DomainEventType<>("TestAccountCreated", 2, org.elasticsoftware.akces.schemas.AccountCreatedEventV2.class, true, false, false));
        walletAggregate.registerAndValidate(new DomainEventType<>("TestAccountCreated", 3, org.elasticsoftware.akces.schemas.AccountCreatedEventV3.class, true, false, false));
        List<ParsedSchema> registeredSchemas = schemaRegistryClient.getSchemas("TestAccountCreated",false, false);
        assertEquals(3, registeredSchemas.size());
    }

    @Test
    public void testRegisterAndValidateMultipleVersionsOfEventWithSkippedVersion() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        walletAggregate.registerAndValidate(new DomainEventType<>("AnotherTestAccountCreated", 1, org.elasticsoftware.akces.schemas.AccountCreatedEvent.class, true, false, false));
        Assertions.assertThrows(IllegalStateException.class, () ->
                walletAggregate.registerAndValidate(new DomainEventType<>("AnotherTestAccountCreated", 3, org.elasticsoftware.akces.schemas.AccountCreatedEventV3.class, true, false, false)));
    }

    @Test
    public void testRegisterAndValidateMultipleVersionsOfEventWithNonCompatibleEvent() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        walletAggregate.registerAndValidate(new DomainEventType<>("YetAnotherTestAccountCreated", 1, org.elasticsoftware.akces.schemas.AccountCreatedEvent.class, true, false, false));
        walletAggregate.registerAndValidate(new DomainEventType<>("YetAnotherTestAccountCreated", 2, org.elasticsoftware.akces.schemas.AccountCreatedEventV2.class, true, false, false));
        walletAggregate.registerAndValidate(new DomainEventType<>("YetAnotherTestAccountCreated", 3, org.elasticsoftware.akces.schemas.AccountCreatedEventV3.class, true, false, false));
        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> {
            walletAggregate.registerAndValidate(new DomainEventType<>("YetAnotherTestAccountCreated", 4, org.elasticsoftware.akces.schemas.NotCompatibleAccountCreatedEventV4.class, true, false, false));
        });
        assertEquals("New Schema is not backwards compatible with previous versions for DomainEvent [YetAnotherTestAccountCreated:4]", exception.getMessage());
    }

    @Test
    public void testCreateWalletByCommand() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, ExternalAccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        String tenantId = "tenant1";
        String aggregateId = "d43a3afc-3e5a-11ed-b878-0242ac120002";
        String correlationId = "01e04622-3e5b-11ed-b878-0242ac120002";
        List<ProtocolRecord> producedRecords = new ArrayList<>();
        walletAggregate.handleCommandRecord(
                new CommandRecord(
                        tenantId,
                        "CreateWallet",
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
        AggregateStateRecord expectedRecord = new AggregateStateRecord(
                tenantId,
                "Wallet",
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
        AggregateRuntime walletAggregate = applicationContext.getBean(AggregateRuntime.class);
        String tenantId = "tenant1";
        String aggregateId = "d43a3afc-3e5a-11ed-b878-0242ac120002";
        String correlationId = "01e04622-3e5b-11ed-b878-0242ac120002";
        List<ProtocolRecord> producedRecords = new ArrayList<>();
        walletAggregate.handleExternalDomainEventRecord(
                new DomainEventRecord(
                        tenantId,
                        "AccountCreated",
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
        AggregateStateRecord expectedRecord = new AggregateStateRecord(
                tenantId,
                "Wallet",
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
