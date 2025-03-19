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
            summary: |
              Test minimal aggregate
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
            ---""";

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
            summary: |
              Aggregate that manages payment processing
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
            ---""";

        assertEquals(expected, result);
    }
}
