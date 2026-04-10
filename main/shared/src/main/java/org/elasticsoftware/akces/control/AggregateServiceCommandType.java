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

import jakarta.annotation.Nullable;
import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.commands.Command;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

/**
 * Describes a command type supported by an aggregate service, as published on the
 * {@code Akces-Control} topic.
 *
 * @param typeName    the logical name of the command type
 * @param version     the schema version of the command
 * @param create      whether this command creates a new aggregate instance
 * @param schemaName  the schema registry subject name for this command
 * @param description a human-readable description of the command; may be {@code null}
 */
public record AggregateServiceCommandType(
        String typeName,
        int version,
        boolean create,
        String schemaName,
        @Nullable String description
) {
    /**
     * Converts this service command type into a {@link CommandType} for use as an external
     * command type within an aggregate runtime.
     *
     * @param typeClass the Java class of the command
     * @param <C>       the command type
     * @return a new {@link CommandType} instance
     */
    public <C extends Command> CommandType<C> toExternalCommandType(Class<C> typeClass) {
        return new CommandType<>(typeName, version, typeClass, create, true, hasPIIDataAnnotation(typeClass));
    }
}
