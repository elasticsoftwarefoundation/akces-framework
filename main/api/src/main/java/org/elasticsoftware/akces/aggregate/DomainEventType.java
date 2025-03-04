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

package org.elasticsoftware.akces.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.elasticsoftware.akces.events.DomainEvent;

public record DomainEventType<T extends DomainEvent>(
        String typeName,
        int version,
        @JsonIgnore Class<T> typeClass,
        boolean create,
        boolean external,
        boolean error,
        Boolean piiData
) implements SchemaType {
    public DomainEventType(String typeName,
                           int version,
                           Class<T> typeClass,
                           boolean create,
                           boolean external,
                           boolean error) {
        this(typeName, version, typeClass, create, external, error, null);
    }

    @Override
    public String getSchemaPrefix() {
        return "domainevents.";
    }

    @Override
    public boolean relaxExternalValidation() {
        return true;
    }
}
