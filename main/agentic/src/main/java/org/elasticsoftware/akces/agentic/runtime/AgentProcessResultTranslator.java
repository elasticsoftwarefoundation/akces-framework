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

import com.embabel.agent.core.Blackboard;
import org.elasticsoftware.akces.aggregate.DomainEventType;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class that translates the results of an Embabel {@code AgentProcess} back
 * into Akces {@link DomainEvent} instances.
 *
 * <p>After each agent tick (or after the process reaches an end state), call
 * {@link #collectEvents(Blackboard, Collection)} to drain new {@link DomainEvent} objects
 * from the blackboard. A cursor (the index of the last processed object) is stored on
 * the blackboard under the key {@value #PROCESSED_INDEX_KEY}, so subsequent calls skip
 * objects that have already been collected. Events remain visible on the blackboard so
 * that the Embabel planner can still evaluate goal achievement based on the full event
 * stream.
 *
 * <p>Unknown {@link ErrorEvent} types (not declared in {@code agentProducedErrors} and
 * therefore not registered as {@link DomainEventType}s in the runtime) are logged at
 * {@code WARN} level and excluded from the returned list. This prevents
 * {@code processDomainEvent()} from encountering a {@code null} type look-up and
 * NPE-ing, while still allowing the transaction to commit with a meaningful result.
 * Standard (non-error) {@link DomainEvent} types are always included and must be
 * registered; passing an unregistered non-error event to the runtime will result in
 * a {@code NullPointerException} in {@code processDomainEvent()}.
 */
public final class AgentProcessResultTranslator {

    private static final Logger logger = LoggerFactory.getLogger(AgentProcessResultTranslator.class);

    /**
     * Blackboard key under which the index of the last processed object is stored.
     * The value is an {@code int[1]} array holding the index (into
     * {@link Blackboard#getObjects()}) up to which events have already been collected.
     */
    static final String PROCESSED_INDEX_KEY = "_akces_processedIndex";

    private AgentProcessResultTranslator() {
        // utility class
    }

    /**
     * Collects all <em>new</em> {@link DomainEvent} objects currently on the blackboard
     * that have not been collected before, and returns the subset that can safely be
     * passed to the runtime's {@code processDomainEvent()} method.
     *
     * <p>Events remain on the blackboard (they are <em>not</em> hidden) so the Embabel
     * planner can still evaluate goal achievement from the full event stream. A cursor
     * stored on the blackboard under key {@value #PROCESSED_INDEX_KEY} tracks how far
     * we have already read. Because {@link Blackboard#getObjects()} returns objects in
     * insertion order, storing just the index is sufficient and more economical than
     * maintaining a full set of processed events.
     *
     * <p>For every collected event:
     * <ul>
     *   <li>Non-error {@link DomainEvent}s are always included and passed through as-is.</li>
     *   <li>{@link ErrorEvent}s whose class is present in {@code registeredEventTypes} are
     *       included.</li>
     *   <li>{@link ErrorEvent}s whose class is <em>not</em> present in
     *       {@code registeredEventTypes} are logged at {@code WARN} level and excluded.
     *       Excluding them prevents a {@code NullPointerException} inside
     *       {@code KafkaAggregateRuntime.processDomainEvent()} that would otherwise occur
     *       when the runtime looks up the unregistered type. The transaction still commits
     *       normally; only the unknown error event is silently dropped.</li>
     * </ul>
     *
     * @param blackboard          the agent process blackboard to drain events from
     * @param registeredEventTypes all {@link DomainEventType}s registered with the runtime;
     *                             used to verify that agent-produced {@link ErrorEvent}s are
     *                             known before passing them downstream
     * @return an unmodifiable list of domain events that are safe to pass to the runtime;
     *         never {@code null}, may be empty
     */
    public static List<DomainEvent> collectEvents(Blackboard blackboard,
                                                  Collection<DomainEventType<?>> registeredEventTypes) {
        Set<Class<?>> registeredClasses = registeredEventTypes.stream()
                .map(DomainEventType::typeClass)
                .collect(Collectors.toSet());

        // Retrieve or initialize the cursor.  We use a single-element int array so
        // the value is mutable across calls without needing to call set() again.
        int[] cursor = (int[]) blackboard.get(PROCESSED_INDEX_KEY);
        if (cursor == null) {
            cursor = new int[]{0};
            blackboard.set(PROCESSED_INDEX_KEY, cursor);
        }

        List<Object> allObjects = blackboard.getObjects();
        int size = allObjects.size();
        int startIndex = cursor[0];

        if (startIndex >= size) {
            return List.of(); // nothing new
        }

        List<DomainEvent> result = new ArrayList<>(size - startIndex);
        for (int i = startIndex; i < size; i++) {
            Object obj = allObjects.get(i);
            if (!(obj instanceof DomainEvent event)) {
                continue;
            }
            if (event instanceof ErrorEvent && !registeredClasses.contains(event.getClass())) {
                logger.warn(
                        "Agent produced undeclared error event of type '{}' which is not registered " +
                                "as a DomainEventType. The event will be excluded from processing to " +
                                "prevent a runtime failure. Declare it in agentProducedErrors to enable " +
                                "serialization and service discovery. Event: {}",
                        event.getClass().getName(), event);
            } else {
                result.add(event);
            }
        }

        // Advance the cursor past all objects we just scanned.
        cursor[0] = size;

        return List.copyOf(result);
    }
}
