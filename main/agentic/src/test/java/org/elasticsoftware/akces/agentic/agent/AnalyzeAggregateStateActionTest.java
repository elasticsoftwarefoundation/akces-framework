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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.annotations.AggregateStateInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AnalyzeAggregateStateAction}.
 */
class AnalyzeAggregateStateActionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalyzeAggregateStateAction action = new AnalyzeAggregateStateAction(objectMapper);

    @AggregateStateInfo(type = "TestState", version = 1)
    record TestState(String id, String name, int count) implements AggregateState {
        @Override
        public String getAggregateId() {
            return id;
        }
    }

    @Test
    void analyzeStateSerializesToJson() {
        TestState state = new TestState("agg-1", "TestAggregate", 42);

        String result = action.analyzeState(state);

        assertThat(result).contains("\"id\"");
        assertThat(result).contains("\"agg-1\"");
        assertThat(result).contains("\"name\"");
        assertThat(result).contains("\"TestAggregate\"");
        assertThat(result).contains("\"count\"");
        assertThat(result).contains("42");
    }

    @Test
    void analyzeStateProducesPrettyPrintedJson() {
        TestState state = new TestState("agg-1", "Test", 1);

        String result = action.analyzeState(state);

        // Pretty-printed JSON contains newlines and indentation
        assertThat(result).contains("\n");
    }

    @Test
    void analyzeStateFallsBackToToStringOnSerializationError() {
        // Use a mock ObjectMapper that throws on serialization
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public com.fasterxml.jackson.databind.ObjectWriter writerWithDefaultPrettyPrinter() {
                return new com.fasterxml.jackson.databind.ObjectWriter(this,
                        this.getSerializationConfig()) {
                    @Override
                    public String writeValueAsString(Object value) throws JsonProcessingException {
                        throw new JsonProcessingException("Simulated failure") {};
                    }
                };
            }
        };
        AnalyzeAggregateStateAction failAction = new AnalyzeAggregateStateAction(failingMapper);
        TestState state = new TestState("agg-1", "Test", 1);

        String result = failAction.analyzeState(state);

        // Falls back to toString()
        assertThat(result).isEqualTo(state.toString());
    }
}
