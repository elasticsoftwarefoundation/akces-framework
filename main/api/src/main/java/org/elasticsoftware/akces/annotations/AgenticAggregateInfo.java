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

package org.elasticsoftware.akces.annotations;

import org.elasticsoftware.akces.aggregate.AggregateState;
import org.elasticsoftware.akces.commands.Command;
import org.elasticsoftware.akces.events.DomainEvent;
import org.elasticsoftware.akces.events.ErrorEvent;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an agentic aggregate, a special type of aggregate that integrates with
 * the Embabel AI agent framework for AI-assisted command and event processing.
 *
 * <p>In addition to the standard aggregate capabilities, an agentic aggregate may declare:
 * <ul>
 *   <li>{@link #agentHandledCommands} — commands processed entirely by the AI agent (no
 *       {@code @CommandHandler} method required)</li>
 *   <li>{@link #agentHandledEvents} — external domain events processed by the AI agent (no
 *       {@code @EventHandler} method required)</li>
 *   <li>{@link #agentProducedErrors} — error event types the AI agent may emit during
 *       processing (registered for schema validation and service discovery)</li>
 * </ul>
 *
 * <p>Every {@code AgenticAggregate} must implement
 * {@link org.elasticsoftware.akces.aggregate.AgenticAggregate#getCreateDomainEvent()
 * getCreateDomainEvent()} to provide the domain event that creates the initial aggregate
 * state. The framework calls this method automatically when the singleton aggregate has no
 * state yet. The returned event must have a corresponding
 * {@code @EventSourcingHandler(create = true)} method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Component
public @interface AgenticAggregateInfo {
    @AliasFor(annotation = Component.class)
    String value();

    Class<? extends AggregateState> stateClass();

    String description() default "";

    /**
     * The sliding-window capacity of the memory system. When the number of stored memories
     * exceeds this limit the oldest entries are evicted to make room for new ones.
     */
    int maxTotalMemories() default 100;

    /**
     * The maximum number of net new memories that the {@code MemoryDistillerAgent} may add
     * in a single distillation pass (i.e.&nbsp;{@code stored − revoked ≤ maxMemoriesAdded}).
     *
     * <p>This acts as a per-distillation budget, independent of {@link #maxTotalMemories()}.
     * Set to a low value to avoid overwhelming the memory store with many entries in a single
     * agent run.
     */
    int maxMemoriesAdded() default 10;

    /**
     * Command classes to be processed by the AI agent instead of a deterministic
     * {@code @CommandHandler} method. Each class must implement {@link Command} and be
     * annotated with {@code @CommandInfo}.
     *
     * <p>Agent-handled commands cannot create aggregate state — every agentic aggregate must
     * have a separate deterministic {@code @CommandHandler(create = true)} method.
     */
    Class<? extends Command>[] agentHandledCommands() default {};

    /**
     * External domain event classes to be processed by the AI agent instead of a
     * deterministic {@code @EventHandler} method. Each class must implement
     * {@link DomainEvent} and be annotated with {@code @DomainEventInfo}.
     */
    Class<? extends DomainEvent>[] agentHandledEvents() default {};

    /**
     * Error event classes that the AI agent may produce during command or event processing.
     * Each class must implement {@link ErrorEvent} and be annotated with
     * {@code @DomainEventInfo}.
     *
     * <p>These types are registered as {@code DomainEventType(error=true)} for JSON schema
     * validation and service discovery. Agent-produced error events that are <em>not</em>
     * declared here are not registered with the runtime and will be silently excluded from
     * processing — they will not be serialized to the Kafka domain-events topic. A warning
     * is logged for each excluded event so that operators can detect the misconfiguration.
     *
     * <p>To ensure an error event produced by the agent is persisted and visible to
     * downstream consumers, it must be declared here.
     */
    Class<? extends ErrorEvent>[] agentProducedErrors() default {};
}
