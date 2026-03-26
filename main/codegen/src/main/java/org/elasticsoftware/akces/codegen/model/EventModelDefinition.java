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
import java.util.Map;

/**
 * Root model for the Akces code generator input.
 * Combines event-modeling slices with Akces-specific aggregate configuration.
 *
 * @param packageName     the base Java package for generated code
 * @param aggregateConfig per-aggregate configuration (keyed by aggregate name)
 * @param slices          event-modeling slices defining commands, events, and their relationships
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventModelDefinition(
        String packageName,
        Map<String, AggregateConfig> aggregateConfig,
        List<Slice> slices
) {
}
