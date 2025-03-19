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

class MessageTemplateGeneratorTests {

    @Test
    void shouldGenerateEventTemplate() {
        // Given
        MessageTemplateGenerator.EventMetadata accountCreated = new MessageTemplateGenerator.EventMetadata(
            "AccountCreated",
            "Account Created Event",
            "1.0.0",
            "Event emitted when a user account is successfully created",
            List.of(
                "framework-developers"
            ),
            "schema.json",
            "Java",
            "https://github.com/elasticsoftwarefoundation/akces-framework/blob/main/test-apps/crypto-trading/aggregates/src/main/java/org/elasticsoftware/cryptotrading/aggregates/account/events/AccountCreatedEvent.java"
        );

        // When
        String result = MessageTemplateGenerator.generate(accountCreated);

        // Then
        String expected = """
            ---
            id: AccountCreated
            name: Account Created Event
            version: 1.0.0
            summary: Event emitted when a user account is successfully created
            owners:
                - framework-developers
            schemaPath: 'schema.json'
            repository:
                language: Java
                url: https://github.com/elasticsoftwarefoundation/akces-framework/blob/main/test-apps/crypto-trading/aggregates/src/main/java/org/elasticsoftware/cryptotrading/aggregates/account/events/AccountCreatedEvent.java
            ---""";

        assertEquals(expected, result);
    }

    @Test
    void shouldHandleMultipleOwners() {
        // Given
        MessageTemplateGenerator.EventMetadata event = new MessageTemplateGenerator.EventMetadata(
            "MultiOwnerEvent",
            "Multi Owner Event",
            "1.0.0",
            "Event with multiple owners",
            List.of(
                "jwijgerd",
                "framework-developers"
            ),
            "schema.json",
            "Java",
            "https://github.com/example/repo"
        );

        // When
        String result = MessageTemplateGenerator.generate(event);

        // Then
        String expected = """
            ---
            id: MultiOwnerEvent
            name: Multi Owner Event
            version: 1.0.0
            summary: Event with multiple owners
            owners:
                - jwijgerd
                - framework-developers
            schemaPath: 'schema.json'
            repository:
                language: Java
                url: https://github.com/example/repo
            ---""";

        assertEquals(expected, result);
    }
}
