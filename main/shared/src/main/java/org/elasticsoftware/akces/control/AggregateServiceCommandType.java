/*
 * Copyright 2022 - 2025 The Original Authors
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

import org.elasticsoftware.akces.aggregate.CommandType;
import org.elasticsoftware.akces.commands.Command;

import static org.elasticsoftware.akces.gdpr.GDPRAnnotationUtils.hasPIIDataAnnotation;

public record AggregateServiceCommandType(
        String typeName,
        int version,
        boolean create,
        String schemaName
) {
    public <C extends Command> CommandType<C> toLocalCommandType(Class<C> typeClass) {
        return new CommandType<>(typeName, version, typeClass, create, true, hasPIIDataAnnotation(typeClass));
    }
}
