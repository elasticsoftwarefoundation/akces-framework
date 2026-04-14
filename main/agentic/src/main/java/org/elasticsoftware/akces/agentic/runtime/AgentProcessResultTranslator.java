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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class that translates the results of an Embabel {@code AgentProcess} back
 * into Akces {@link DomainEvent} instances.
 *
 * <p>After each agent tick (or after the process reaches an end state), call
 * {@link #collectEvents(Blackboard, Collection)} to drain new {@link DomainEvent} objects
 * from the blackboard. Already-collected events are tracked using an identity-based set
 * stored on the blackboard under the key {@value #PROCESSED_EVENTS_KEY}, so subsequent
 * calls to this method will not return the same events again. Unlike the previous
 * approach of hiding events via {@link Blackboard#hide(Object)}, events remain visible
 * on the blackboard so that the Embabel planner can still evaluate goal achievement
 * based on the full event stream.
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
     * Blackboard key under which the set of already-processed events is stored.
     */
    static final String PROCESSED_EVENTS_KEY = "_akces_processedDomainEvents";

    private AgentProcessResultTranslator() {
        // utility class
    }

    /**
     * Collects all <em>new</em> {@link DomainEvent} objects currently on the blackboard
     * that have not been collected before, and returns the subset that can safely be
     * passed to the runtime's {@code processDomainEvent()} method.
     *
     * <p>Events remain on the blackboard (they are <em>not</em> hidden) so the Embabel
     * planner can still evaluate goal achievement from the full event stream. An
     * identity-based set stored on the blackboard under key
     * {@value #PROCESSED_EVENTS_KEY} tracks which events have already been collected to
     * prevent double-processing.
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
    @SuppressWarnings("unchecked")
    public static List<DomainEvent> collectEvents(Blackboard blackboard,
                                                  Collection<DomainEventType<?>> registeredEventTypes) {
        Set<Class<?>> registeredClasses = registeredEventTypes.stream()
                .map(DomainEventType::typeClass)
                .collect(Collectors.toSet());

        // Retrieve or create the identity-based set of already-processed events.
        // Using IdentityHashMap-backed set so that reference equality is used — each
        // DomainEvent instance placed on the blackboard is tracked by identity, not
        // by value equality. This is important because two structurally equal events
        // (e.g. same record fields) placed at different times must be treated as
        // separate events.
        Set<DomainEvent> processedEvents = (Set<DomainEvent>) blackboard.get(PROCESSED_EVENTS_KEY);
        if (processedEvents == null) {
            processedEvents = Collections.newSetFromMap(new IdentityHashMap<>());
            blackboard.set(PROCESSED_EVENTS_KEY, processedEvents);
        }

        List<DomainEvent> allEvents = blackboard.getObjects().stream()
                .filter(o -> o instanceof DomainEvent)
                .map(o -> (DomainEvent) o)
                .toList();

        List<DomainEvent> result = new ArrayList<>(allEvents.size());
        for (DomainEvent event : allEvents) {
            if (processedEvents.contains(event)) {
                continue; // already collected in a previous call
            }
            processedEvents.add(event);
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
        return List.copyOf(result);
    }
}
