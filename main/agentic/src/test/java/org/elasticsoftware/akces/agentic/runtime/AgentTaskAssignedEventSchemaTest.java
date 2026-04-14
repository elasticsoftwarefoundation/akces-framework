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

package org.elasticsoftware.akces.agentic.runtime;

import org.elasticsoftware.akces.agentic.commands.AssignTaskCommand;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.aggregate.AgentRequestingParty;
import org.elasticsoftware.akces.aggregate.HumanRequestingParty;
import org.elasticsoftware.akces.schemas.JsonSchema;
import org.elasticsoftware.akces.schemas.SchemaRegistry;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests that the {@link AgentTaskAssignedEvent} and {@link AssignTaskCommand} can be
 * serialized and validated against their generated JSON Schemas. Reproduces the
 * serialization failure reported in the integration test where {@code taskMetadata}
 * validation fails with "no subschema matched out of the total 2 subschemas".
 *
 * <p>Root cause: {@code Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT} generated
 * {@code "additionalProperties": false} on Map types, rejecting map entries as
 * additional properties. Fix: add {@code Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES}
 * to the schema generator.
 */
class AgentTaskAssignedEventSchemaTest {

    @Test
    void agentTaskAssignedEventWithTaskMetadataShouldPassSchemaValidation() {
        SchemaGenerator generator = SchemaRegistry.createJsonSchemaGenerator();
        var schemaNode = generator.generateSchema(AgentTaskAssignedEvent.class);
        JsonSchema schema = new JsonSchema(schemaNode.toString());

        var event = new AgentTaskAssignedEvent(
                "agg-1",
                "proc-42",
                "Analyze BTC/USD market",
                new HumanRequestingParty("user-1", "analyst"),
                Map.of("symbol", "BTC/USD", "priority", "high"),
                Instant.now()
        );

        assertThatNoException().isThrownBy(() -> schema.validate(schema.toJson(event)));
    }

    @Test
    void agentTaskAssignedEventWithNullTaskMetadataShouldPassSchemaValidation() {
        SchemaGenerator generator = SchemaRegistry.createJsonSchemaGenerator();
        var schemaNode = generator.generateSchema(AgentTaskAssignedEvent.class);
        JsonSchema schema = new JsonSchema(schemaNode.toString());

        var event = new AgentTaskAssignedEvent(
                "agg-1",
                "proc-42",
                "Analyze BTC/USD market",
                new HumanRequestingParty("user-1", "analyst"),
                null,
                Instant.now()
        );

        assertThatNoException().isThrownBy(() -> schema.validate(schema.toJson(event)));
    }

    @Test
    void agentTaskAssignedEventWithEmptyTaskMetadataShouldPassSchemaValidation() {
        SchemaGenerator generator = SchemaRegistry.createJsonSchemaGenerator();
        var schemaNode = generator.generateSchema(AgentTaskAssignedEvent.class);
        JsonSchema schema = new JsonSchema(schemaNode.toString());

        var event = new AgentTaskAssignedEvent(
                "agg-1",
                "proc-42",
                "Analyze BTC/USD market",
                new HumanRequestingParty("user-1", "analyst"),
                Map.of(),
                Instant.now()
        );

        assertThatNoException().isThrownBy(() -> schema.validate(schema.toJson(event)));
    }

    @Test
    void agentTaskAssignedEventWithAgentRequestingPartyShouldPassSchemaValidation() {
        SchemaGenerator generator = SchemaRegistry.createJsonSchemaGenerator();
        var schemaNode = generator.generateSchema(AgentTaskAssignedEvent.class);
        JsonSchema schema = new JsonSchema(schemaNode.toString());

        var event = new AgentTaskAssignedEvent(
                "agg-1",
                "proc-42",
                "Analyze BTC/USD market",
                new AgentRequestingParty("agent-99", "Orchestrator", "supervisor"),
                Map.of("correlationId", "req-123"),
                Instant.now()
        );

        assertThatNoException().isThrownBy(() -> schema.validate(schema.toJson(event)));
    }

    @Test
    void assignTaskCommandWithTaskMetadataShouldPassSchemaValidation() {
        SchemaGenerator generator = SchemaRegistry.createJsonSchemaGenerator();
        var schemaNode = generator.generateSchema(AssignTaskCommand.class);
        JsonSchema schema = new JsonSchema(schemaNode.toString());

        var command = new AssignTaskCommand(
                "agg-1",
                "Analyze BTC/USD market",
                new HumanRequestingParty("user-1", "analyst"),
                Map.of("symbol", "BTC/USD", "priority", "high")
        );

        assertThatNoException().isThrownBy(() -> schema.validate(schema.toJson(command)));
    }

    @Test
    void assignTaskCommandWithNullTaskMetadataShouldPassSchemaValidation() {
        SchemaGenerator generator = SchemaRegistry.createJsonSchemaGenerator();
        var schemaNode = generator.generateSchema(AssignTaskCommand.class);
        JsonSchema schema = new JsonSchema(schemaNode.toString());

        var command = new AssignTaskCommand(
                "agg-1",
                "Analyze BTC/USD market",
                new HumanRequestingParty("user-1", "analyst"),
                null
        );

        assertThatNoException().isThrownBy(() -> schema.validate(schema.toJson(command)));
    }
}
