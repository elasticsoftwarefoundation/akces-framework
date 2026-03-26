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
 * Represents a field definition from the event-modeling schema.
 *
 * @param name               the field name
 * @param type               the field type (String, Boolean, Decimal, Long, Int, etc.)
 * @param optional           whether the field is optional (nullable)
 * @param idAttribute        whether this field is the aggregate identifier
 * @param cardinality        Single or List
 * @param subfields          nested fields for Custom types
 * @param technicalAttribute whether this is a technical attribute (not business relevant)
 * @param generated          whether this field value is generated
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Field(
        String name,
        String type,
        Boolean optional,
        Boolean idAttribute,
        String cardinality,
        List<Field> subfields,
        Boolean technicalAttribute,
        Boolean generated
) {
    public Field {
        if (subfields == null) {
            subfields = List.of();
        }
    }

    /**
     * Returns true if this field is the aggregate identifier.
     */
    public boolean isIdAttribute() {
        return idAttribute != null && idAttribute;
    }

    /**
     * Returns true if this field is optional (nullable).
     */
    public boolean isOptional() {
        return optional != null && optional;
    }

    /**
     * Returns true if this field is a list.
     */
    public boolean isList() {
        return "List".equals(cardinality);
    }
}
