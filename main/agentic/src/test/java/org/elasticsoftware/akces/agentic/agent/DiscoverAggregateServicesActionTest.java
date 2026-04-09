/*
 * Copyright 2022 - 2026 The Original Authors
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

package org.elasticsoftware.akces.agentic.agent;

import org.elasticsoftware.akces.control.AggregateServiceRecord;
import org.elasticsoftware.akces.control.AggregateServiceCommandType;
import org.elasticsoftware.akces.control.AggregateServiceDomainEventType;
import org.elasticsoftware.akces.control.AggregateServiceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DiscoverAggregateServicesAction}.
 */
class DiscoverAggregateServicesActionTest {

    private final DiscoverAggregateServicesAction action = new DiscoverAggregateServicesAction();

    @Test
    void discoverServicesReturnsEmptyMessageForNullCollection() {
        String result = action.discoverServices(null);

        assertThat(result).contains("No aggregate services");
    }

    @Test
    void discoverServicesReturnsEmptyMessageForEmptyCollection() {
        String result = action.discoverServices(List.of());

        assertThat(result).contains("No aggregate services");
    }

    @Test
    void discoverServicesIncludesAggregateName() {
        AggregateServiceRecord record = createRecord(
                "Wallet",
                "wallet-commands",
                "wallet-events",
                AggregateServiceType.STANDARD,
                List.of(),
                List.of(),
                List.of()
        );

        String result = action.discoverServices(List.of(record));

        assertThat(result).contains("Wallet");
    }

    @Test
    void discoverServicesIncludesTopicNames() {
        AggregateServiceRecord record = createRecord(
                "Wallet",
                "wallet-commands",
                "wallet-events",
                AggregateServiceType.STANDARD,
                List.of(),
                List.of(),
                List.of()
        );

        String result = action.discoverServices(List.of(record));

        assertThat(result).contains("wallet-commands");
        assertThat(result).contains("wallet-events");
    }

    @Test
    void discoverServicesIncludesServiceType() {
        AggregateServiceRecord standardRecord = createRecord(
                "Wallet", "cmd", "evt",
                AggregateServiceType.STANDARD,
                List.of(), List.of(), List.of()
        );
        AggregateServiceRecord agenticRecord = createRecord(
                "Advisor", "cmd2", "evt2",
                AggregateServiceType.AGENTIC,
                List.of(), List.of(), List.of()
        );

        String result = action.discoverServices(List.of(standardRecord, agenticRecord));

        assertThat(result).contains("STANDARD");
        assertThat(result).contains("AGENTIC");
    }

    @Test
    void discoverServicesIncludesSupportedCommands() {
        AggregateServiceRecord record = createRecord(
                "Wallet", "cmd", "evt",
                AggregateServiceType.STANDARD,
                List.of(cmd("CreditWallet", 1)),
                List.of(),
                List.of()
        );

        String result = action.discoverServices(List.of(record));

        assertThat(result).contains("CreditWallet");
        assertThat(result).contains("v1");
    }

    @Test
    void discoverServicesIncludesProducedEvents() {
        AggregateServiceRecord record = createRecord(
                "Wallet", "cmd", "evt",
                AggregateServiceType.STANDARD,
                List.of(),
                List.of(evt("WalletCredited", 1)),
                List.of()
        );

        String result = action.discoverServices(List.of(record));

        assertThat(result).contains("WalletCredited");
    }

    @Test
    void discoverServicesIncludesConsumedEvents() {
        AggregateServiceRecord record = createRecord(
                "Wallet", "cmd", "evt",
                AggregateServiceType.STANDARD,
                List.of(),
                List.of(),
                List.of(evt("OrderPlaced", 1))
        );

        String result = action.discoverServices(List.of(record));

        assertThat(result).contains("OrderPlaced");
    }

    @Test
    void discoverServicesHandlesMultipleAggregates() {
        AggregateServiceRecord wallet = createRecord(
                "Wallet", "wallet-cmd", "wallet-evt",
                AggregateServiceType.STANDARD,
                List.of(cmd("CreditWallet", 1)),
                List.of(evt("WalletCredited", 1)),
                List.of()
        );
        AggregateServiceRecord portfolio = createRecord(
                "Portfolio", "portfolio-cmd", "portfolio-evt",
                AggregateServiceType.AGENTIC,
                List.of(cmd("PlaceOrder", 2)),
                List.of(evt("OrderPlaced", 1)),
                List.of(evt("MarketDataUpdated", 1))
        );

        String result = action.discoverServices(List.of(wallet, portfolio));

        assertThat(result).contains("Wallet");
        assertThat(result).contains("Portfolio");
        assertThat(result).contains("CreditWallet");
        assertThat(result).contains("PlaceOrder");
        assertThat(result).contains("WalletCredited");
        assertThat(result).contains("OrderPlaced");
        assertThat(result).contains("MarketDataUpdated");
    }

    @Test
    void discoverServicesIncludesVersionNumbers() {
        AggregateServiceRecord record = createRecord(
                "Wallet", "cmd", "evt",
                AggregateServiceType.STANDARD,
                List.of(cmd("CreditWallet", 3)),
                List.of(evt("WalletCredited", 2)),
                List.of()
        );

        String result = action.discoverServices(List.of(record));

        assertThat(result).contains("v3");
        assertThat(result).contains("v2");
    }

    private AggregateServiceRecord createRecord(
            String name,
            String commandTopic,
            String eventTopic,
            AggregateServiceType type,
            List<AggregateServiceCommandType> commands,
            List<AggregateServiceDomainEventType> produced,
            List<AggregateServiceDomainEventType> consumed) {
        return new AggregateServiceRecord(name, commandTopic, eventTopic, type, commands, produced, consumed);
    }

    private static AggregateServiceCommandType cmd(String typeName, int version) {
        return new AggregateServiceCommandType(typeName, version, false, typeName);
    }

    private static AggregateServiceDomainEventType evt(String typeName, int version) {
        return new AggregateServiceDomainEventType(typeName, version, false, false, typeName);
    }
}
