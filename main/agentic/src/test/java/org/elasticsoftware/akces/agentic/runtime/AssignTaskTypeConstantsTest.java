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

import org.elasticsoftware.akces.agentic.AgenticAggregateRuntime;
import org.elasticsoftware.akces.agentic.commands.AssignTaskCommand;
import org.elasticsoftware.akces.agentic.events.AgentTaskAssignedEvent;
import org.elasticsoftware.akces.agentic.events.AgentTaskFinishedEvent;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the built-in type registration constants on {@link AgenticAggregateRuntime},
 * verifying the {@code ASSIGN_TASK_COMMAND_TYPE} and {@code AGENT_TASK_ASSIGNED_TYPE}
 * constants are correctly configured.
 */
class AssignTaskTypeConstantsTest {

    @Test
    void assignTaskCommandTypeShouldBeConfiguredCorrectly() {
        CommandType<AssignTaskCommand> type = AgenticAggregateRuntime.ASSIGN_TASK_COMMAND_TYPE;

        assertThat(type.typeName()).isEqualTo("AssignTask");
        assertThat(type.version()).isEqualTo(1);
        assertThat(type.typeClass()).isEqualTo(AssignTaskCommand.class);
    }

    @Test
    void agentTaskAssignedTypeShouldBeConfiguredCorrectly() {
        DomainEventType<AgentTaskAssignedEvent> type = AgenticAggregateRuntime.AGENT_TASK_ASSIGNED_TYPE;

        assertThat(type.typeName()).isEqualTo("AgentTaskAssigned");
        assertThat(type.version()).isEqualTo(1);
        assertThat(type.typeClass()).isEqualTo(AgentTaskAssignedEvent.class);
    }

    @Test
    void agentTaskFinishedTypeShouldBeConfiguredCorrectly() {
        DomainEventType<AgentTaskFinishedEvent> type = AgenticAggregateRuntime.AGENT_TASK_FINISHED_TYPE;

        assertThat(type.typeName()).isEqualTo("AgentTaskFinished");
        assertThat(type.version()).isEqualTo(1);
        assertThat(type.typeClass()).isEqualTo(AgentTaskFinishedEvent.class);
    }
}
