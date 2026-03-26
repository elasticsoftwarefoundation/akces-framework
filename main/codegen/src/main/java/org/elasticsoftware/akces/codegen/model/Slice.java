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
 * Represents an event-modeling slice, which groups related commands and events
 * that form a coherent use case or business operation.
 *
 * @param id         unique identifier for the slice
 * @param title      human-readable title
 * @param sliceType  the type of slice (STATE_CHANGE, STATE_VIEW, AUTOMATION)
 * @param commands   commands in this slice
 * @param events     events in this slice
 * @param aggregates aggregate names referenced by this slice
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Slice(
        String id,
        String title,
        String sliceType,
        List<Element> commands,
        List<Element> events,
        List<String> aggregates
) {
    public Slice {
        if (commands == null) {
            commands = List.of();
        }
        if (events == null) {
            events = List.of();
        }
        if (aggregates == null) {
            aggregates = List.of();
        }
    }
}
