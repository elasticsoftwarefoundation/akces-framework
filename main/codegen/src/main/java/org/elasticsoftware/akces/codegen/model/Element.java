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

package org.elasticsoftware.akces.codegen.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Represents an element in an event-modeling slice (command, event, readmodel, etc.).
 *
 * @param id                  unique identifier
 * @param title               human-readable title (used as the basis for class naming)
 * @param type                element type (COMMAND, EVENT, READMODEL, SCREEN, AUTOMATION)
 * @param fields              the fields of this element
 * @param aggregate           the aggregate this element belongs to
 * @param createsAggregate    whether this element creates a new aggregate instance
 * @param tags                tags for metadata (e.g., "error" for error events)
 * @param description         optional description
 * @param dependencies        dependencies on other elements
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Element(
        String id,
        String title,
        String type,
        List<Field> fields,
        String aggregate,
        Boolean createsAggregate,
        List<String> tags,
        String description,
        List<Dependency> dependencies
) {
    public Element {
        if (fields == null) {
            fields = List.of();
        }
        if (tags == null) {
            tags = List.of();
        }
        if (dependencies == null) {
            dependencies = List.of();
        }
    }

    /**
     * Returns true if this element is tagged as an error event.
     */
    public boolean isError() {
        return tags.contains("error");
    }
}
