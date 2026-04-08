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
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Utility class that translates the results of an Embabel {@code AgentProcess} back
 * into Akces {@link DomainEvent} instances.
 *
 * <p>After each agent tick (or after the process reaches an end state), call
 * {@link #collectEvents(Blackboard)} to drain {@link DomainEvent} objects from the
 * blackboard. Events are marked as hidden on the blackboard after collection, so
 * subsequent calls to this method will not return the same events again — this
 * supports both tick-to-completion and future incremental-tick processing patterns.
 *
 * <p>Undeclared {@link ErrorEvent} instances are accepted at runtime. A warning is
 * logged so operators can detect unexpected error types without causing the transaction
 * to abort.
 */
public final class AgentProcessResultTranslator {

    private static final Logger logger = LoggerFactory.getLogger(AgentProcessResultTranslator.class);

    private AgentProcessResultTranslator() {
        // utility class
    }

    /**
     * Collects all {@link DomainEvent} objects currently visible on the blackboard
     * and removes them from visible scope (via {@link Blackboard#hide(Object)}) so that
     * they are not collected again on the next call.
     *
     * <p>Any {@link ErrorEvent} instances found are logged at {@code WARN} level so
     * that operators can detect unexpected error types emitted by the agent.
     *
     * @param blackboard the agent process blackboard to drain events from
     * @return an unmodifiable list of collected domain events in the order they
     *         were encountered on the blackboard; never {@code null}, may be empty
     */
    public static List<DomainEvent> collectEvents(Blackboard blackboard) {
        List<DomainEvent> events = blackboard.getObjects().stream()
                .filter(o -> o instanceof DomainEvent)
                .map(o -> (DomainEvent) o)
                .toList();

        for (DomainEvent event : events) {
            if (event instanceof ErrorEvent) {
                logger.warn("Agent produced error event of type {}: {}",
                        event.getClass().getName(), event);
            }
            blackboard.hide(event);
        }

        return events;
    }
}
