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

package org.elasticsoftware.akcestest.aggregate.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import jakarta.inject.Inject;
import org.elasticsoftware.akces.aggregate.AggregateRuntime;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.aggregate.CommandHandlerFunction;
import org.elasticsoftware.akces.aggregate.EventHandlerFunction;
import org.elasticsoftware.akces.aggregate.EventSourcingHandlerFunction;
import org.elasticsoftware.akces.protocol.*;
import org.elasticsoftware.akcestest.aggregate.account.AccountCreatedEvent;
import org.elasticsoftware.akcestest.schemas.AccountCreatedEventV2;
import org.elasticsoftware.akcestest.schemas.AccountCreatedEventV3;
import org.elasticsoftware.akcestest.schemas.NotCompatibleAccountCreatedEventV4;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

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
        assertEquals(3, applicationContext.getBeansOfType(CommandHandlerFunction.class).size());
        assertEquals(1, applicationContext.getBeansOfType(EventHandlerFunction.class).size());
        assertEquals(3, applicationContext.getBeansOfType(EventSourcingHandlerFunction.class).size());
        Assertions.assertNotNull(applicationContext.getBean("Wallet_ch_create_CreateWallet_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_ch_credit_CreditWallet_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_ch_makeReservation_ReserveAmount_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_eh_create_AccountCreated_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_esh_create_WalletCreated_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_esh_createBalance_BalanceCreated_1"));
        Assertions.assertNotNull(applicationContext.getBean("Wallet_esh_credit_WalletCredited_1"));
    }

    @Test
    @Order(1)
    public void testValidateDomainEventsWithMissingExternalDomainEventSchema() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory", AggregateRuntime.class);
        assertThrows(IllegalStateException.class, () -> {
            for (DomainEventType<?> domainEventType : walletAggregate.getAllDomainEventTypes()) {
                walletAggregate.registerAndValidate(domainEventType);
            }
        });
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    public void testValidateDomainEvents() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory",AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("domainevents.AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, AccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getAllDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    public void testValidateDomainEventsWithExistingSchemas() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory",AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("domainevents.AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, AccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        schemaRegistryClient.register("domainevents.WalletCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCreated", 1, WalletCreatedEvent.class, true, false, false)),
                1,
                -1);
        schemaRegistryClient.register("domainevents.WalletCredited",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCredited", 1, WalletCreditedEvent.class, false, false, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getAllDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        System.out.println(schemaRegistryClient.getAllSubjects());
    }

    @Test
    public void testValidateDomainEventsWithExistingSchemasAndExternalEventSubset() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory",AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("domainevents.AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, ExternalAccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        schemaRegistryClient.register("domainevents.WalletCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCreated", 1, WalletCreatedEvent.class, true, false, false)),
                1,
                -1);
        schemaRegistryClient.register("domainevents.WalletCredited",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCredited", 1, WalletCreditedEvent.class, false, false, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getAllDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
    }

    @Test
    public void testValidateDomainEventsWithExistingSchemasAndInvalidExternalEvent() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory",AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("domainevents.AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, ExternalAccountCreatedEvent.class, true, false, false)),
                1,
                -1);
        schemaRegistryClient.register("domainevents.WalletCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCreated", 1, WalletCreatedEvent.class, true, false, false)),
                1,
                -1);
        schemaRegistryClient.register("domainevents.WalletCredited",
                walletAggregate.generateJsonSchema(new DomainEventType<>("WalletCredited", 1, WalletCreditedEvent.class, false, false, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getAllDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        Assertions.assertThrows(IllegalStateException.class, () ->
                walletAggregate.registerAndValidate(new DomainEventType<>("AccountCreated", 1, InvalidAccountCreatedEvent.class, true, true, false)));
    }

    @Test
    public void testRegisterAndValidateMultipleVersionsOfEvent() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory",AggregateRuntime.class);
        walletAggregate.registerAndValidate(new DomainEventType<>("TestAccountCreated", 1, org.elasticsoftware.akcestest.schemas.AccountCreatedEvent.class, true, false, false));
        walletAggregate.registerAndValidate(new DomainEventType<>("TestAccountCreated", 2, AccountCreatedEventV2.class, true, false, false));
        walletAggregate.registerAndValidate(new DomainEventType<>("TestAccountCreated", 3, AccountCreatedEventV3.class, true, false, false));
        List<ParsedSchema> registeredSchemas = schemaRegistryClient.getSchemas("domainevents.TestAccountCreated",false, false);
        assertEquals(3, registeredSchemas.size());
    }

    @Test
    public void testRegisterAndValidateMultipleVersionsOfEventWithSkippedVersion() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory",AggregateRuntime.class);
        walletAggregate.registerAndValidate(new DomainEventType<>("AnotherTestAccountCreated", 1, org.elasticsoftware.akcestest.schemas.AccountCreatedEvent.class, true, false, false));
        Assertions.assertThrows(IllegalStateException.class, () ->
                walletAggregate.registerAndValidate(new DomainEventType<>("AnotherTestAccountCreated", 3, AccountCreatedEventV3.class, true, false, false)));
    }

    @Test
    public void testRegisterAndValidateMultipleVersionsOfEventWithNonCompatibleEvent() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory",AggregateRuntime.class);
        walletAggregate.registerAndValidate(new DomainEventType<>("YetAnotherTestAccountCreated", 1, org.elasticsoftware.akcestest.schemas.AccountCreatedEvent.class, true, false, false));
        walletAggregate.registerAndValidate(new DomainEventType<>("YetAnotherTestAccountCreated", 2, AccountCreatedEventV2.class, true, false, false));
        walletAggregate.registerAndValidate(new DomainEventType<>("YetAnotherTestAccountCreated", 3, AccountCreatedEventV3.class, true, false, false));
        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> {
            walletAggregate.registerAndValidate(new DomainEventType<>("YetAnotherTestAccountCreated", 4, NotCompatibleAccountCreatedEventV4.class, true, false, false));
        });
        assertEquals("New Schema is not backwards compatible with previous versions for DomainEvent [YetAnotherTestAccountCreated:4]", exception.getMessage());
    }

    @Test
    public void testCreateWalletByCommand() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory",AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("domainevents.AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, ExternalAccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getAllDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        String tenantId = "tenant1";
        String aggregateId = "d43a3afc-3e5a-11ed-b878-0242ac120002";
        String correlationId = "01e04622-3e5b-11ed-b878-0242ac120002";
        List<ProtocolRecord> producedRecords = new ArrayList<>();
        List<DomainEventRecord> indexedEvents = new ArrayList<>();
        walletAggregate.handleCommandRecord(
                new CommandRecord(
                        tenantId,
                        "CreateWallet",
                        1,
                        objectMapper.writeValueAsBytes(
                                new CreateWalletCommand(aggregateId, "EUR")),
                        PayloadEncoding.JSON,
                        aggregateId,
                        correlationId,
                        null),
                producedRecords::add,
                (eventRecord, index) -> indexedEvents.add(eventRecord),
                () -> null
        );
        assertEquals(4, producedRecords.size());
        AggregateStateRecord actualRecord = (AggregateStateRecord) producedRecords.get(0);
        AggregateStateRecord expectedRecord = new AggregateStateRecord(
                tenantId,
                "Wallet",
                1,
                objectMapper.writeValueAsBytes(new WalletState(aggregateId, new ArrayList<>())),
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
        assertArrayEquals(objectMapper.writeValueAsBytes(new WalletCreatedEvent(aggregateId)), actual.payload());
        assertEquals(PayloadEncoding.JSON, actual.encoding());
        assertEquals("WalletCreated", actual.name());
        assertEquals(1, actual.version());

        // now we should have added the balance
        actualRecord = (AggregateStateRecord) producedRecords.get(2);
        expectedRecord = new AggregateStateRecord(
                tenantId,
                "Wallet",
                1,
                objectMapper.writeValueAsBytes(new WalletState(aggregateId, List.of(new WalletState.Balance("EUR", BigDecimal.ZERO)))),
                PayloadEncoding.JSON,
                aggregateId,
                correlationId,
                2);
        assertEquals(expectedRecord.generation(), actualRecord.generation());
        assertEquals(expectedRecord.aggregateId(), actualRecord.aggregateId());
        assertEquals(expectedRecord.correlationId(), actualRecord.correlationId());
        assertArrayEquals(expectedRecord.payload(), actualRecord.payload());
        assertEquals(expectedRecord.encoding(), actualRecord.encoding());
        assertEquals(expectedRecord.name(), actualRecord.name());
        assertEquals(expectedRecord.version(), actualRecord.version());

        actual = (DomainEventRecord) producedRecords.get(3);

        assertEquals(2, actual.generation());
        assertEquals(aggregateId, actual.aggregateId());
        assertEquals(correlationId, actual.correlationId());
        assertArrayEquals(objectMapper.writeValueAsBytes(new BalanceCreatedEvent(aggregateId, "EUR")), actual.payload());
        assertEquals(PayloadEncoding.JSON, actual.encoding());
        assertEquals("BalanceCreated", actual.name());
        assertEquals(1, actual.version());

    }

    @Test
    public void testIndexWalletEventsFromCommand() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory", AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("domainevents.AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, ExternalAccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getAllDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        String tenantId = "tenant1";
        String aggregateId = "d43a3afc-3e5a-11ed-b878-0242ac120002";
        String correlationId = "01e04622-3e5b-11ed-b878-0242ac120002";
        List<ProtocolRecord> producedRecords = new ArrayList<>();
        List<DomainEventRecord> indexedEvents = new ArrayList<>();
        walletAggregate.handleCommandRecord(
                new CommandRecord(
                        tenantId,
                        "CreateWallet",
                        1,
                        objectMapper.writeValueAsBytes(
                                new CreateWalletCommand(aggregateId, "EUR")),
                        PayloadEncoding.JSON,
                        aggregateId,
                        correlationId,
                        null),
                producedRecords::add,
                (eventRecord, index) -> indexedEvents.add(eventRecord),
                () -> null
        );
        // we should index 2 events: WalletCreated and BalanceCreated
        assertEquals(2, indexedEvents.size());
        DomainEventRecord actual = indexedEvents.get(0);

        assertEquals(1, actual.generation());
        assertEquals(aggregateId, actual.aggregateId());
        assertEquals(correlationId, actual.correlationId());
        assertArrayEquals(objectMapper.writeValueAsBytes(new WalletCreatedEvent(aggregateId)), actual.payload());
        assertEquals(PayloadEncoding.JSON, actual.encoding());
        assertEquals("WalletCreated", actual.name());
        assertEquals(1, actual.version());

        actual = (DomainEventRecord) indexedEvents.get(1);

        assertEquals(2, actual.generation());
        assertEquals(aggregateId, actual.aggregateId());
        assertEquals(correlationId, actual.correlationId());
        assertArrayEquals(objectMapper.writeValueAsBytes(new BalanceCreatedEvent(aggregateId, "EUR")), actual.payload());
        assertEquals(PayloadEncoding.JSON, actual.encoding());
        assertEquals("BalanceCreated", actual.name());
        assertEquals(1, actual.version());
    }

    @Test
    public void testCreateWalletByExternalDomainEvent() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory",AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("domainevents.AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, ExternalAccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getAllDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        String tenantId = "tenant1";
        String aggregateId = "d43a3afc-3e5a-11ed-b878-0242ac120002";
        String correlationId = "01e04622-3e5b-11ed-b878-0242ac120002";
        List<ProtocolRecord> producedRecords = new ArrayList<>();
        List<DomainEventRecord> indexedEvents = new ArrayList<>();
        walletAggregate.handleExternalDomainEventRecord(
                new DomainEventRecord(
                        tenantId,
                        "AccountCreated",
                        1,
                        objectMapper.writeValueAsBytes(
                                new AccountCreatedEvent(aggregateId, "NL","7hdU_mfA_bvkRRgCekTZ0A==","ioxbJd-hSLj6KNJpdYzN4g==","6KLIDo3Ii2d-oVZtiv1h3OYNgW5lXYAnCnxPK2fprUU=")),
                        PayloadEncoding.JSON,
                        aggregateId,
                        correlationId,
                        1),
                producedRecords::add,
                (eventRecord, index) -> indexedEvents.add(eventRecord),
                () -> null
        );
        assertEquals(4, producedRecords.size());
        AggregateStateRecord actualRecord = (AggregateStateRecord) producedRecords.get(0);
        AggregateStateRecord expectedRecord = new AggregateStateRecord(
                tenantId,
                "Wallet",
                1,
                objectMapper.writeValueAsBytes(new WalletState(aggregateId, new ArrayList<>())),
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
        assertArrayEquals(objectMapper.writeValueAsBytes(new WalletCreatedEvent(aggregateId)), actual.payload());
        assertEquals(PayloadEncoding.JSON, actual.encoding());
        assertEquals("WalletCreated", actual.name());
        assertEquals(1, actual.version());

        // now we should have added the balance
        actualRecord = (AggregateStateRecord) producedRecords.get(2);
        expectedRecord = new AggregateStateRecord(
                tenantId,
                "Wallet",
                1,
                objectMapper.writeValueAsBytes(new WalletState(aggregateId, List.of(new WalletState.Balance("EUR", BigDecimal.ZERO)))),
                PayloadEncoding.JSON,
                aggregateId,
                correlationId,
                2);
        assertEquals(expectedRecord.generation(), actualRecord.generation());
        assertEquals(expectedRecord.aggregateId(), actualRecord.aggregateId());
        assertEquals(expectedRecord.correlationId(), actualRecord.correlationId());
        assertArrayEquals(expectedRecord.payload(), actualRecord.payload());
        assertEquals(expectedRecord.encoding(), actualRecord.encoding());
        assertEquals(expectedRecord.name(), actualRecord.name());
        assertEquals(expectedRecord.version(), actualRecord.version());

        actual = (DomainEventRecord) producedRecords.get(3);

        assertEquals(2, actual.generation());
        assertEquals(aggregateId, actual.aggregateId());
        assertEquals(correlationId, actual.correlationId());
        assertArrayEquals(objectMapper.writeValueAsBytes(new BalanceCreatedEvent(aggregateId, "EUR")), actual.payload());
        assertEquals(PayloadEncoding.JSON, actual.encoding());
        assertEquals("BalanceCreated", actual.name());
        assertEquals(1, actual.version());

    }

    @Test
    public void testIndexWalletEventsByExternalDomainEvent() throws Exception {
        AggregateRuntime walletAggregate = applicationContext.getBean("WalletAggregateRuntimeFactory", AggregateRuntime.class);
        // need to register the external domainevent
        schemaRegistryClient.register("domainevents.AccountCreated",
                walletAggregate.generateJsonSchema(new DomainEventType<>("AccountCreated", 1, ExternalAccountCreatedEvent.class, true, true, false)),
                1,
                -1);
        for (DomainEventType<?> domainEventType : walletAggregate.getAllDomainEventTypes()) {
            walletAggregate.registerAndValidate(domainEventType);
        }
        String tenantId = "tenant1";
        String aggregateId = "d43a3afc-3e5a-11ed-b878-0242ac120002";
        String correlationId = "01e04622-3e5b-11ed-b878-0242ac120002";
        List<ProtocolRecord> producedRecords = new ArrayList<>();
        List<DomainEventRecord> indexedEvents = new ArrayList<>();
        walletAggregate.handleExternalDomainEventRecord(
                new DomainEventRecord(
                        tenantId,
                        "AccountCreated",
                        1,
                        objectMapper.writeValueAsBytes(
                                new AccountCreatedEvent(aggregateId, "NL", "7hdU_mfA_bvkRRgCekTZ0A==", "ioxbJd-hSLj6KNJpdYzN4g==", "6KLIDo3Ii2d-oVZtiv1h3OYNgW5lXYAnCnxPK2fprUU=")),
                        PayloadEncoding.JSON,
                        aggregateId,
                        correlationId,
                        1),
                producedRecords::add,
                (eventRecord, index) -> indexedEvents.add(eventRecord),
                () -> null
        );
        // we should index 2 events: WalletCreated and BalanceCreated
        assertEquals(2, indexedEvents.size());
    }
}
