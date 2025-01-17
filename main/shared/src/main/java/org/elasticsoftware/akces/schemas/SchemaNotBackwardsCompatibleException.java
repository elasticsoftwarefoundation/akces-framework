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

package org.elasticsoftware.akces.schemas;

import java.util.List;

public class SchemaNotBackwardsCompatibleException extends SchemaException {
    private final int previousSchemaVersion;
    private final int schemaVersion;
    private final List<String> differences;

    public SchemaNotBackwardsCompatibleException(String schemaIdentifier,
                                                 int previousSchemaVersion,
                                                 int schemaVersion,
                                                 Class<?> implementationClass,
                                                 List<String> differences) {
        super("Schema not backwards compatible with previous version: " + previousSchemaVersion, schemaIdentifier, implementationClass);
        this.previousSchemaVersion = previousSchemaVersion;
        this.schemaVersion = schemaVersion;
        this.differences = differences;
    }

    public int getPreviousSchemaVersion() {
        return previousSchemaVersion;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public List<String> getDifferences() {
        return differences;
    }

}
