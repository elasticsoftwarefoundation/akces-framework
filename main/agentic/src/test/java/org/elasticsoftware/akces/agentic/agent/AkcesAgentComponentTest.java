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

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Condition;
import com.embabel.agent.api.annotation.EmbabelComponent;
import org.elasticsoftware.akces.agentic.events.MemoryRevokedEvent;
import org.elasticsoftware.akces.agentic.events.MemoryStoredEvent;
import org.elasticsoftware.akces.aggregate.AgenticAggregateMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AkcesAgentComponent}, verifying that all Actions, Conditions,
 * and Goals produce correct outputs and are properly annotated for Embabel discovery.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>{@link AkcesAgentComponent#storeMemory storeMemory} — produces valid
 *       {@link MemoryStoredEvent}</li>
 *   <li>{@link AkcesAgentComponent#forgetMemory forgetMemory} — produces valid
 *       {@link MemoryRevokedEvent}</li>
 *   <li>{@link AkcesAgentComponent#recallMemories recallMemories} — filters memories by
 *       subject/keyword and handles edge cases</li>
 *   <li>{@link AkcesAgentComponent#hasMemories hasMemories} — evaluates memory presence
 *       correctly</li>
 *   <li>{@link AkcesAgentComponent#learnFromProcess learnFromProcess} — goal action
 *       produces a valid result</li>
 *   <li>Embabel annotation presence and attributes</li>
 * </ul>
 */
class AkcesAgentComponentTest {

    private AkcesAgentComponent component;

    @BeforeEach
    void setUp() {
        component = new AkcesAgentComponent();
    }

    // =========================================================================
    // Annotation verification
    // =========================================================================

    @Nested
    class AnnotationTests {

        @Test
        void classShouldBeAnnotatedWithEmbabelComponent() {
            assertThat(AkcesAgentComponent.class.isAnnotationPresent(EmbabelComponent.class))
                    .as("AkcesAgentComponent must be annotated with @EmbabelComponent")
                    .isTrue();
        }

        @Test
        void storeMemoryShouldBeAnnotatedWithAction() throws NoSuchMethodException {
            Method method = AkcesAgentComponent.class.getMethod(
                    "storeMemory", String.class, String.class, String.class,
                    String.class, String.class);
            Action action = method.getAnnotation(Action.class);

            assertThat(action).isNotNull();
            assertThat(action.description())
                    .isEqualTo("Store a learned fact as a memory entry for the agentic aggregate");
            assertThat(action.readOnly()).isFalse();
        }

        @Test
        void forgetMemoryShouldBeAnnotatedWithAction() throws NoSuchMethodException {
            Method method = AkcesAgentComponent.class.getMethod(
                    "forgetMemory", String.class, String.class, String.class);
            Action action = method.getAnnotation(Action.class);

            assertThat(action).isNotNull();
            assertThat(action.description())
                    .isEqualTo("Revoke a memory entry that is no longer relevant or accurate");
            assertThat(action.readOnly()).isFalse();
        }

        @Test
        void recallMemoriesShouldBeAnnotatedWithReadOnlyAction() throws NoSuchMethodException {
            Method method = AkcesAgentComponent.class.getMethod(
                    "recallMemories", List.class, String.class);
            Action action = method.getAnnotation(Action.class);

            assertThat(action).isNotNull();
            assertThat(action.description()).contains("Search stored memories");
            assertThat(action.readOnly())
                    .as("recallMemories must be readOnly")
                    .isTrue();
        }

        @Test
        void hasMemoriesShouldBeAnnotatedWithCondition() throws NoSuchMethodException {
            Method method = AkcesAgentComponent.class.getMethod("hasMemories", List.class);
            Condition condition = method.getAnnotation(Condition.class);

            assertThat(condition).isNotNull();
            assertThat(condition.name()).isEqualTo("hasMemories");
        }

        @Test
        void learnFromProcessShouldBeAnnotatedWithAchievesGoalAndAction()
                throws NoSuchMethodException {
            Method method = AkcesAgentComponent.class.getMethod("learnFromProcess", List.class);

            AchievesGoal achievesGoal = method.getAnnotation(AchievesGoal.class);
            assertThat(achievesGoal).isNotNull();
            assertThat(achievesGoal.description())
                    .contains("Analyze current session information")
                    .contains("Maximum 3 new memories")
                    .contains("No duplicate memories")
                    .contains("Memory capacity enforcement");

            Action action = method.getAnnotation(Action.class);
            assertThat(action).isNotNull();
            assertThat(action.description()).contains("learning process");
        }
    }

    // =========================================================================
    // StoreMemoryAction tests
    // =========================================================================

    @Nested
    class StoreMemoryActionTests {

        @Test
        void shouldProduceMemoryStoredEventWithCorrectFields() {
            MemoryStoredEvent event = component.storeMemory(
                    "agg-1", "testing", "Use JUnit 5",
                    "build.gradle:10", "consistency");

            assertThat(event.agenticAggregateId()).isEqualTo("agg-1");
            assertThat(event.subject()).isEqualTo("testing");
            assertThat(event.fact()).isEqualTo("Use JUnit 5");
            assertThat(event.citations()).isEqualTo("build.gradle:10");
            assertThat(event.reason()).isEqualTo("consistency");
            assertThat(event.memoryId()).isNotNull().isNotBlank();
            assertThat(event.storedAt()).isNotNull();
        }

        @Test
        void shouldGenerateUniqueMemoryIds() {
            MemoryStoredEvent event1 = component.storeMemory(
                    "agg-1", "s", "f", "c", "r");
            MemoryStoredEvent event2 = component.storeMemory(
                    "agg-1", "s", "f", "c", "r");

            assertThat(event1.memoryId()).isNotEqualTo(event2.memoryId());
        }

        @Test
        void shouldSetStoredAtToCurrentTime() {
            Instant before = Instant.now();
            MemoryStoredEvent event = component.storeMemory(
                    "agg-1", "s", "f", "c", "r");
            Instant after = Instant.now();

            assertThat(event.storedAt())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
        }

        @Test
        void shouldSetCorrectAggregateId() {
            MemoryStoredEvent event = component.storeMemory(
                    "agg-1", "s", "f", "c", "r");

            assertThat(event.getAggregateId()).isEqualTo("agg-1");
        }
    }

    // =========================================================================
    // ForgetMemoryAction tests
    // =========================================================================

    @Nested
    class ForgetMemoryActionTests {

        @Test
        void shouldProduceMemoryRevokedEventWithCorrectFields() {
            MemoryRevokedEvent event = component.forgetMemory(
                    "agg-1", "mem-42", "no longer relevant");

            assertThat(event.agenticAggregateId()).isEqualTo("agg-1");
            assertThat(event.memoryId()).isEqualTo("mem-42");
            assertThat(event.reason()).isEqualTo("no longer relevant");
            assertThat(event.revokedAt()).isNotNull();
        }

        @Test
        void shouldSetRevokedAtToCurrentTime() {
            Instant before = Instant.now();
            MemoryRevokedEvent event = component.forgetMemory(
                    "agg-1", "mem-1", "eviction");
            Instant after = Instant.now();

            assertThat(event.revokedAt())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
        }

        @Test
        void shouldSetCorrectAggregateId() {
            MemoryRevokedEvent event = component.forgetMemory(
                    "agg-1", "mem-1", "reason");

            assertThat(event.getAggregateId()).isEqualTo("agg-1");
        }
    }

    // =========================================================================
    // RecallMemoriesAction tests
    // =========================================================================

    @Nested
    class RecallMemoriesActionTests {

        private final List<AgenticAggregateMemory> sampleMemories = List.of(
                new AgenticAggregateMemory("m1", "error handling",
                        "Use try-with-resources for auto-closeable resources",
                        "Service.java:42", "Prevents resource leaks",
                        Instant.parse("2026-01-01T00:00:00Z")),
                new AgenticAggregateMemory("m2", "testing",
                        "Use JUnit 5 for all new tests",
                        "build.gradle:10", "Consistency across the project",
                        Instant.parse("2026-01-02T00:00:00Z")),
                new AgenticAggregateMemory("m3", "logging",
                        "Use SLF4J with structured logging",
                        "LogConfig.java:5", "Better observability in production",
                        Instant.parse("2026-01-03T00:00:00Z"))
        );

        @Test
        void shouldReturnAllMemoriesWhenQueryIsNull() {
            List<AgenticAggregateMemory> result = component.recallMemories(
                    sampleMemories, null);

            assertThat(result).hasSize(3);
        }

        @Test
        void shouldReturnAllMemoriesWhenQueryIsBlank() {
            List<AgenticAggregateMemory> result = component.recallMemories(
                    sampleMemories, "  ");

            assertThat(result).hasSize(3);
        }

        @Test
        void shouldFilterBySubjectMatch() {
            List<AgenticAggregateMemory> result = component.recallMemories(
                    sampleMemories, "testing");

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().memoryId()).isEqualTo("m2");
        }

        @Test
        void shouldFilterByFactContentMatch() {
            List<AgenticAggregateMemory> result = component.recallMemories(
                    sampleMemories, "SLF4J");

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().memoryId()).isEqualTo("m3");
        }

        @Test
        void shouldFilterByReasonMatch() {
            List<AgenticAggregateMemory> result = component.recallMemories(
                    sampleMemories, "observability");

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().memoryId()).isEqualTo("m3");
        }

        @Test
        void shouldBeCaseInsensitive() {
            List<AgenticAggregateMemory> result = component.recallMemories(
                    sampleMemories, "JUNIT");

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().memoryId()).isEqualTo("m2");
        }

        @Test
        void shouldReturnMultipleMatchesWhenQueryMatchesMultipleMemories() {
            // "Consistency" appears in m2's reason, and "production" in m3's reason
            // But a broader term like "for" appears in m1 fact, m2 fact, m3 reason
            List<AgenticAggregateMemory> result = component.recallMemories(
                    sampleMemories, "for");

            assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        void shouldReturnEmptyListWhenNoMatchesFound() {
            List<AgenticAggregateMemory> result = component.recallMemories(
                    sampleMemories, "nonexistent");

            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenMemoriesAreNull() {
            List<AgenticAggregateMemory> result = component.recallMemories(null, "test");

            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenMemoriesAreEmpty() {
            List<AgenticAggregateMemory> result = component.recallMemories(
                    List.of(), "test");

            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnEmptyListWhenBothMemoriesAndQueryAreNull() {
            List<AgenticAggregateMemory> result = component.recallMemories(null, null);

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // HasMemoriesCondition tests
    // =========================================================================

    @Nested
    class HasMemoriesConditionTests {

        @Test
        void shouldReturnTrueWhenMemoriesExist() {
            List<AgenticAggregateMemory> memories = List.of(
                    new AgenticAggregateMemory("m1", "s", "f", "c", "r", Instant.now()));

            assertThat(component.hasMemories(memories)).isTrue();
        }

        @Test
        void shouldReturnFalseWhenMemoriesAreEmpty() {
            assertThat(component.hasMemories(List.of())).isFalse();
        }

        @Test
        void shouldReturnFalseWhenMemoriesAreNull() {
            assertThat(component.hasMemories(null)).isFalse();
        }

        @Test
        void shouldReturnTrueWhenMultipleMemoriesExist() {
            List<AgenticAggregateMemory> memories = List.of(
                    new AgenticAggregateMemory("m1", "s1", "f1", "c1", "r1", Instant.now()),
                    new AgenticAggregateMemory("m2", "s2", "f2", "c2", "r2", Instant.now()));

            assertThat(component.hasMemories(memories)).isTrue();
        }
    }

    // =========================================================================
    // LearnFromProcessGoal tests
    // =========================================================================

    @Nested
    class LearnFromProcessGoalTests {

        @Test
        void shouldProduceMemoryLearningResult() {
            List<AgenticAggregateMemory> memories = List.of(
                    new AgenticAggregateMemory("m1", "s", "f", "c", "r", Instant.now()));

            MemoryLearningResult result = component.learnFromProcess(memories);

            assertThat(result).isNotNull();
            assertThat(result.summary()).contains("1");
        }

        @Test
        void shouldHandleNullMemories() {
            MemoryLearningResult result = component.learnFromProcess(null);

            assertThat(result).isNotNull();
            assertThat(result.summary()).contains("0");
        }

        @Test
        void shouldHandleEmptyMemories() {
            MemoryLearningResult result = component.learnFromProcess(List.of());

            assertThat(result).isNotNull();
            assertThat(result.summary()).contains("0");
        }

        @Test
        void maxNewMemoriesPerExecutionShouldBeThree() {
            assertThat(AkcesAgentComponent.MAX_NEW_MEMORIES_PER_EXECUTION).isEqualTo(3);
        }
    }
}
