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
 * Akces-specific configuration for an aggregate, complementing the event-modeling slices.
 *
 * @param indexed                whether the aggregate is indexed
 * @param indexName              the index name for the aggregate
 * @param generateGDPRKeyOnCreate whether to generate a GDPR key on create
 * @param stateVersion           the version of the aggregate state
 * @param stateFields            the fields of the aggregate state
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AggregateConfig(
        boolean indexed,
        String indexName,
        boolean generateGDPRKeyOnCreate,
        int stateVersion,
        List<StateField> stateFields
) {
    public AggregateConfig {
        if (stateVersion <= 0) {
            stateVersion = 1;
        }
        if (stateFields == null) {
            stateFields = List.of();
        }
    }
}
