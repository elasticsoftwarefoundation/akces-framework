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

package org.elasticsoftware.akces.aggregate;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link RequestingParty} sealed interface and its implementations
 * ({@link AgentRequestingParty} and {@link HumanRequestingParty}), verifying
 * record construction, equality, and Jackson annotation configuration.
 *
 * <p>Since the api module only has {@code jackson-annotations} (not {@code jackson-databind}),
 * serialization round-trips are verified in the agentic module tests where a full Jackson
 * {@code ObjectMapper} is available.
 */
class RequestingPartySerializationTest {

    // -------------------------------------------------------------------------
    // AgentRequestingParty tests
    // -------------------------------------------------------------------------

    @Test
    void agentRequestingPartyShouldPopulateAllFields() {
        var agent = new AgentRequestingParty("agent-1", "Orchestrator", "supervisor");

        assertThat(agent.agentId()).isEqualTo("agent-1");
        assertThat(agent.agentName()).isEqualTo("Orchestrator");
        assertThat(agent.role()).isEqualTo("supervisor");
    }

    @Test
    void agentRequestingPartyShouldImplementRequestingParty() {
        var agent = new AgentRequestingParty("agent-1", "Orchestrator", "supervisor");

        assertThat(agent).isInstanceOf(RequestingParty.class);
        assertThat(((RequestingParty) agent).role()).isEqualTo("supervisor");
    }

    // -------------------------------------------------------------------------
    // HumanRequestingParty tests
    // -------------------------------------------------------------------------

    @Test
    void humanRequestingPartyShouldPopulateAllFields() {
        var human = new HumanRequestingParty("user-1", "administrator");

        assertThat(human.userId()).isEqualTo("user-1");
        assertThat(human.role()).isEqualTo("administrator");
    }

    @Test
    void humanRequestingPartyShouldImplementRequestingParty() {
        var human = new HumanRequestingParty("user-1", "administrator");

        assertThat(human).isInstanceOf(RequestingParty.class);
        assertThat(((RequestingParty) human).role()).isEqualTo("administrator");
    }

    // -------------------------------------------------------------------------
    // Equality tests
    // -------------------------------------------------------------------------

    @Test
    void agentRequestingPartyEqualityShouldHold() {
        var a = new AgentRequestingParty("id-1", "Name", "role");
        var b = new AgentRequestingParty("id-1", "Name", "role");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void humanRequestingPartyEqualityShouldHold() {
        var a = new HumanRequestingParty("id-1", "role");
        var b = new HumanRequestingParty("id-1", "role");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void agentAndHumanShouldNotBeEqual() {
        var agent = new AgentRequestingParty("id-1", "Name", "role");
        var human = new HumanRequestingParty("id-1", "role");

        assertThat(agent).isNotEqualTo(human);
    }

    @Test
    void agentRequestingPartyInequalityWhenIdDiffers() {
        var a = new AgentRequestingParty("id-1", "Name", "role");
        var b = new AgentRequestingParty("id-2", "Name", "role");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void humanRequestingPartyInequalityWhenIdDiffers() {
        var a = new HumanRequestingParty("id-1", "role");
        var b = new HumanRequestingParty("id-2", "role");

        assertThat(a).isNotEqualTo(b);
    }

    // -------------------------------------------------------------------------
    // Jackson annotation tests
    // -------------------------------------------------------------------------

    @Test
    void requestingPartyShouldHaveJsonTypeInfoAnnotation() {
        JsonTypeInfo typeInfo = RequestingParty.class.getAnnotation(JsonTypeInfo.class);

        assertThat(typeInfo).isNotNull();
        assertThat(typeInfo.use()).isEqualTo(JsonTypeInfo.Id.NAME);
        assertThat(typeInfo.property()).isEqualTo("type");
    }

    @Test
    void requestingPartyShouldHaveJsonSubTypesAnnotation() {
        JsonSubTypes subTypes = RequestingParty.class.getAnnotation(JsonSubTypes.class);

        assertThat(subTypes).isNotNull();
        assertThat(subTypes.value()).hasSize(2);

        JsonSubTypes.Type[] types = subTypes.value();
        assertThat(types[0].value()).isEqualTo(AgentRequestingParty.class);
        assertThat(types[0].name()).isEqualTo("agent");
        assertThat(types[1].value()).isEqualTo(HumanRequestingParty.class);
        assertThat(types[1].name()).isEqualTo("human");
    }

    // -------------------------------------------------------------------------
    // Sealed interface tests
    // -------------------------------------------------------------------------

    @Test
    void requestingPartyShouldBeSealed() {
        assertThat(RequestingParty.class.isSealed()).isTrue();
        assertThat(RequestingParty.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(AgentRequestingParty.class, HumanRequestingParty.class);
    }

    @Test
    void patternMatchingShouldWorkWithSwitch() {
        RequestingParty agent = new AgentRequestingParty("a-1", "TestAgent", "supervisor");
        RequestingParty human = new HumanRequestingParty("u-1", "analyst");

        assertThat(describe(agent)).isEqualTo("Agent: TestAgent (supervisor)");
        assertThat(describe(human)).isEqualTo("Human: u-1 (analyst)");
    }

    private static String describe(RequestingParty party) {
        return switch (party) {
            case AgentRequestingParty a -> "Agent: " + a.agentName() + " (" + a.role() + ")";
            case HumanRequestingParty h -> "Human: " + h.userId() + " (" + h.role() + ")";
        };
    }
}
