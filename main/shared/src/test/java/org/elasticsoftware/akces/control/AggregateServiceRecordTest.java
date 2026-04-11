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

package org.elasticsoftware.akces.control;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AggregateServiceRecordTest {

    private static AggregateServiceRecord agenticService(String name) {
        return new AggregateServiceRecord(
                name,
                name + "-Commands",
                name + "-DomainEvents",
                AggregateServiceType.AGENTIC,
                List.of(new AggregateServiceCommandType("AssignTask", 1, false, "commands.AssignTask", "Assign a task")),
                List.of(),
                List.of(),
                null);
    }

    private static AggregateServiceRecord standardService(String name) {
        return new AggregateServiceRecord(
                name,
                name + "-Commands",
                name + "-DomainEvents",
                AggregateServiceType.STANDARD,
                List.of(new AggregateServiceCommandType("AssignTask", 1, false, "commands.AssignTask", "Assign a task")),
                List.of(),
                List.of(),
                null);
    }

    @Test
    void resolveAgenticTarget_matchesAgenticServiceByAggregateName() {
        var agentA = agenticService("AgentA");
        var agentB = agenticService("AgentB");
        var services = List.of(agentA, agentB);

        assertEquals(agentA, AggregateServiceRecord.resolveAgenticTarget(services, "AgentA"));
        assertEquals(agentB, AggregateServiceRecord.resolveAgenticTarget(services, "AgentB"));
    }

    @Test
    void resolveAgenticTarget_fallsBackToSingleStandardService() {
        var agentA = agenticService("AgentA");
        var standard = standardService("Account");
        var services = List.of(agentA, standard);

        // aggregateId doesn't match any AGENTIC service, falls back to STANDARD
        assertEquals(standard, AggregateServiceRecord.resolveAgenticTarget(services, "unknown-id"));
    }

    @Test
    void resolveAgenticTarget_returnsNullWhenNoMatch() {
        var agentA = agenticService("AgentA");
        var agentB = agenticService("AgentB");
        var services = List.of(agentA, agentB);

        // aggregateId doesn't match any service and there's no STANDARD fallback
        assertNull(AggregateServiceRecord.resolveAgenticTarget(services, "unknown-id"));
    }

    @Test
    void resolveAgenticTarget_returnsNullWithMultipleStandardServices() {
        var standard1 = standardService("Account");
        var standard2 = standardService("Wallet");
        var services = List.of(standard1, standard2);

        // No AGENTIC match and multiple STANDARD services → ambiguous
        assertNull(AggregateServiceRecord.resolveAgenticTarget(services, "unknown-id"));
    }

    @Test
    void resolveAgenticTarget_prefersAgenticOverStandard() {
        var agentA = agenticService("AgentA");
        var standard = standardService("Account");
        var services = List.of(agentA, standard);

        // aggregateId matches the AGENTIC service — should pick it, not STANDARD
        assertEquals(agentA, AggregateServiceRecord.resolveAgenticTarget(services, "AgentA"));
    }

    @Test
    void effectiveType_defaultsToStandard() {
        var record = new AggregateServiceRecord(
                "Test", "Test-Commands", "Test-DomainEvents",
                null, List.of(), List.of(), List.of(), null);
        assertEquals(AggregateServiceType.STANDARD, record.effectiveType());
    }

    @Test
    void effectiveType_returnsExplicitType() {
        var agentic = agenticService("Test");
        assertEquals(AggregateServiceType.AGENTIC, agentic.effectiveType());

        var standard = standardService("Test");
        assertEquals(AggregateServiceType.STANDARD, standard.effectiveType());
    }
}
