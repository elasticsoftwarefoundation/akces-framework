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

package org.elasticsoftware.akces.eventcatalog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class ServiceTemplateGeneratorTests {
    @Test
    void shouldGenerateServiceTemplate() {
        // Given
        ServiceTemplateGenerator.ServiceMetadata accountAggregate = new ServiceTemplateGenerator.ServiceMetadata(
                "AccountAggregate",
                "1.0.0",
                "Account Aggregate",
                "Core aggregate that manages user account lifecycle",
                List.of(
                        "jwijgerd",
                        "framework-developers"
                ),
                List.of(new ServiceTemplateGenerator.Message("CreateAccount", "1.0.0")),
                List.of(new ServiceTemplateGenerator.Message("AccountCreated", "1.0.0")),
                "Java",
                "https://github.com/elasticsoftwarefoundation/akces-framework/test-apps/crypto-trading/eventcatalog/domains/Accounts/services/AccountAggregate"
        );

        // When
        String result = ServiceTemplateGenerator.generate(accountAggregate);

        // Then
        assertTrue(result.contains("id: AccountAggregate"));
        assertTrue(result.contains("version: 1.0.0"));
        assertTrue(result.contains("name: Account Aggregate"));
        assertTrue(result.contains("Core aggregate that manages user account lifecycle"));

        // Verify owners
        assertTrue(result.contains("- jwijgerd"));
        assertTrue(result.contains("- framework-developers"));

        // Verify commands and events
        assertTrue(result.contains("- id: CreateAccount"));
        assertTrue(result.contains("- id: AccountCreated"));

        // Verify repository info
        assertTrue(result.contains("language: Java"));
        assertTrue(result.contains("url: https://github.com/elasticsoftwarefoundation/akces-framework/test-apps/crypto-trading/eventcatalog/domains/Accounts/services/AccountAggregate"));
    }

    @Test
    void shouldHandleEmptyLists() {
        // Given
        ServiceTemplateGenerator.ServiceMetadata emptyListsAggregate = new ServiceTemplateGenerator.ServiceMetadata(
                "EmptyListsAggregate",
                "1.0.0",
                "Empty Lists Aggregate",
                "Test aggregate with empty lists",
                List.of("tester"),
                List.of(),
                List.of(),
                "Java",
                "https://example.com/repo"
        );

        // When
        String result = ServiceTemplateGenerator.generate(emptyListsAggregate);

        // Then
        assertTrue(result.contains("receives:\n  []"));
        assertTrue(result.contains("sends:\n  []"));
    }

    @Test
    void shouldGenerateCorrectYamlFormat() {
        // Given
        ServiceTemplateGenerator.ServiceMetadata minimalAggregate = new ServiceTemplateGenerator.ServiceMetadata(
                "MinimalAggregate",
                "1.0.0",
                "Minimal Aggregate",
                "Test minimal aggregate",
                List.of("tester"),
                List.of(new ServiceTemplateGenerator.Message("TestCommand", "1.0.0")),
                List.of(new ServiceTemplateGenerator.Message("TestEvent", "1.0.0")),
                "Java",
                "https://example.com/repo"
        );

        // When
        String result = ServiceTemplateGenerator.generate(minimalAggregate);

        // Then - verify the exact format matches the expected YAML structure
        String expected = """
            ---
            id: MinimalAggregate
            version: 1.0.0
            name: Minimal Aggregate
            summary: Test minimal aggregate
            owners:
                - tester
            receives:
              - id: TestCommand
                version: 1.0.0
            sends:
              - id: TestEvent
                version: 1.0.0
            repository:
              language: Java
              url: https://example.com/repo
            ---
            import Footer from '@catalog/components/footer.astro';

            ## Architecture diagram
            
            <NodeGraph />
            
            <Footer />""";

        assertEquals(expected, result);
    }

    @Test
    void shouldHandleMultipleReceivesAndSends() {
        // Given
        ServiceTemplateGenerator.ServiceMetadata multiCommandAggregate = new ServiceTemplateGenerator.ServiceMetadata(
                "PaymentAggregate",
                "1.0.0",
                "Payment Aggregate",
                "Aggregate that manages payment processing",
                List.of(
                        "framework-developers"
                ),
                List.of(
                        new ServiceTemplateGenerator.Message("InitiatePayment", "1.0.0"),
                        new ServiceTemplateGenerator.Message("CancelPayment", "1.0.0"),
                        new ServiceTemplateGenerator.Message("RefundPayment", "1.0.0")
                ),
                List.of(
                        new ServiceTemplateGenerator.Message("PaymentInitiated", "1.0.0"),
                        new ServiceTemplateGenerator.Message("PaymentCancelled", "1.0.0")
                ),
                "Java",
                "https://github.com/elasticsoftwarefoundation/akces-framework/blob/main/test-apps/crypto-trading/aggregates/src/main/java/org/elasticsoftware/cryptotrading/aggregates/payment/Payment.java"
        );

        // When
        String result = ServiceTemplateGenerator.generate(multiCommandAggregate);

        // Then
        String expected = """
            ---
            id: PaymentAggregate
            version: 1.0.0
            name: Payment Aggregate
            summary: Aggregate that manages payment processing
            owners:
                - framework-developers
            receives:
              - id: InitiatePayment
                version: 1.0.0
              - id: CancelPayment
                version: 1.0.0
              - id: RefundPayment
                version: 1.0.0
            sends:
              - id: PaymentInitiated
                version: 1.0.0
              - id: PaymentCancelled
                version: 1.0.0
            repository:
              language: Java
              url: https://github.com/elasticsoftwarefoundation/akces-framework/blob/main/test-apps/crypto-trading/aggregates/src/main/java/org/elasticsoftware/cryptotrading/aggregates/payment/Payment.java
            ---
            import Footer from '@catalog/components/footer.astro';

            ## Architecture diagram
            
            <NodeGraph />
            
            <Footer />""";

        assertEquals(expected, result);
    }

    @Test
    void shouldHandleSingleCommandAndEvent() {
        // Given
        ServiceTemplateGenerator.ServiceMetadata singleAggregate = new ServiceTemplateGenerator.ServiceMetadata(
                "SimpleAggregate",
                "1.0.0",
                "Simple Aggregate",
                "Simplest possible aggregate",
                List.of("simple-team"),
                List.of(new ServiceTemplateGenerator.Message("SimpleCommand", "1.0.0")),
                List.of(new ServiceTemplateGenerator.Message("SimpleEvent", "1.0.0")),
                "Go",
                "https://example.com/simple"
        );

        // When
        String result = ServiceTemplateGenerator.generate(singleAggregate);

        // Then
        assertTrue(result.contains("id: SimpleAggregate"));
        assertTrue(result.contains("version: 1.0.0"));
        assertTrue(result.contains("- id: SimpleCommand"));
        assertTrue(result.contains("- id: SimpleEvent"));
        assertTrue(result.contains("language: Go"));
    }

    @Test
    void shouldHandleComplexNames() {
        // Given
        ServiceTemplateGenerator.ServiceMetadata complexAggregate = new ServiceTemplateGenerator.ServiceMetadata(
                "User-Account_Aggregate.v2",
                "2.3.4",
                "User Account Aggregate (Version 2)",
                "Aggregate with complex naming",
                List.of("user-team", "account-team"),
                List.of(new ServiceTemplateGenerator.Message("Create-User_Command", "2.0.0")),
                List.of(new ServiceTemplateGenerator.Message("User_Created-Event", "2.0.0")),
                "TypeScript",
                "https://github.com/example/user-account"
        );

        // When
        String result = ServiceTemplateGenerator.generate(complexAggregate);

        // Then
        assertTrue(result.contains("id: User-Account_Aggregate.v2"));
        assertTrue(result.contains("version: 2.3.4"));
        assertTrue(result.contains("Create-User_Command"));
        assertTrue(result.contains("User_Created-Event"));
    }

    @Test
    void shouldGenerateWithLongDescription() {
        // Given
        ServiceTemplateGenerator.ServiceMetadata longDescAggregate = new ServiceTemplateGenerator.ServiceMetadata(
                "DetailedAggregate",
                "1.0.0",
                "Detailed Aggregate",
                "This is a very detailed aggregate that handles multiple complex business operations and orchestrates various workflows across different bounded contexts within the system architecture",
                List.of("detailed-team"),
                List.of(new ServiceTemplateGenerator.Message("DetailCommand", "1.0.0")),
                List.of(new ServiceTemplateGenerator.Message("DetailEvent", "1.0.0")),
                "Java",
                "https://example.com/detailed"
        );

        // When
        String result = ServiceTemplateGenerator.generate(longDescAggregate);

        // Then
        assertTrue(result.contains("Detailed Aggregate"));
        assertTrue(result.contains("complex business operations"));
        assertTrue(result.contains("bounded contexts"));
    }

    @Test
    void shouldHandleEmptyReceivesOnly() {
        // Given
        ServiceTemplateGenerator.ServiceMetadata emptyReceivesAggregate = new ServiceTemplateGenerator.ServiceMetadata(
                "ReadOnlyAggregate",
                "1.0.0",
                "Read Only Aggregate",
                "Only sends events",
                List.of("readonly-team"),
                List.of(),
                List.of(new ServiceTemplateGenerator.Message("DataLoadedEvent", "1.0.0")),
                "Java",
                "https://example.com/readonly"
        );

        // When
        String result = ServiceTemplateGenerator.generate(emptyReceivesAggregate);

        // Then
        assertTrue(result.contains("receives:\n  []"));
        assertTrue(result.contains("- id: DataLoadedEvent"));
    }

    @Test
    void shouldHandleEmptySendsOnly() {
        // Given
        ServiceTemplateGenerator.ServiceMetadata emptySendsAggregate = new ServiceTemplateGenerator.ServiceMetadata(
                "WriteOnlyAggregate",
                "1.0.0",
                "Write Only Aggregate",
                "Only receives commands",
                List.of("writeonly-team"),
                List.of(new ServiceTemplateGenerator.Message("ProcessCommand", "1.0.0")),
                List.of(),
                "Java",
                "https://example.com/writeonly"
        );

        // When
        String result = ServiceTemplateGenerator.generate(emptySendsAggregate);

        // Then
        assertTrue(result.contains("- id: ProcessCommand"));
        assertTrue(result.contains("sends:\n  []"));
    }
}
