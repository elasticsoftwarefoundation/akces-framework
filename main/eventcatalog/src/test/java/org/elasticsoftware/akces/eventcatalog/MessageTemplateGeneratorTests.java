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
            repository:
                language: Java
                url: https://github.com/elasticsoftwarefoundation/akces-framework/blob/main/test-apps/crypto-trading/aggregates/src/main/java/org/elasticsoftware/cryptotrading/aggregates/account/events/AccountCreatedEvent.java
            ---
            import Footer from '@catalog/components/footer.astro';

            ## Architecture diagram
            
            <NodeGraph />
            
            ## JSON Schema
            
            <Schema schemaPath="schema.json" />
            
            <Footer />""";

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
            repository:
                language: Java
                url: https://github.com/example/repo
            ---
            import Footer from '@catalog/components/footer.astro';

            ## Architecture diagram
            
            <NodeGraph />
            
            ## JSON Schema
            
            <Schema schemaPath="schema.json" />
            
            <Footer />""";

        assertEquals(expected, result);
    }

    @Test
    void shouldHandleSingleOwner() {
        // Given
        MessageTemplateGenerator.EventMetadata event = new MessageTemplateGenerator.EventMetadata(
            "SingleOwnerEvent",
            "Single Owner Event",
            "2.0.0",
            "Event with single owner",
            List.of("single-owner"),
            "Kotlin",
            "https://github.com/example/single"
        );

        // When
        String result = MessageTemplateGenerator.generate(event);

        // Then
        String expected = """
            ---
            id: SingleOwnerEvent
            name: Single Owner Event
            version: 2.0.0
            summary: Event with single owner
            owners:
                - single-owner
            repository:
                language: Kotlin
                url: https://github.com/example/single
            ---
            import Footer from '@catalog/components/footer.astro';

            ## Architecture diagram
            
            <NodeGraph />
            
            ## JSON Schema
            
            <Schema schemaPath="schema.json" />
            
            <Footer />""";

        assertEquals(expected, result);
    }

    @Test
    void shouldGenerateWithDifferentVersion() {
        // Given
        MessageTemplateGenerator.EventMetadata event = new MessageTemplateGenerator.EventMetadata(
            "VersionedEvent",
            "Versioned Event",
            "3.2.1",
            "Event with specific version",
            List.of("versioning-team"),
            "Python",
            "https://github.com/example/versioned"
        );

        // When
        String result = MessageTemplateGenerator.generate(event);

        // Then
        assertTrue(result.contains("version: 3.2.1"));
        assertTrue(result.contains("language: Python"));
    }

    @Test
    void shouldGenerateWithSpecialCharactersInSummary() {
        // Given
        MessageTemplateGenerator.EventMetadata event = new MessageTemplateGenerator.EventMetadata(
            "SpecialEvent",
            "Special Event",
            "1.0.0",
            "Event with special chars: & < > \"quotes\" 'apostrophes'",
            List.of("special-team"),
            "Java",
            "https://github.com/example/special"
        );

        // When
        String result = MessageTemplateGenerator.generate(event);

        // Then
        assertTrue(result.contains("Special Event"));
        assertTrue(result.contains("Event with special chars: & < > \"quotes\" 'apostrophes'"));
    }
}
