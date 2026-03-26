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

/**
 * Represents a field in the aggregate state definition with Akces-specific metadata.
 *
 * @param name        the field name
 * @param type        the field type (using event-modeling type names)
 * @param idAttribute whether this field is the aggregate identifier
 * @param piiData     whether this field contains PII data requiring GDPR protection
 * @param optional    whether the field is optional (nullable)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StateField(
        String name,
        String type,
        boolean idAttribute,
        boolean piiData,
        boolean optional
) {
}
